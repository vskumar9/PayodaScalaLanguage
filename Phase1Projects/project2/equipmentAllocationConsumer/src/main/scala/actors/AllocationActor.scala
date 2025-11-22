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
 * Factory and message definitions for AllocationActor.
 *
 * The actor listens for equipment-related events (wrapped as `EquipmentEvent`)
 * and composes/sends notification emails to relevant recipients (assignee,
 * allocatedBy, and admin users). The actor performs non-blocking repository
 * lookups and uses the provided Mailer to send HTML (preferred) or plain text
 * fallback emails.
 */
object AllocationActor {

  /**
   * Create Props for an AllocationActor.
   *
   * @param allocRepo   repository for EquipmentAllocation lookups and updates
   * @param itemRepo    repository for EquipmentItem lookups
   * @param employeeRepo repository for Employee lookups
   * @param userRepo    repository for User lookups (used to fetch admin email addresses)
   * @param emailService mailer used to send emails (HTML and plain text)
   * @return akka Props for creating the actor
   */
  def props(
             allocRepo: EquipmentAllocationRepository,
             itemRepo: EquipmentItemRepository,
             employeeRepo: EmployeeRepository,
             userRepo: UserRepository,
             emailService: Mailer
           ): Props = Props(new AllocationActor(allocRepo, itemRepo, employeeRepo, userRepo, emailService))

  /**
   * Message to instruct the actor to handle an equipment-related event.
   *
   * @param ev EquipmentEvent containing a JSON payload describing the event
   */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor responsible for converting equipment events into notification emails.
 *
 * Behaviour summary:
 *  - Receives `AllocationActor.HandleEvent(EquipmentEvent)` messages.
 *  - Extracts `eventType` and `allocationId` from the event payload.
 *  - Loads allocation, equipment, user and employee data from repositories.
 *  - Builds role-specific messages (subject, HTML body and plain body).
 *  - Sends HTML email; on failure falls back to plain text email.
 *
 * Implementation notes:
 *  - This actor performs asynchronous repository calls; futures are used
 *    and errors are handled (logged) without blocking the actor message loop.
 *  - Duplicate recipient emails are deduplicated while preserving first-seen order.
 *
 * @param allocRepo    equipment allocation repository
 * @param itemRepo     equipment item repository
 * @param employeeRepo employee repository
 * @param userRepo     user repository (used to locate admin emails)
 * @param emailService mailer used to deliver emails
 */
class AllocationActor(
                       allocRepo: EquipmentAllocationRepository,
                       itemRepo: EquipmentItemRepository,
                       employeeRepo: EmployeeRepository,
                       userRepo: UserRepository,
                       emailService: Mailer
                     ) extends Actor with ActorLogging {

  import AllocationActor._
  import context.dispatcher

  // ---------- date/time formatter (human-friendly) ----------
  private val displayZone: ZoneId = ZoneId.of("Asia/Kolkata")
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(displayZone)

  private def fmtInstant(i: Instant): String = displayFmt.format(i)
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")
  private def fmtNullableInstant(i: Instant): String = Option(i).map(displayFmt.format).getOrElse("N/A")

  /**
   * Actor message handling.
   *
   * Recognised event types include:
   *  - CREATE, ALLOCATION, RETURN, MAINTENANCE_ALERT, MAINTENANCE, OVERDUE_REMINDER, REMINDER
   *
   * Unknown or missing event types are logged and ignored.
   */
  override def receive: Receive = {
    case HandleEvent(ev) =>
      val payload = ev.payload
      val eventTypeOpt = (payload \ "eventType").asOpt[String].map(_.trim.toUpperCase)

      eventTypeOpt match {
        case Some(t) if Set("CREATE", "ALLOCATION", "RETURN", "MAINTENANCE_ALERT", "MAINTENANCE", "OVERDUE_REMINDER", "REMINDER").contains(t) =>
          log.info(s"[AllocationActor] Received eventType=$t payload=${safePretty(payload)}")
          handleNotifyOnly(t, payload)

        case Some(other) =>
          log.warning(s"[AllocationActor] Unhandled eventType=$other payload=${safePretty(payload)}")

        case None =>
          log.warning(s"[AllocationActor] Missing eventType inside payload: ${safePretty(payload)}")
      }

    case other =>
      log.debug(s"[AllocationActor] Ignoring unsupported message: $other")
  }

  /**
   * Asynchronously load allocation and related data, then compose and send emails.
   *
   * The method:
   *  - extracts `allocationId` from payload
   *  - loads allocation, equipment, assignee, allocatedBy and admin users
   *  - builds recipient list preserving order [Assignee, AllocatedBy, Admins]
   *  - deduplicates recipients by email (first-seen wins)
   *  - composes subject/html/plain body per role & eventType via `makeMessageFor`
   *  - attempts HTML send, falls back to plain on failure
   *
   * Errors during DB loads or email sends are logged; they do not crash the actor.
   *
   * @param eventType high-level event type (upper-cased)
   * @param payload JSON payload expected to contain at least allocationId
   */
  private def handleNotifyOnly(eventType: String, payload: JsObject): Unit = {
    val allocationIdOpt = getInt(payload, "allocationId")

    allocationIdOpt match {
      case Some(aid) =>
        // Start asynchronous processing (do not block actor)
        allocRepo.findById(aid).flatMap {
          case Some(allocation) =>
            // parallel lookups
            val equipmentF   = itemRepo.findById(allocation.equipmentId)
            val assigneeEmpF = employeeRepo.findById(allocation.employeeId)
            val byEmpF       = employeeRepo.findById(allocation.allocatedByEmployeeId)
            val adminsF      = userRepo.findAll().map(_.filter(u => u.roles.exists(_.equalsIgnoreCase("admin"))))

            for {
              equipOpt        <- equipmentF
              assigneeEmpOpt  <- assigneeEmpF
              byEmpOpt        <- byEmpF
              assigneeUserOpt <- assigneeEmpOpt match {
                case Some(emp) => userRepo.findById(emp.userId)
                case None      => Future.successful(None)
              }
              byUserOpt <- byEmpOpt match {
                case Some(emp) => userRepo.findById(emp.userId)
                case None      => Future.successful(None)
              }
              adminUsers <- adminsF
              // result: Future[Unit] returned so outer flatMap resolves to Future[Unit]
              result <- {
                // build recipient candidates preserving order:
                // 1) Assignee (if user email present)
                // 2) AllocatedBy (if user email present)
                // 3) Admins (all admin emails)
                val baseRecipients: Seq[(String, Option[User])] = Seq(
                  ("Assignee", assigneeUserOpt),
                  ("AllocatedBy", byUserOpt)
                )

                val adminRecipients: Seq[(String, Option[User])] = adminUsers.map(u => ("Admin", Some(u)))

                val rawRecipients: Seq[(String, Option[User])] = baseRecipients ++ adminRecipients

                // dedupe by email preserving first-seen order and skip missing emails
                val recipientsDistinct: Seq[(String, Option[User], String)] = rawRecipients.foldLeft(Seq.empty[(String, Option[User], String)]) {
                  case (acc, (role, userOpt)) =>
                    val emailOpt = userOpt.flatMap(u => Option(u.email)).map(_.trim).filter(_.nonEmpty)
                    emailOpt match {
                      case Some(email) =>
                        if (acc.exists(_._3 == email)) acc else acc :+ (role, userOpt, email)
                      case None => acc
                    }
                }

                if (recipientsDistinct.isEmpty) {
                  log.info(s"[AllocationActor] No recipient emails found for allocation=${allocation.allocationId}; payload=${safePretty(payload)}")
                  Future.successful(())
                } else {
                  // prepare frequently used fields
                  val equipmentDesc = equipOpt.map(e => s"${e.assetTag} / SN:${e.serialNumber} (id=${e.equipmentId})")
                    .getOrElse(s"(equipment id=${allocation.equipmentId})")

                  val assigneeDesc = (assigneeEmpOpt, assigneeUserOpt) match {
                    case (Some(emp), Some(user)) => s"${user.fullName} (EmployeeId=${emp.employeeId})"
                    case (Some(emp), None)       => s"EmployeeId=${emp.employeeId} (user missing)"
                    case _                       => "(assignee not found)"
                  }

                  val allocatedByDesc = (byEmpOpt, byUserOpt) match {
                    case (Some(emp), Some(user)) => s"${user.fullName} (EmployeeId=${emp.employeeId})"
                    case (Some(emp), None)       => s"EmployeeId=${emp.employeeId} (user missing)"
                    case _                       => "(allocatedBy not found)"
                  }

                  // send per-recipient (HTML preferred, plain fallback)
                  val sendFs: Seq[Future[Unit]] = recipientsDistinct.map { case (role, userOpt, email) =>
                    val (subject, htmlBody, plainBody) = makeMessageFor(
                      role = role,
                      recipientUserOpt = userOpt,
                      eventType = eventType,
                      allocation = allocation,
                      equipmentOpt = equipOpt,
                      assigneeDesc = assigneeDesc,
                      allocatedByDesc = allocatedByDesc
                    )

                    // emailService.sendHtml/send return Future[Unit] (per your Mailer)
                    emailService.sendHtml(email, subject, htmlBody).map { _ =>
                      log.info(s"[AllocationActor] Email sent (HTML) to=$email role=$role allocation=${allocation.allocationId}")
                    }.recoverWith { case ex =>
                      log.error(ex, s"[AllocationActor] sendHtml failed for $email role=$role; attempting plain text send")
                      emailService.send(email, subject, plainBody).map { _ =>
                        log.info(s"[AllocationActor] Fallback plain email sent to=$email role=$role allocation=${allocation.allocationId}")
                      }.recover { case ex2 =>
                        log.error(ex2, s"[AllocationActor] Fallback plain send failed for $email role=$role allocation=${allocation.allocationId}")
                      }
                    }
                  }

                  Future.sequence(sendFs).map(_ => ())
                }
              }
            } yield result

          case None =>
            log.warning(s"[AllocationActor] Allocation id=$aid not found in DB for eventType=$eventType payload=${safePretty(payload)}")
            Future.successful(())
        }.recover { case ex =>
          log.error(ex, s"[AllocationActor] DB error while loading allocationId=$aid for eventType=$eventType payload=${safePretty(payload)}")
          ()
        }

        () // don't block actor

      case None =>
        log.warning(s"[AllocationActor] payload missing allocationId; cannot fetch data. payload=${safePretty(payload)}")
    }
  }

  /**
   * Create a role-specific subject, HTML body and plain-text body for the notification email.
   *
   * Messages intentionally omit raw payload JSON to keep emails concise and user-friendly.
   *
   * @param role recipient role (e.g., "Assignee", "AllocatedBy", "Admin")
   * @param recipientUserOpt optional User model for the recipient (used for personalization)
   * @param eventType upper-cased event type string
   * @param allocation the allocation model associated with this event
   * @param equipmentOpt optional EquipmentItem model
   * @param assigneeDesc pre-computed assignee description string
   * @param allocatedByDesc pre-computed allocatedBy description string
   * @return tuple (subject, htmlBody, plainBody)
   */
  private def makeMessageFor(
                              role: String,
                              recipientUserOpt: Option[User],
                              eventType: String,
                              allocation: EquipmentAllocation,
                              equipmentOpt: Option[EquipmentItem],
                              assigneeDesc: String,
                              allocatedByDesc: String
                            ): (String, String, String) = {

    val actorWhen = fmtInstant(Instant.now())
    val allocId = allocation.allocationId
    val equipmentDesc = equipmentOpt.map(e => s"${e.assetTag} / SN:${e.serialNumber} (id=${e.equipmentId})").getOrElse(s"(equipment id=${allocation.equipmentId})")
    val purpose = Option(allocation.purpose).filter(_.nonEmpty).getOrElse("N/A")
    val expectedReturn = fmtOptInstant(Option(allocation.expectedReturnAt))
    val returnedAt = fmtOptInstant(allocation.returnedAt)
    val status = Option(allocation.status).getOrElse("N/A")
    val allocatedAtStr = fmtNullableInstant(allocation.allocatedAt)

    def basePlainHeader: Seq[String] = Seq(
      s"EventType : $eventType",
      s"Allocation : $allocId",
      s"Equipment  : $equipmentDesc"
    )

    def baseHtmlHeader: String =
      s"""
         |<p><strong>Event:</strong> ${escapeHtml(eventType)}</p>
         |<p><strong>Allocation:</strong> ${allocId}</p>
         |<p><strong>Equipment:</strong> ${escapeHtml(equipmentDesc)}</p>
       """.stripMargin

    (role.toLowerCase, eventType.toUpperCase) match {
      // ASSIGNEE: assigned
      case ("assignee", "CREATE") | ("assignee", "ALLOCATION") =>
        val subject = s"[Allocation Assigned] Allocation #$allocId - ${equipmentOpt.map(_.assetTag).getOrElse("")}"
        val plain = (basePlainHeader ++ Seq(
          s"Action    : You have been assigned this equipment",
          s"Purpose   : $purpose",
          s"AllocatedAt: $allocatedAtStr",
          s"ExpectedReturnAt: $expectedReturn",
          s"Status    : $status",
          "",
          "If you have any questions contact the Inventory team."
        )).mkString("\n")

        val html =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Equipment Assigned to You</h3>
             |  ${baseHtmlHeader}
             |  <p><strong>Action:</strong> You have been assigned this equipment</p>
             |  <p><strong>Purpose:</strong> ${escapeHtml(purpose)}</p>
             |  <p><strong>Allocated At:</strong> ${escapeHtml(allocatedAtStr)}</p>
             |  <p><strong>Expected Return:</strong> ${escapeHtml(expectedReturn)}</p>
             |  <p><strong>Status:</strong> ${escapeHtml(status)}</p>
             |  <p style="font-size:smaller;color:#666;">SentAt: ${escapeHtml(actorWhen)}</p>
             |</body></html>""".stripMargin

        (subject, html, plain)

      // ASSIGNEE: returned
      case ("assignee", "RETURN") =>
        val subject = s"[Allocation Returned] Allocation #$allocId"
        val plain = (basePlainHeader ++ Seq(
          s"Action    : Equipment returned",
          s"ReturnedAt: $returnedAt",
          s"Status    : $status",
          s"Notes     : ${allocation.returnNotes.getOrElse("N/A")}"
        )).mkString("\n")

        val html =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Equipment Return Notice</h3>
             |  ${baseHtmlHeader}
             |  <p><strong>Returned At:</strong> ${escapeHtml(returnedAt)}</p>
             |  <p><strong>Status:</strong> ${escapeHtml(status)}</p>
             |  <p><strong>Notes:</strong> ${escapeHtml(allocation.returnNotes.getOrElse("N/A"))}</p>
             |</body></html>""".stripMargin

        (subject, html, plain)

      // ALLOCATEDBY: creation
      case ("allocatedby", "CREATE") | ("allocatedby", "ALLOCATION") =>
        val subject = s"[Allocation Created] Allocation #$allocId"
        val plain = (basePlainHeader ++ Seq(
          s"Action    : Allocation created",
          s"Assignee  : $assigneeDesc",
          s"Purpose   : $purpose",
          s"AllocatedAt: $allocatedAtStr",
          s"ExpectedReturnAt: $expectedReturn",
          s"Status    : $status"
        )).mkString("\n")

        val html =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Allocation Created</h3>
             |  ${baseHtmlHeader}
             |  <p><strong>Assignee:</strong> ${escapeHtml(assigneeDesc)}</p>
             |  <p><strong>Purpose:</strong> ${escapeHtml(purpose)}</p>
             |  <p><strong>Allocated At:</strong> ${escapeHtml(allocatedAtStr)}</p>
             |  <p><strong>Expected Return:</strong> ${escapeHtml(expectedReturn)}</p>
             |  <p><strong>Status:</strong> ${escapeHtml(status)}</p>
             |</body></html>""".stripMargin

        (subject, html, plain)

      // ALLOCATEDBY: return
      case ("allocatedby", "RETURN") =>
        val subject = s"[Allocation Returned] Allocation #$allocId"
        val plain = (basePlainHeader ++ Seq(
          s"Action    : Allocation returned",
          s"Assignee  : $assigneeDesc",
          s"ReturnedAt: $returnedAt",
          s"Condition : ${allocation.conditionOnReturn.getOrElse("N/A")}",
          s"Notes     : ${allocation.returnNotes.getOrElse("N/A")}",
          s"Status    : $status"
        )).mkString("\n")

        val html =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Allocation Returned</h3>
             |  ${baseHtmlHeader}
             |  <p><strong>Assignee:</strong> ${escapeHtml(assigneeDesc)}</p>
             |  <p><strong>Returned At:</strong> ${escapeHtml(returnedAt)}</p>
             |  <p><strong>Condition:</strong> ${escapeHtml(allocation.conditionOnReturn.getOrElse("N/A"))}</p>
             |  <p><strong>Notes:</strong> ${escapeHtml(allocation.returnNotes.getOrElse("N/A"))}</p>
             |</body></html>""".stripMargin

        (subject, html, plain)

      // MAINTENANCE alerts (admins)
      case ("admin", "MAINTENANCE") | ("admin", "MAINTENANCE_ALERT") | ("admin", "DAMAGED") =>
        val subject = s"[Maintenance Alert] Allocation #$allocId"
        val plain = (basePlainHeader ++ Seq(
          s"Action    : Maintenance alert",
          s"Assignee  : $assigneeDesc",
          s"AllocatedBy: $allocatedByDesc",
          s"Purpose   : $purpose",
          s"Status    : $status",
          s"Notes     : ${allocation.returnNotes.getOrElse("N/A")}"
        )).mkString("\n")

        val html =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Maintenance Alert</h3>
             |  ${baseHtmlHeader}
             |  <p><strong>Assignee:</strong> ${escapeHtml(assigneeDesc)}</p>
             |  <p><strong>Allocated By:</strong> ${escapeHtml(allocatedByDesc)}</p>
             |  <p><strong>Purpose:</strong> ${escapeHtml(purpose)}</p>
             |  <p><strong>Notes:</strong> ${escapeHtml(allocation.returnNotes.getOrElse("N/A"))}</p>
             |</body></html>""".stripMargin

        (subject, html, plain)

      // OVERDUE/REMINDER
      case (_, "OVERDUE_REMINDER") | (_, "OVERDUE") | (_, "REMINDER") =>
        val subject = s"[Reminder] Allocation #$allocId is overdue"
        val plain = (basePlainHeader ++ Seq(
          s"Action    : Overdue reminder",
          s"Assignee  : $assigneeDesc",
          s"ExpectedReturnAt: $expectedReturn",
          s"Status    : $status",
          "",
          "Please return or update the expected return date."
        )).mkString("\n")

        val html =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Overdue Allocation Reminder</h3>
             |  ${baseHtmlHeader}
             |  <p><strong>Assignee:</strong> ${escapeHtml(assigneeDesc)}</p>
             |  <p><strong>Expected Return:</strong> ${escapeHtml(expectedReturn)}</p>
             |  <p><strong>Status:</strong> ${escapeHtml(status)}</p>
             |  <p>Please return the item or contact the Inventory team to extend the due date.</p>
             |</body></html>""".stripMargin

        (subject, html, plain)

      // fallback generic notification (no payload)
      case _ =>
        val subject = s"[Allocation Notification] Allocation #$allocId"
        val plain = (basePlainHeader ++ Seq(
          s"Action    : $eventType",
          s"Assignee  : $assigneeDesc",
          s"AllocatedBy: $allocatedByDesc",
          s"Purpose   : $purpose",
          s"ExpectedReturnAt: $expectedReturn",
          s"ReturnedAt: $returnedAt",
          s"Status    : $status"
        )).mkString("\n")

        val html =
          s"""<html><body style="font-family:sans-serif;">
             |  <h3>Allocation Notification</h3>
             |  ${baseHtmlHeader}
             |  <p><strong>Assignee:</strong> ${escapeHtml(assigneeDesc)}</p>
             |  <p><strong>Allocated By:</strong> ${escapeHtml(allocatedByDesc)}</p>
             |  <p><strong>Purpose:</strong> ${escapeHtml(purpose)}</p>
             |</body></html>""".stripMargin

        (subject, html, plain)
    }
  }

  /**
   * Extract an integer from a JSON object field that might be a number or a string.
   *
   * Examples supported:
   *  - { "allocationId": 123 }
   *  - { "allocationId": "123" }
   *
   * @param js JSON object
   * @param field name of the field to extract
   * @return Option[Int] parsed value or None if missing/invalid
   */
  private def getInt(js: JsObject, field: String): Option[Int] =
    (js \ field).asOpt[JsValue].flatMap {
      case JsNumber(n) => Try(n.toIntExact).toOption
      case JsString(s) => Try(s.trim.toInt).toOption
      case _ => None
    }

  /**
   * Safely stringify JSON (truncated to `max` characters) for logging.
   *
   * @param js JSON value to stringify
   * @param max maximum characters to include (default 1024)
   * @return compact string representation (possibly truncated)
   */
  private def safePretty(js: JsValue, max: Int = 1024): String = {
    val s = Json.stringify(js)
    if (s.length <= max) s else s.take(max) + "...(truncated)"
  }

  /**
   * Minimal HTML-escaping for a string used in email HTML bodies.
   *
   * Purpose: avoid breaking HTML when inserting arbitrary text.
   *
   * @param s raw string
   * @return escaped string safe to inject into simple HTML templates
   */
  private def escapeHtml(s: String): String =
    Option(s).getOrElse("")
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
}
