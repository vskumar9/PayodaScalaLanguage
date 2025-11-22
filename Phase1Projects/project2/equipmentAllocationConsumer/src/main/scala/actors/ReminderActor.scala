package actors

import akka.actor.{Actor, ActorLogging, Props}
import play.api.libs.json._
import scala.concurrent.Future
import scala.util.Try
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import models._
import repositories._
import mail.Mailer

/**
 * Companion object for [[ReminderActor]].
 *
 * Provides a `props` factory and the message types consumed by the actor.
 */
object ReminderActor {
  /**
   * Create Props for a [[ReminderActor]].
   *
   * @param allocRepo    Repository to load equipment allocations.
   * @param itemRepo     Repository to load equipment item metadata.
   * @param employeeRepo Repository to map employee -> user.
   * @param userRepo     Repository to list/find users (used to find admins).
   * @param emailService Mailer used to send HTML/plain emails.
   * @return Props suitable for actor creation.
   */
  def props(
             allocRepo: EquipmentAllocationRepository,
             itemRepo: EquipmentItemRepository,
             employeeRepo: EmployeeRepository,
             userRepo: UserRepository,
             emailService: Mailer
           ): Props = Props(new ReminderActor(allocRepo, itemRepo, employeeRepo, userRepo, emailService))

  /**
   * Message accepted by the actor representing a domain equipment event.
   *
   * @param ev EquipmentEvent containing routing key and JSON `payload`.
   */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor responsible for sending reminders related to equipment allocations.
 *
 * Responsibilities:
 *  - Handle overdue allocation reminders (OVERDUE, OVERDUE_REMINDER).
 *  - Handle generic reminders (REMINDER, REMINDER_EMAIL), which may target explicit emails,
 *    allocate-specific recipients, or admin recipients when no explicit target is provided.
 *  - Compose friendly HTML + plain-text fallback messages and attempt HTML first.
 *
 * Notes:
 *  - The actor prefers human-friendly timestamps formatted in the "Asia/Kolkata" zone.
 *  - Recipient ordering is preserved where applicable (e.g., assignee, allocatedBy, then admins),
 *    with deduplication performed while preserving first-seen ordering.
 *
 * @param allocRepo    Repository for allocations (used to resolve allocation details).
 * @param itemRepo     Repository for equipment items.
 * @param employeeRepo Repository for employee -> user resolution.
 * @param userRepo     Repository for user queries (used to find admins).
 * @param emailService Mailer used to deliver notifications.
 */
class ReminderActor(
                     allocRepo: EquipmentAllocationRepository,
                     itemRepo: EquipmentItemRepository,
                     employeeRepo: EmployeeRepository,
                     userRepo: UserRepository,
                     emailService: Mailer
                   ) extends Actor with ActorLogging {

  import ReminderActor._
  import context.dispatcher

  /**
   * Zone used in all human-friendly timestamps emitted by this actor.
   */
  private val displayZone: ZoneId = ZoneId.of("Asia/Kolkata")

  /**
   * DateTimeFormatter used for email bodies and logs.
   * Pattern uses year-month-day hour:minute:second zone.
   */
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-LL-dd HH:mm:ss z").withZone(displayZone)

  /** Format an Instant to the configured display format. */
  private def fmtInstant(i: Instant): String = displayFmt.format(i)

  /** Format an optional Instant, returning "N/A" when missing. */
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")

  /**
   * Primary message handler.
   *
   * Accepts:
   *  - `HandleEvent(ev)` where ev.payload contains an `eventType` field.
   *  - Routes recognized event types to specific handlers:
   *      * OVERDUE / OVERDUE_REMINDER -> [[handleOverdueReminder]]
   *      * REMINDER / REMINDER_EMAIL -> [[handleGenericReminder]]
   *  - Logs warnings for missing or unhandled event types.
   */
  override def receive: Receive = {
    case HandleEvent(ev) =>
      val p = ev.payload
      val payloadEventType = (p \ "eventType").asOpt[String].map(_.trim.toUpperCase)
      log.info(s"[ReminderActor] Received payloadEventType=$payloadEventType payload=${safePretty(p)}")

      payloadEventType match {
        case Some(t) if Set("OVERDUE", "OVERDUE_REMINDER").contains(t) =>
          handleOverdueReminder(t, p).recover { case ex =>
            log.error(ex, s"[ReminderActor] handleOverdueReminder failed for payload=${safePretty(p)}")
          }

        case Some(t) if Set("REMINDER", "REMINDER_EMAIL").contains(t) =>
          handleGenericReminder(t, p).recover { case ex =>
            log.error(ex, s"[ReminderActor] handleGenericReminder failed for payload=${safePretty(p)}")
          }

        case Some(other) =>
          log.warning(s"[ReminderActor] Unhandled reminder eventType=$other payload=${safePretty(p)}")

        case None =>
          log.warning(s"[ReminderActor] Missing eventType inside payload: ${safePretty(p)}")
      }

    case other =>
      log.debug(s"[ReminderActor] Ignoring unsupported message: $other")
  }

  /**
   * Handle overdue allocation reminders.
   *
   * Steps:
   *  - Resolve allocation id from payload (`allocationId`, `allocId`, or `id` accepted).
   *  - Load allocation; if absent, log and return.
   *  - Load equipment, assignee user, allocated-by user, and admin users in parallel.
   *  - Construct recipients in order: assignee, allocatedBy, then admins; dedupe by email preserving first-seen.
   *  - Build role-specific HTML + plain text bodies via [[makeOverdueMessage]] and attempt to send HTML first,
   *    falling back to plain text on failure.
   *
   * Returns a `Future[Unit]`.
   *
   * @param eventType Event type string (e.g. "OVERDUE").
   * @param payload   Payload JSON expected to contain allocation identifiers.
   */
  private def handleOverdueReminder(eventType: String, payload: JsObject): Future[Unit] = {
    val allocationIdOpt = getLong(payload, "allocationId").orElse(getLong(payload, "allocId")).orElse(getLong(payload, "id"))

    allocationIdOpt match {
      case None =>
        log.warning(s"[ReminderActor] OVERDUE payload missing allocationId: ${safePretty(payload)}")
        Future.successful(())

      case Some(aidLong) =>
        Try(aidLong.toInt).toOption match {
          case None =>
            log.warning(s"[ReminderActor] allocationId $aidLong is out of Int range; skipping payload=${safePretty(payload)}")
            Future.successful(())
          case Some(aid) =>
            // 1) load allocation
            allocRepo.findById(aid).flatMap {
              case None =>
                log.warning(s"[ReminderActor] Allocation id=$aid not found for payload=${safePretty(payload)}")
                Future.successful(())

              case Some(allocation) =>
                // 2) look up item, assignee user, allocatedBy user, admins in parallel
                val equipF = itemRepo.findById(allocation.equipmentId)

                val assigneeUserF = employeeRepo.findById(allocation.employeeId).flatMap {
                  case Some(emp) => userRepo.findById(emp.userId)
                  case None => Future.successful(None)
                }

                val allocatedByUserF = employeeRepo.findById(allocation.allocatedByEmployeeId).flatMap {
                  case Some(emp) => userRepo.findById(emp.userId)
                  case None => Future.successful(None)
                }

                val adminsF = userRepo.findAll().map(_.filter(u => u.roles.exists(_.equalsIgnoreCase("admin"))))

                for {
                  equipOpt <- equipF
                  assigneeUserOpt <- assigneeUserF
                  allocatedByUserOpt <- allocatedByUserF
                  admins <- adminsF
                } yield {
                  // build recipients in order (assignee, allocatedBy, admins), dedupe by email
                  val base = Seq(
                    assigneeUserOpt.flatMap(u => Option(u.email)).map(e => ("Assignee", e)),
                    allocatedByUserOpt.flatMap(u => Option(u.email)).map(e => ("AllocatedBy", e))
                  ).flatten

                  val adminRecipients = admins.flatMap(u => Option(u.email).map(e => ("Admin", e)))
                  val rawRecipients = base ++ adminRecipients

                  val recipientsDistinct = rawRecipients.foldLeft(Seq.empty[(String, String)]) {
                    case (acc, entry @ (_, email)) =>
                      if (acc.exists(_._2 == email)) acc else acc :+ entry
                  }

                  if (recipientsDistinct.isEmpty) {
                    log.info(s"[ReminderActor] No recipient emails found for overdue allocation=$aid payload=${safePretty(payload)}")
                    Future.successful(())
                  } else {
                    // expectedReturn preference: payload string -> allocation.expectedReturnAt
                    val expectedReturnFromPayload: Option[String] = (payload \ "expectedReturnAt").asOpt[String]
                    val expectedFromAllocation: Option[String] = {
                      Try(fmtInstant(allocation.expectedReturnAt)).toOption
                        .orElse(Try(fmtOptInstant(allocation.expectedReturnAt.asInstanceOf[Option[Instant]])).toOption)
                        .filterNot(_ == "N/A")
                    }
                    val expectedReturnOpt = expectedReturnFromPayload.orElse(expectedFromAllocation)

                    val subject = s"[Reminder] Overdue Allocation #${allocation.allocationId}"

                    val equipmentDesc = equipOpt.map(e => s"${e.assetTag} / SN:${e.serialNumber} (id=${e.equipmentId})")
                      .getOrElse(s"(equipment id=${allocation.equipmentId})")

                    val sends: Seq[Future[Unit]] = recipientsDistinct.map { case (role, email) =>
                      val (subjectFor, htmlBody, plainBody) = makeOverdueMessage(role, eventType, allocation, equipmentDesc, expectedReturnOpt)
                      // prefer HTML send, fallback to plain
                      emailService.sendHtml(email, subjectFor, htmlBody).map { _ =>
                        log.info(s"[ReminderActor] Email sent to=$email role=$role allocation=${allocation.allocationId}")
                      }.recoverWith { case ex =>
                        log.error(ex, s"[ReminderActor] sendHtml failed for $email role=$role; attempting plain text send")
                        emailService.send(email, subjectFor, plainBody).map { _ =>
                          log.info(s"[ReminderActor] Fallback plain email sent to=$email role=$role allocation=${allocation.allocationId}")
                        }.recover { case ex2 =>
                          log.error(ex2, s"[ReminderActor] Fallback plain send failed for $email role=$role allocation=${allocation.allocationId}")
                        }
                      }
                    }

                    Future.sequence(sends).map(_ => ())
                  }
                } // end for-yield (returns Future[Unit] via callers)
            } // end allocRepo.flatMap
        } // end Try match
    } // end allocationIdOpt match
  }

  /**
   * Handle generic reminders.
   *
   * Behavior:
   *  - If `emails` or `email` field present in payload, sends to those explicit addresses.
   *  - Else if payload contains `allocationId`, delegates to [[handleOverdueReminder]].
   *  - Else, sends reminder to admin users.
   *
   * Returns a `Future[Unit]`.
   *
   * @param eventType Event type string (e.g. "REMINDER").
   * @param payload   JSON payload that may include `emails`, `email`, or `allocationId`.
   */
  private def handleGenericReminder(eventType: String, payload: JsObject): Future[Unit] = {
    val explicitEmails: Seq[String] =
      (payload \ "emails").asOpt[JsArray]
        .map(_.value.flatMap(_.asOpt[String]).map(_.trim).filter(_.nonEmpty).toSeq)
        .getOrElse(Seq.empty) ++ (payload \ "email").asOpt[String].toSeq

    if (explicitEmails.nonEmpty) {
      val emails = explicitEmails.distinct
      val subject = s"[Reminder] $eventType"
      val bodyPlain = Seq(
        s"EventType : $eventType",
        s"GeneratedAt: ${fmtInstant(Instant.now())}"
      ).mkString("\n")

      log.info(s"[ReminderActor] Prepared generic reminder -> to=[${emails.mkString(",")}] subject='$subject'")

      val sends = emails.map { email =>
        emailService.sendHtml(email, subject, bodyPlain).map { _ =>
          log.info(s"[ReminderActor] Email sent to=$email event=$eventType")
        }.recover { case ex =>
          log.error(ex, s"[ReminderActor] Error sending email to=$email event=$eventType")
        }
      }

      Future.sequence(sends).map(_ => ())
    } else {
      // if allocationId present -> delegate to overdue, else notify admins
      getLong(payload, "allocationId") match {
        case Some(_) => handleOverdueReminder(eventType, payload)
        case None =>
          userRepo.findAll().flatMap { users =>
            val adminEmails = users.filter(u => u.roles.exists(_.equalsIgnoreCase("admin"))).flatMap(u => Option(u.email)).distinct
            if (adminEmails.isEmpty) {
              log.info(s"[ReminderActor] No admin emails found for generic reminder payload=${safePretty(payload)}")
              Future.successful(())
            } else {
              val subject = s"[Reminder] $eventType"
              val body = Seq(
                s"EventType : $eventType",
                s"GeneratedAt: ${fmtInstant(Instant.now())}"
              ).mkString("\n")

              log.info(s"[ReminderActor] Prepared generic reminder -> to=[${adminEmails.mkString(",")}] subject='$subject'")

              val sends = adminEmails.map { email =>
                emailService.sendHtml(email, subject, body).map { _ =>
                  log.info(s"[ReminderActor] Email sent to admin=$email event=$eventType")
                }.recover { case ex =>
                  log.error(ex, s"[ReminderActor] Error sending email to admin=$email event=$eventType")
                }
              }

              Future.sequence(sends).map(_ => ())
            }
          }.recover { case ex =>
            log.error(ex, s"[ReminderActor] Error fetching admins for payload=${safePretty(payload)}")
            ()
          }
      }
    }
  }

  /**
   * Build subject, HTML body and plain-text body for overdue notifications according to recipient role.
   *
   * Roles treated specially:
   *  - "Assignee": requests action from the person holding the allocation.
   *  - "AllocatedBy": informs allocator that the assignee is overdue.
   *  - others (including "Admin") receive a neutral reminder.
   *
   * @param role               Recipient role label (e.g., "Assignee", "AllocatedBy", "Admin").
   * @param eventType          Event type that caused the reminder.
   * @param allocation         Allocation record used to populate details.
   * @param equipmentDesc      Friendly equipment description string.
   * @param expectedReturnOpt  Optional expected-return string (payload preferred).
   * @return Tuple(subject, htmlBody, plainBody).
   */
  private def makeOverdueMessage(
                                  role: String,
                                  eventType: String,
                                  allocation: EquipmentAllocation,
                                  equipmentDesc: String,
                                  expectedReturnOpt: Option[String]
                                ): (String, String, String) = {

    val allocId = allocation.allocationId
    val allocatedAtStr = Try(fmtInstant(allocation.allocatedAt)).getOrElse(allocation.allocatedAt.toString)
    val expectedReturnStr = expectedReturnOpt.getOrElse(Try(fmtInstant(allocation.expectedReturnAt)).getOrElse(allocation.expectedReturnAt.toString))
    val statusStr = Option(allocation.status).getOrElse("N/A")

    val basePlainHeader = Seq(
      s"EventType : $eventType",
      s"Allocation : $allocId",
      s"Equipment  : $equipmentDesc"
    ).mkString("\n")

    val baseHtmlHeader =
      s"""
         |<p><strong>Event:</strong> ${escapeHtml(eventType)}</p>
         |<p><strong>Allocation:</strong> ${allocId}</p>
         |<p><strong>Equipment:</strong> ${escapeHtml(equipmentDesc)}</p>
       """.stripMargin

    val subject = s"[Reminder] Allocation #$allocId"

    val (html, plain) = (role.toLowerCase) match {
      case "assignee" =>
        val plainBody = Seq(
          basePlainHeader,
          s"Action    : Please return or update the expected return date",
          s"AllocatedAt: $allocatedAtStr",
          s"ExpectedReturnAt: $expectedReturnStr",
          s"Status    : $statusStr"
        ).mkString("\n\n")

        val htmlBody =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Allocation Reminder</h3>
             |  $baseHtmlHeader
             |  <p><strong>Allocated At:</strong> ${escapeHtml(allocatedAtStr)}</p>
             |  <p><strong>Expected Return:</strong> ${escapeHtml(expectedReturnStr)}</p>
             |  <p><strong>Status:</strong> ${escapeHtml(statusStr)}</p>
             |  <p>Please return the item or contact the Inventory team to extend the due date.</p>
             |  <p style="font-size:smaller;color:#666;">SentAt: ${escapeHtml(fmtInstant(Instant.now()))}</p>
             |</body></html>""".stripMargin

        (htmlBody, plainBody)

      case "allocatedby" =>
        val plainBody = Seq(
          basePlainHeader,
          s"Action    : The assignee's allocation is overdue",
          s"AllocatedAt: $allocatedAtStr",
          s"ExpectedReturnAt: $expectedReturnStr",
          s"Status    : $statusStr"
        ).mkString("\n\n")

        val htmlBody =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Assignee Overdue Notification</h3>
             |  $baseHtmlHeader
             |  <p><strong>Allocated At:</strong> ${escapeHtml(allocatedAtStr)}</p>
             |  <p><strong>Expected Return:</strong> ${escapeHtml(expectedReturnStr)}</p>
             |  <p><strong>Status:</strong> ${escapeHtml(statusStr)}</p>
             |  <p style="font-size:smaller;color:#666;">SentAt: ${escapeHtml(fmtInstant(Instant.now()))}</p>
             |</body></html>""".stripMargin

        (htmlBody, plainBody)

      case _ =>
        val plainBody = Seq(
          basePlainHeader,
          s"AllocatedAt: $allocatedAtStr",
          s"ExpectedReturnAt: $expectedReturnStr",
          s"Status    : $statusStr"
        ).mkString("\n\n")

        val htmlBody =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Allocation Reminder</h3>
             |  $baseHtmlHeader
             |  <p><strong>Allocated At:</strong> ${escapeHtml(allocatedAtStr)}</p>
             |  <p><strong>Expected Return:</strong> ${escapeHtml(expectedReturnStr)}</p>
             |  <p style="font-size:smaller;color:#666;">SentAt: ${escapeHtml(fmtInstant(Instant.now()))}</p>
             |</body></html>""".stripMargin

        (htmlBody, plainBody)
    }

    (subject, html, plain)
  }

  /**
   * Safely parse a long field from a JsObject.
   *
   * Accepts numeric JSON values or numeric strings.
   *
   * @param js    JSON object.
   * @param field Field name to extract.
   * @return Some(long) when value present and parseable, otherwise None.
   */
  private def getLong(js: JsObject, field: String): Option[Long] =
    (js \ field).asOpt[JsValue].flatMap {
      case JsNumber(n) => Try(n.toLong).toOption
      case JsString(s) => Try(s.trim.toLong).toOption
      case _ => None
    }

  /**
   * Produce a compact single-line JSON string for logging, truncated to `max`.
   *
   * @param js  JSON value to stringify.
   * @param max Maximum length to return (default 1024). Longer content is truncated with "...(truncated)".
   * @return String representation safe for logs.
   */
  private def safePretty(js: JsValue, max: Int = 1024): String = {
    val s = Json.stringify(js)
    if (s.length <= max) s else s.take(max) + "...(truncated)"
  }

  /**
   * Minimal HTML-escaping utility for safe email HTML composition.
   *
   * Escapes &, <, >, ", and ' characters.
   *
   * @param s Input string (null/None treated as empty).
   * @return Escaped string safe to embed inside HTML content.
   */
  private def escapeHtml(s: String): String =
    Option(s).getOrElse("")
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
}
