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
 * Companion object for [[MaintenanceActor]].
 *
 * Provides a `props` factory method and the message case classes used by the actor.
 */
object MaintenanceActor {

  /**
   * Create Props for a [[MaintenanceActor]].
   *
   * @param ticketRepo   Repository for maintenance tickets (used to load ticket details).
   * @param itemRepo     Repository for equipment items (used to load equipment meta-data).
   * @param employeeRepo Repository for employee lookup (to map employee -> user).
   * @param userRepo     Repository for user accounts (used to locate admin / user emails).
   * @param emailService Mailer service used to send notification emails.
   * @return Props for creating the actor.
   */
  def props(
             ticketRepo: MaintenanceTicketRepository,
             itemRepo: EquipmentItemRepository,
             employeeRepo: EmployeeRepository,
             userRepo: UserRepository,
             emailService: Mailer
           ): Props =
    Props(new MaintenanceActor(ticketRepo, itemRepo, employeeRepo, userRepo, emailService))

  /**
   * Message accepted by the actor representing a domain equipment event.
   *
   * @param ev EquipmentEvent containing `routeKey` and JSON `payload`.
   */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor responsible for handling maintenance-related events.
 *
 * Responsibilities:
 *  - Process ticket lifecycle events (created, updated, assigned, deleted).
 *  - Process maintenance alerts / damaged equipment notifications.
 *  - Compose and send user-facing notification emails (HTML with plain-text fallback).
 *
 * Notes:
 *  - Email messages intentionally avoid embedding the full JSON payload; only friendly fields
 *    are included so emails remain readable for recipients.
 *  - The actor uses repositories to resolve related data (equipment, employee -> user mappings).
 *
 * @param ticketRepo   Repository to fetch maintenance ticket details.
 * @param itemRepo     Repository to fetch equipment item details.
 * @param employeeRepo Repository to map employee records to user accounts.
 * @param userRepo     Repository to list users (used to find admins).
 * @param emailService Mailer used to deliver HTML/plain emails.
 */
class MaintenanceActor(
                        ticketRepo: MaintenanceTicketRepository,
                        itemRepo: EquipmentItemRepository,
                        employeeRepo: EmployeeRepository,
                        userRepo: UserRepository,
                        emailService: Mailer
                      ) extends Actor with ActorLogging {

  import MaintenanceActor._
  import context.dispatcher

  // ---------- date/time formatter (human-friendly) ----------
  /**
   * ZoneId used for formatting timestamps shown in email bodies.
   * Defaulted to "Asia/Kolkata" to match application locale.
   */
  private val displayZone: ZoneId = ZoneId.of("Asia/Kolkata")

  /**
   * Human-friendly date/time formatter used for email/GIn logs.
   * Pattern: yyyy-MM-dd HH:mm:ss z
   */
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(displayZone)

  /** Format an Instant to the configured display string. */
  private def fmtInstant(i: Instant): String = displayFmt.format(i)

  /** Format an optional Instant, returning "N/A" when missing. */
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")

  /**
   * Primary message handler.
   *
   * Accepts:
   *  - `HandleEvent(ev)` where `ev.payload` is expected to be a JSON object containing an `eventType`.
   *  - Ignores other messages with a debug log.
   *
   * Behavior:
   *  - Routes ticket lifecycle event types (TICKET_CREATED, TICKET_STATUS_UPDATED, TICKET_ASSIGNED, TICKET_DELETED)
   *    to [[handleTicketEvent]].
   *  - Routes maintenance alerts (DAMAGED, MAINTENANCE_ALERT) to [[handleMaintenanceAlert]].
   *  - Logs a warning for unrecognized or missing `eventType`.
   */
  override def receive: Receive = {
    case HandleEvent(ev) =>
      val p = ev.payload
      val payloadEventType = (p \ "eventType").asOpt[String].map(_.trim.toUpperCase)
      log.info(s"[MaintenanceActor] Received eventType=$payloadEventType payload=${safePretty(p)}")

      payloadEventType match {
        case Some("TICKET_CREATED")        => handleTicketEvent("TICKET_CREATED", p).recover { case ex => log.error(ex, s"[MaintenanceActor] handleTicketEvent failed for payload=${safePretty(p)}") }
        case Some("TICKET_STATUS_UPDATED") => handleTicketEvent("TICKET_STATUS_UPDATED", p).recover { case ex => log.error(ex, s"[MaintenanceActor] handleTicketEvent failed for payload=${safePretty(p)}") }
        case Some("TICKET_ASSIGNED")       => handleTicketEvent("TICKET_ASSIGNED", p).recover { case ex => log.error(ex, s"[MaintenanceActor] handleTicketEvent failed for payload=${safePretty(p)}") }
        case Some("TICKET_DELETED")        => handleTicketEvent("TICKET_DELETED", p).recover { case ex => log.error(ex, s"[MaintenanceActor] handleTicketEvent failed for payload=${safePretty(p)}") }

        case Some("DAMAGED") | Some("MAINTENANCE_ALERT") =>
          handleMaintenanceAlert(payloadEventType.get, p).recover { case ex => log.error(ex, s"[MaintenanceActor] handleMaintenanceAlert failed for payload=${safePretty(p)}") }

        case Some(other) =>
          log.warning(s"[MaintenanceActor] Unhandled type=$other payload=${safePretty(p)}")

        case None =>
          log.warning(s"[MaintenanceActor] Missing eventType inside payload=${safePretty(p)}")
      }

    case other =>
      log.debug(s"[MaintenanceActor] Ignoring unknown message: $other")
  }

  /**
   * Handle ticket lifecycle events.
   *
   * Steps:
   *  - Resolve `ticketId` from the payload (accepts `ticketId` or `id` fields).
   *  - Load ticket from `ticketRepo`. If missing, log and return.
   *  - Load associated equipment, creator user, assigned user (if any), and admin users.
   *  - Build a deduplicated recipient list preserving ordering: creator, assigned, then admins.
   *  - Compose subject, plain-text and HTML bodies with friendly fields (no full JSON).
   *  - Attempt to send HTML email to each recipient; on failure, fall back to plain-text.
   *
   * Returns a `Future[Unit]` that completes when sends have finished (or immediately if no-op).
   *
   * @param eventType A canonical event type string (e.g. "TICKET_CREATED").
   * @param payload   JSON payload containing ticket identifiers and optional extras.
   */
  private def handleTicketEvent(eventType: String, payload: JsObject): Future[Unit] = {
    val ticketIdOpt = getInt(payload, "ticketId").orElse(getInt(payload, "id"))

    ticketIdOpt match {
      case None =>
        log.warning(s"[MaintenanceActor] Missing ticketId in payload=${safePretty(payload)}")
        Future.successful(())

      case Some(tid) =>
        ticketRepo.findById(tid).flatMap {
          case None =>
            log.warning(s"[MaintenanceActor] ticketId=$tid not found payload=${safePretty(payload)}")
            Future.successful(())

          case Some(ticket) =>
            val equipF = itemRepo.findById(ticket.equipmentId)

            val createdByUserF: Future[Option[User]] =
              employeeRepo.findById(ticket.createdByEmployeeId).flatMap {
                case Some(emp) => userRepo.findById(emp.userId)
                case None => Future.successful(None)
              }

            val assignedUserF: Future[Option[User]] = ticket.assignedToEmployeeId match {
              case Some(empId) =>
                employeeRepo.findById(empId).flatMap {
                  case Some(emp) => userRepo.findById(emp.userId)
                  case None => Future.successful(None)
                }
              case None => Future.successful(None)
            }

            val adminsF: Future[Seq[User]] = userRepo.findAll().map(_.filter(u => u.roles.exists(_.equalsIgnoreCase("admin"))))

            for {
              equipmentOpt     <- equipF
              creatorUserOpt   <- createdByUserF
              assignedUserOpt  <- assignedUserF
              admins           <- adminsF
              result           <- {
                // build recipients preserving order: creator, assigned, admins; dedupe by email (first-seen)
                val rawRecipients: Seq[Option[String]] = Seq(
                  creatorUserOpt.flatMap(u => Option(u.email)),
                  assignedUserOpt.flatMap(u => Option(u.email))
                ) ++ admins.map(a => Option(a.email))

                val recipientsDistinct: Seq[String] = rawRecipients.flatten.foldLeft(Seq.empty[String]) {
                  case (acc, email) => if (acc.contains(email)) acc else acc :+ email
                }

                if (recipientsDistinct.isEmpty) {
                  log.info(s"[MaintenanceActor] No recipients for ticket=$tid event=$eventType")
                  Future.successful(())
                } else {
                  val subject = eventType match {
                    case "TICKET_CREATED"        => s"[Maintenance] Ticket Created #$tid"
                    case "TICKET_STATUS_UPDATED" => s"[Maintenance] Ticket Updated #$tid"
                    case "TICKET_ASSIGNED"       => s"[Maintenance] Ticket Assigned #$tid"
                    case "TICKET_DELETED"        => s"[Maintenance] Ticket Deleted #$tid"
                    case _                       => s"[Maintenance] Ticket Notification #$tid"
                  }

                  // friendly strings
                  val equipmentDesc = equipmentOpt.map(e => s"${e.assetTag} / ${e.serialNumber} (id=${e.equipmentId})").getOrElse("Unknown equipment")
                  val assignedToStr = ticket.assignedToEmployeeId.map(_.toString).getOrElse("Unassigned")
                  val generatedAt = fmtInstant(Instant.now())

                  val plainBody =
                    Seq(
                      s"EventType   : $eventType",
                      s"TicketId    : ${ticket.ticketId}",
                      s"Equipment   : $equipmentDesc",
                      s"Status      : ${ticket.status}",
                      s"Severity    : ${ticket.severity}",
                      s"IssueNotes  : ${ticket.issueNotes}",
                      s"AssignedTo  : $assignedToStr",
                      s"GeneratedAt : $generatedAt"
                    ).mkString("\n")

                  val htmlBody =
                    s"""
                       |<html><body style="font-family:sans-serif;">
                       |  <h3>Maintenance Ticket Notification</h3>
                       |  <p><strong>Event:</strong> ${escapeHtml(eventType)}</p>
                       |  <p><strong>TicketId:</strong> ${ticket.ticketId}</p>
                       |  <p><strong>Equipment:</strong> ${escapeHtml(equipmentDesc)}</p>
                       |  <p><strong>Status:</strong> ${escapeHtml(ticket.status)}</p>
                       |  <p><strong>Severity:</strong> ${escapeHtml(ticket.severity)}</p>
                       |  <p><strong>AssignedTo:</strong> ${escapeHtml(assignedToStr)}</p>
                       |  <h4>Issue Notes</h4>
                       |  <pre style="background:#f5f5f5;padding:10px;border-radius:4px;">${escapeHtml(ticket.issueNotes)}</pre>
                       |  <p style="font-size:smaller;color:#666;">GeneratedAt: ${escapeHtml(generatedAt)}</p>
                       |</body></html>
                     """.stripMargin

                  log.info(s"[MaintenanceActor] Prepared Mail -> to=[${recipientsDistinct.mkString(", ")}] subject='$subject' bodyPreview='${plainBody.take(512)}'")

                  val sendFs: Seq[Future[Unit]] = recipientsDistinct.map { email =>
                    // try HTML, fallback to plain
                    emailService.sendHtml(email, subject, htmlBody).map { _ =>
                      log.info(s"[MaintenanceActor] Email sent -> $email")
                    }.recoverWith { case ex =>
                      log.error(ex, s"[MaintenanceActor] HTML send failed -> $email; trying plain")
                      emailService.send(email, subject, plainBody).map { _ =>
                        log.info(s"[MaintenanceActor] Plain email sent -> $email")
                      }.recover { case ex2 =>
                        log.error(ex2, s"[MaintenanceActor] Plain send failed -> $email")
                      }
                    }
                  }

                  Future.sequence(sendFs).map(_ => ())
                }
              }
            } yield result
        }
    }
  }

  /**
   * Handle maintenance alerts and damaged equipment notifications.
   *
   * Steps:
   *  - Resolve `equipmentId` from payload.
   *  - Load equipment details and admin users.
   *  - Compose friendly subject and body (HTML + plain fallback).
   *  - Send notifications to all admin emails.
   *
   * Returns a `Future[Unit]`.
   *
   * @param eventType Event type string ("DAMAGED" or "MAINTENANCE_ALERT" expected).
   * @param payload   JSON payload expected to contain `equipmentId`.
   */
  private def handleMaintenanceAlert(eventType: String, payload: JsObject): Future[Unit] = {
    val equipmentIdOpt = getInt(payload, "equipmentId")

    equipmentIdOpt match {
      case None =>
        log.warning(s"[MaintenanceActor] Alert missing equipmentId payload=${safePretty(payload)}")
        Future.successful(())

      case Some(eid) =>
        val equipF = itemRepo.findById(eid)
        val adminsF = userRepo.findAll().map(_.filter(_.roles.exists(_.equalsIgnoreCase("admin"))))

        for {
          equipOpt <- equipF
          admins   <- adminsF
          result   <- {
            val adminEmails = admins.flatMap(a => Option(a.email)).distinct

            if (adminEmails.isEmpty) {
              log.info(s"[MaintenanceActor] No admin recipients for maintenance alert equipment=$eid")
              Future.successful(())
            } else {
              val subject = eventType match {
                case "DAMAGED"           => s"[Maintenance] Equipment Damaged (id=$eid)"
                case "MAINTENANCE_ALERT" => s"[Maintenance] High Severity Maintenance Alert (id=$eid)"
                case _                   => s"[Maintenance] Alert for equipment=$eid"
              }

              val equipmentDesc = equipOpt.map(i => s"${i.assetTag} / ${i.serialNumber} (id=${i.equipmentId})").getOrElse("Unknown equipment")
              val generatedAt = fmtInstant(Instant.now())

              val plainBody =
                Seq(
                  s"EventType   : $eventType",
                  s"EquipmentId : $eid",
                  s"Equipment   : $equipmentDesc",
                  s"GeneratedAt : $generatedAt"
                ).mkString("\n")

              val htmlBody =
                s"""
                   |<html><body style="font-family:sans-serif;">
                   |  <h3>Maintenance Alert</h3>
                   |  <p><strong>Event:</strong> ${escapeHtml(eventType)}</p>
                   |  <p><strong>Equipment:</strong> ${escapeHtml(equipmentDesc)}</p>
                   |  <p style="font-size:smaller;color:#666;">GeneratedAt: ${escapeHtml(generatedAt)}</p>
                   |</body></html>
                 """.stripMargin

              log.info(s"[MaintenanceActor] Prepared Maintenance Alert -> to=[${adminEmails.mkString(", ")}] subject='$subject' bodyPreview='${plainBody.take(512)}'")

              val sends: Seq[Future[Unit]] = adminEmails.map { email =>
                emailService.sendHtml(email, subject, htmlBody).map { _ =>
                  log.info(s"[MaintenanceActor] Email sent -> $email")
                }.recoverWith { case ex =>
                  log.error(ex, s"[MaintenanceActor] HTML send failed -> $email; trying plain")
                  emailService.send(email, subject, plainBody).recover { case ex2 =>
                    log.error(ex2, s"[MaintenanceActor] Plain send failed -> $email")
                  }
                }
              }

              Future.sequence(sends).map(_ => ())
            }
          }
        } yield result
    }
  }

  // -------------------------
  // Helpers
  // -------------------------
  /**
   * Safely parse an integer field from a JsObject.
   *
   * Accepts numeric JSON values or numeric strings.
   *
   * @param js    JSON object.
   * @param field Field name to extract.
   * @return Some(int) when value present and parseable, otherwise None.
   */
  private def getInt(js: JsObject, field: String): Option[Int] =
    (js \ field).asOpt[JsValue].flatMap {
      case JsNumber(n) => Try(n.toIntExact).toOption
      case JsString(s) => Try(s.trim.toInt).toOption
      case _ => None
    }

  /**
   * Produce a compact single-line JSON string for logging, truncated to `maxLen`.
   *
   * @param js     JSON value to stringify.
   * @param maxLen Maximum length to return (default 1024). Longer content is truncated with "... (truncated)".
   * @return String representation safe for logs.
   */
  private def safePretty(js: JsValue, maxLen: Int = 1024): String = {
    val s = Json.stringify(js)
    if (s.length <= maxLen) s else s.take(maxLen) + "... (truncated)"
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
    Option(s).getOrElse("").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
