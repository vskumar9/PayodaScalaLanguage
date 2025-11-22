package actors

import akka.actor.{Actor, ActorLogging, Props}
import play.api.libs.json._
import scala.concurrent.Future
import scala.util.Try
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import repositories.{EventsRepository, UsersRepository}
import models.{EquipmentEvent, Event, User}
import mail.Mailer

/**
 * Actor factory and messages for event handling pipeline.
 *
 * The EventActor subscribes to incoming equipment/event notification payloads
 * and performs DB lookups, email composition, and delivery for common event
 * lifecycle actions (CREATE, UPDATE, DELETE, RESTORED).
 */
object EventActor {
  /**
   * Create Props for an EventActor.
   *
   * @param eventsRepo  repository used to fetch event records (may include deleted rows)
   * @param usersRepo   repository used to lookup user (manager/actor/admin) details
   * @param emailService mailer service used to send HTML/plain emails
   * @return Props configured for this actor
   */
  def props(eventsRepo: EventsRepository, usersRepo: UsersRepository, emailService: Mailer): Props =
    Props(new EventActor(eventsRepo, usersRepo, emailService))

  /**
   * Message used to deliver an incoming equipment / notification event payload to the actor.
   *
   * @param ev the incoming event envelope (expected to contain a JSON `payload` with an "eventType" field)
   */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor that processes equipment/event messages and sends notification emails.
 *
 * Responsibilities:
 *  - Inspect the incoming JSON payload and determine the logical action (CREATE, UPDATE, DELETE, RESTORED).
 *  - Lookup the Event row from the DB (using EventsRepository), including rows marked deleted when necessary.
 *  - Lookup user records (manager / actor / admins) from UsersRepository for addressing emails.
 *  - Compose both HTML and plain-text email bodies with event details and role-specific greetings.
 *  - Attempt to send an HTML email first; on failure, fall back to plain-text email.
 *  - Log useful diagnostics and errors (including truncated payload snippets).
 *
 * Notes:
 *  - This actor is side-effecting: it issues DB reads and makes calls to an injected mailer service.
 *  - Date/time formatting uses the Asia/Kolkata timezone and a human-readable pattern.
 *
 * @param eventsRepo   repository for event lookup (used with findByIdIncludeDeleted)
 * @param usersRepo    repository for user lookup (manager, actor, admins)
 * @param emailService service used for sending HTML/plain emails (mail.Mailer)
 */
class EventActor(eventsRepo: EventsRepository, usersRepo: UsersRepository, emailService: Mailer)
  extends Actor with ActorLogging {

  import EventActor._
  import context.dispatcher

  // -- Configuration for date/time display ------------------------------------------------
  /** Time zone used to present datetimes in emails / logs. */
  private val displayZone: ZoneId = ZoneId.of("Asia/Kolkata")

  /** Human-friendly formatter used for instants in email bodies. */
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-LL-dd HH:mm:ss z").withZone(displayZone)

  /** Format an Instant for display. */
  private def fmtInstant(i: Instant): String = displayFmt.format(i)

  /** Format an optional Instant or show "N/A" when absent. */
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")

  // -- Actor message handling -------------------------------------------------------------
  override def receive: Receive = {
    case HandleEvent(ev) =>
      val p = ev.payload
      val payloadEventType = (p \ "eventType").asOpt[String].map(_.trim.toUpperCase)

      payloadEventType match {
        case Some("CREATE")  =>
          log.info(s"[EventActor] Received CREATE payload: ${safe(p)}")
          handleCreate(p)

        case Some("UPDATE")  =>
          log.info(s"[EventActor] Received UPDATE payload: ${safe(p)}")
          handleUpdate(p)

        case Some("DELETE")  =>
          log.info(s"[EventActor] Received DELETE payload: ${safe(p)}")
          handleDelete(p)

        case Some("RESTORED") =>
          log.info(s"[EventActor] Received RESTORED payload: ${safe(p)}")
          handleRestored(p)

        case Some(other) =>
          log.warning(s"[EventActor] Unhandled payload eventType=$other payload=${safe(p)}")

        case None =>
          log.warning(s"[EventActor] Missing eventType inside payload: ${safe(p)}")
      }

    case other =>
      log.debug(s"[EventActor] Ignoring unsupported message: $other")
  }

  // -- Handlers for each logical payload action -------------------------------------------

  /**
   * Handle CREATE payloads:
   *  - expects an `eventId` field in the payload
   *  - fetches the event row (including deleted) and composes/sends emails
   *
   * Missing eventId or DB lookup failures are logged.
   */
  private def handleCreate(payload: JsValue): Unit = {
    val idOpt = (payload \ "eventId").asOpt[Int]
    idOpt match {
      case Some(id) =>
        eventsRepo.findByIdIncludeDeleted(id).flatMap {
          case Some(eventRow) =>
            composeAndSendEmailForEvent("CREATE", eventRow, payload)
          case None =>
            Future.successful {
              log.warning(s"[EventActor] CREATE: event id=$id not found in DB; payload=${safe(payload)}")
            }
        }.recover { case ex =>
          log.error(ex, s"[EventActor] Error fetching event for CREATE id=$id")
        }

      case None =>
        log.warning(s"[EventActor] CREATE payload missing eventId: ${safe(payload)}")
    }
  }

  /**
   * Handle UPDATE payloads:
   *  - attempts to determine the acting user from payload.updatedBy or falls back to event.createdBy
   *  - fetches the event row and triggers email composition
   */
  private def handleUpdate(payload: JsValue): Unit = {
    val idOpt = (payload \ "eventId").asOpt[Int]
    val updatedByOpt = (payload \ "updatedBy").asOpt[Int]
    idOpt match {
      case Some(id) =>
        eventsRepo.findByIdIncludeDeleted(id).flatMap {
          case Some(eventRow) =>
            val actorUserId = updatedByOpt.orElse(Some(eventRow.createdBy))
            composeAndSendEmailForEvent("UPDATE", eventRow, payload, actorUserIdOpt = actorUserId)
          case None =>
            Future.successful(log.warning(s"[EventActor] UPDATE: event id=$id not found in DB"))
        }.recover { case ex =>
          log.error(ex, s"[EventActor] Error fetching event for UPDATE id=$id")
        }

      case None =>
        log.warning(s"[EventActor] UPDATE payload missing eventId: ${safe(payload)}")
    }
  }

  /**
   * Handle DELETE payloads:
   *  - similar to UPDATE but looks for deletedBy in the payload
   */
  private def handleDelete(payload: JsValue): Unit = {
    val idOpt = (payload \ "eventId").asOpt[Int]
    val deletedByOpt = (payload \ "deletedBy").asOpt[Int]
    idOpt match {
      case Some(id) =>
        eventsRepo.findByIdIncludeDeleted(id).flatMap {
          case Some(eventRow) =>
            val actorUserId = deletedByOpt.orElse(Some(eventRow.createdBy))
            composeAndSendEmailForEvent("DELETE", eventRow, payload, actorUserIdOpt = actorUserId)
          case None =>
            Future.successful(log.warning(s"[EventActor] DELETE: event id=$id not found in DB"))
        }.recover { case ex =>
          log.error(ex, s"[EventActor] Error fetching event for DELETE id=$id")
        }

      case None =>
        log.warning(s"[EventActor] DELETE payload missing eventId: ${safe(payload)}")
    }
  }

  /**
   * Handle RESTORED payloads:
   *  - similar to DELETE/UPDATE; looks for restoredBy in payload
   */
  private def handleRestored(payload: JsValue): Unit = {
    val idOpt = (payload \ "eventId").asOpt[Int]
    val restoredByOpt = (payload \ "restoredBy").asOpt[Int]
    idOpt match {
      case Some(id) =>
        eventsRepo.findByIdIncludeDeleted(id).flatMap {
          case Some(eventRow) =>
            val actorUserId = restoredByOpt.orElse(Some(eventRow.createdBy))
            composeAndSendEmailForEvent("RESTORED", eventRow, payload, actorUserIdOpt = actorUserId)
          case None =>
            Future.successful(log.warning(s"[EventActor] RESTORED: event id=$id not found in DB"))
        }.recover { case ex =>
          log.error(ex, s"[EventActor] Error fetching event for RESTORED id=$id")
        }

      case None =>
        log.warning(s"[EventActor] RESTORED payload missing eventId: ${safe(payload)}")
    }
  }

  // -- Email composition & sending -------------------------------------------------------

  /**
   * Compose and send emails for the given event action.
   *
   * Steps:
   *  - Lookup manager (creator) and actor user records (if available)
   *  - (Optional) lookup admins — currently returns an empty Seq but the hook is provided
   *  - Deduplicate recipients by email address
   *  - Build a role-specific subject, plain text body and HTML body
   *  - Attempt HTML send, and fall back to plain text on error
   *
   * @param action           high-level action string ("CREATE", "UPDATE", "DELETE", "RESTORED", etc.)
   * @param eventRow         Event model fetched from DB
   * @param payload          original JSON payload that triggered the action (used for additional context)
   * @param actorUserIdOpt   optional override for the user who performed the action
   * @return Future[Unit] completed when sends are attempted
   */
  private def composeAndSendEmailForEvent(
                                           action: String,
                                           eventRow: Event,
                                           payload: JsValue,
                                           actorUserIdOpt: Option[Int] = None
                                         ): Future[Unit] = {

    val managerUserId = eventRow.createdBy
    val actorUserIdFromPayload = (payload \ "createdBy").asOpt[Int]
    val actorUserId = actorUserIdOpt.orElse(actorUserIdFromPayload)

    // Lookups: manager, actor, admins
    val managerF: Future[Option[User]] = usersRepo.findByIdIncludeDeleted(managerUserId)
    val actorF: Future[Option[User]] = actorUserId match {
      case Some(uid) => usersRepo.findByIdIncludeDeleted(uid)
      case None => Future.successful(None)
    }

    // Hook to obtain admin recipients if needed; currently returns empty seq.
    val adminsF: Future[Seq[User]] =
      Future.successful(Seq.empty[User])

    for {
      managerOpt <- managerF
      actorOpt   <- actorF
      admins     <- adminsF
      _ <- {
        // Build a deduplicated recipient list: (role, userOpt, email)
        val base: Seq[(String, Option[User])] = Seq(
          ("Manager", managerOpt),
          ("Actor", actorOpt)
        )

        val adminRecipients: Seq[(String, Option[User])] = admins.map(u => ("Admin", Some(u)))

        val rawRecipients: Seq[(String, Option[User])] = base ++ adminRecipients

        // Deduplicate by email (preserve first occurrence)
        val recipientsDistinct: Seq[(String, Option[User], String)] = rawRecipients.foldLeft(Seq.empty[(String, Option[User], String)]) {
          case (acc, (role, uOpt)) =>
            val emailOpt = uOpt.flatMap(u => Option(u.email))
            emailOpt match {
              case Some(email) =>
                if (acc.exists(_._3 == email)) acc else acc :+ (role, uOpt, email)
              case None => acc
            }
        }

        if (recipientsDistinct.isEmpty) {
          log.info(s"[EventActor] No recipient emails found for event=${eventRow.eventId.getOrElse(-1)} action=$action")
          Future.successful(())
        } else {
          // Format dates for the message
          val eventDateStr = Try(fmtInstant(eventRow.eventDate)).getOrElse(eventRow.eventDate.toString)
          val createdAtStr = Try(fmtInstant(eventRow.createdAt)).getOrElse(eventRow.createdAt.toString)
          val sentAt = fmtInstant(Instant.now())

          // Build and send per-recipient emails (HTML first; fallback to plain)
          val sendFs: Seq[Future[Unit]] = recipientsDistinct.map { case (role, userOpt, email) =>
            val actorName = actorOpt.map(_.fullName).getOrElse("System")
            val subject = action match {
              case "CREATE"   => s"[Event Created] ${eventRow.title}"
              case "UPDATE"   => s"[Event Updated] ${eventRow.title}"
              case "DELETE"   => s"[Event Deleted] ${eventRow.title}"
              case "RESTORED" => s"[Event Restored] ${eventRow.title}"
              case other      => s"[Event Notification] ${eventRow.title}"
            }

            val plainLines = Seq(
              s"Action    : $action",
              s"Event ID  : ${eventRow.eventId.getOrElse(-1)}",
              s"Title     : ${eventRow.title}",
              s"Type      : ${eventRow.eventType}",
              s"Date      : $eventDateStr",
              s"Location  : ${eventRow.location.getOrElse("N/A")}",
              s"Guests    : ${eventRow.expectedGuestCount.getOrElse(0)}",
              "",
              s"Performed by: $actorName",
              s"Performed at: ${sentAt}"
            )

            val plainBody = (role match {
              case "Manager" =>
                Seq(s"Dear ${managerOpt.map(_.fullName).getOrElse("Manager")}") ++
                  plainLines ++ Seq("", "You are the owner of this event.")
              case "Actor" =>
                Seq(s"Hello ${actorOpt.map(_.fullName).getOrElse("User")}") ++
                  plainLines ++ Seq("", "This is a notification of your action.")
              case "Admin" =>
                Seq("Hello Admin") ++
                  plainLines ++ Seq("", "Admin notification.")
              case _ =>
                plainLines
            }).mkString("\n")

            val htmlBody =
              s"""
                 |<html><body style="font-family:sans-serif;">
                 |  <h3>${escapeHtml(subject)}</h3>
                 |  <p><strong>Title:</strong> ${escapeHtml(eventRow.title)}</p>
                 |  <p><strong>Type:</strong> ${escapeHtml(eventRow.eventType)}</p>
                 |  <p><strong>Date:</strong> ${escapeHtml(eventDateStr)}</p>
                 |  <p><strong>Location:</strong> ${escapeHtml(eventRow.location.getOrElse("N/A"))}</p>
                 |  <p><strong>Guests:</strong> ${eventRow.expectedGuestCount.getOrElse(0)}</p>
                 |  <p><strong>Performed by:</strong> ${escapeHtml(actorName)}</p>
                 |  <p style="font-size:smaller;color:#666;">SentAt: ${escapeHtml(sentAt)}</p>
                 |</body></html>
               """.stripMargin

            // Send HTML, fallback to plain text
            emailService.sendHtml(email, subject, htmlBody).map { _ =>
              log.info(s"[EventActor] Email sent to=$email role=$role eventId=${eventRow.eventId.getOrElse(-1)}")
            }.recoverWith { case ex =>
              log.error(ex, s"[EventActor] sendHtml failed for $email role=$role; attempting plain text send")
              emailService.send(email, subject, plainBody).map { _ =>
                log.info(s"[EventActor] Fallback plain email sent to=$email role=$role eventId=${eventRow.eventId.getOrElse(-1)}")
              }.recover { case ex2 =>
                log.error(ex2, s"[EventActor] Fallback plain send failed for $email role=$role eventId=${eventRow.eventId.getOrElse(-1)}")
              }
            }
          }

          Future.sequence(sendFs).map(_ => ())
        }
      }
    } yield ()
  }

  // -- Small helpers --------------------------------------------------------------------

  /**
   * Produce a safe, truncated JSON string for logs (prevents very large log lines).
   *
   * @param js JSON value to stringify
   * @return stringified JSON truncated to 1024 characters with a suffix when truncated
   */
  private def safe(js: JsValue): String = {
    val s = Json.stringify(js)
    if (s.length <= 1024) s else s.take(1024) + "...(truncated)"
  }

  /**
   * Minimal HTML escaping for inserted values in the generated HTML email body.
   *
   * Note: this is a simple replacement and not a full HTML-encoding library. It is
   * sufficient for basic safety but consider a proper templating/escaping solution
   * if email content becomes more complex.
   *
   * @param s raw string
   * @return HTML-escaped string (null-safe)
   */
  private def escapeHtml(s: String): String =
    Option(s).getOrElse("").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\"", "&quot;")
}
