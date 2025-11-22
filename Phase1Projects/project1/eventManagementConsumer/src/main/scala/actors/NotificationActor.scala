package actors

import akka.actor.{Actor, ActorLogging, Props}
import play.api.libs.json._
import scala.concurrent.Future
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import repositories.{NotificationsRepository, UsersRepository, TeamsRepository}
import models.{EquipmentEvent, Notification}
import mail.Mailer

/**
 * Factory and protocol for the NotificationActor.
 *
 * The actor processes notification-related equipment events delivered from Kafka
 * (wrapped in [[models.EquipmentEvent]]). It understands inner event types such as
 * `NOTIFICATION`, `REMINDER`, and `OVERDUE` and is responsible for:
 *
 *  - resolving the intended recipient email (user → team → default),
 *  - composing a simple HTML/plain message payload,
 *  - attempting delivery via an injected [[mail.Mailer]] (HTML first, then plain text fallback),
 *  - updating the notification persistence state (markSent / incrementAttempts) via [[NotificationsRepository]],
 *  - applying retry logic when delivery fails.
 *
 * The actor is intentionally defensive: it logs and recovers from parsing,
 * DB lookup, and mailer errors so the actor loop remains healthy.
 */
object NotificationActor {
  /**
   * Create Props for a NotificationActor.
   *
   * @param notificationsRepo repository used to load and update notification rows
   * @param usersRepo         repository used to lookup user e-mail addresses
   * @param teamsRepo         repository used to lookup team contact e-mails
   * @param emailService      mailer service used to send HTML/plain emails
   * @param maxAttempts       maximum delivery attempts used when incrementing retries
   * @return Props configured with the provided dependencies
   */
  def props(
             notificationsRepo: NotificationsRepository,
             usersRepo: UsersRepository,
             teamsRepo: TeamsRepository,
             emailService: Mailer,
             maxAttempts: Int = 3
           ): Props =
    Props(new NotificationActor(notificationsRepo, usersRepo, teamsRepo, emailService, maxAttempts))

  /** Message used to hand an incoming equipment event to the actor. */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor that delivers notifications and updates notification state.
 *
 * Detailed behavior:
 *  - Accepts [[HandleEvent]] messages containing an [[EquipmentEvent]] whose payload
 *    contains a JSON `eventType` (e.g., NOTIFICATION, REMINDER, OVERDUE).
 *  - For recognized inner event types, it calls [[processPayload]] which:
 *      * optionally reads a `notificationId` from the payload and loads the DB row,
 *      * resolves a recipient e-mail (user -> team -> default),
 *      * composes an HTML and plain text body,
 *      * sends the HTML email via `emailService.sendHtml`,
 *      * on success: marks notification sent (if an id was present),
 *      * on failure: increments attempts on the notification (if an id was present),
 *      * logs all important steps and errors for observability.
 *  - The actor formats timestamps using Asia/Kolkata timezone for human-friendly messages.
 *
 * Concurrency and errors:
 *  - All DB and mail operations are performed asynchronously (Futures).
 *  - Failures are logged and translated into notifications repository updates where applicable.
 *
 * @param notificationsRepo repository for reading/updating notifications
 * @param usersRepo         repository for resolving user emails
 * @param teamsRepo         repository for resolving team contact emails
 * @param emailService      mailer used for HTML/plain sends
 * @param maxAttempts       maximum retry attempts forwarded to notificationsRepo.incrementAttempts
 */
class NotificationActor(
                         notificationsRepo: NotificationsRepository,
                         usersRepo: UsersRepository,
                         teamsRepo: TeamsRepository,
                         emailService: Mailer,
                         maxAttempts: Int
                       ) extends Actor with ActorLogging {

  import NotificationActor._
  import context.dispatcher

  // Timezone and formatter used for email timestamps
  private val displayZone: ZoneId = ZoneId.of("Asia/Kolkata")
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-LL-dd HH:mm:ss z").withZone(displayZone)

  private def fmtInstant(i: Instant): String = displayFmt.format(i)
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")

  /**
   * Primary receive loop. Inspects the payload's `eventType` (inner event) and
   * delegates to [[processPayload]] for recognized notification types.
   *
   * Logging is performed for received payloads and unknown event types are ignored with a warning.
   */
  override def receive: Receive = {
    case HandleEvent(ev) =>
      val p = ev.payload
      val inner = (p \ "eventType").asOpt[String].map(_.trim.toUpperCase()).getOrElse("NOTIFICATION")
      log.info(s"[NotificationActor] Received innerEvent=$inner payload=${safe(p)}")

      inner match {
        case "NOTIFICATION" | "REMINDER" | "OVERDUE" =>
          // process asynchronously; log any processing failure
          processPayload(p).recover {
            case ex => log.error(ex, s"[NotificationActor] Failed to process payload: ${safe(p)}")
          }

        case other =>
          log.warning(s"[NotificationActor] Unknown innerEvent=$other payload=${safe(p)}")
      }

    case other =>
      log.debug(s"[NotificationActor] Ignoring unsupported message: $other")
  }

  /**
   * Core processing routine for notification payloads.
   *
   * Accepted payload shapes:
   *  - { "notificationId": <int>, ... } -> will load DB row and use its metadata
   *  - otherwise recipientUserId/recipientTeamId may be read from the payload
   *
   * Steps taken:
   *  1. Resolve notificationId (if present) and load DB row.
   *  2. Resolve recipient e-mail via [[recipientEmailFor]].
   *  3. Compose subject and body (HTML wrapper and plain body).
   *  4. Send email (HTML), on success markSent, on fail incrementAttempts.
   *
   * @param payload JSON payload from the event
   * @return Future[Unit] completing when processing (and any DB updates) finishes
   */
  private def processPayload(payload: JsValue): Future[Unit] = {
    val notifIdOpt = (payload \ "notificationId").asOpt[Int]
    val msgFromPayload = (payload \ "message").asOpt[String]
    val sentAt = fmtInstant(Instant.now())

    /**
     * Helper that attempts delivery and updates DB state accordingly.
     *
     * - Attempts HTML send first.
     * - On success: if notificationId present => notificationsRepo.markSent(id)
     * - On failure: if notificationId present => notificationsRepo.incrementAttempts(id, maxAttempts)
     *
     * @param notificationOpt optional Notification loaded from DB (may be None for ad-hoc sends)
     * @param toEmail         resolved recipient email
     * @param subject         email subject
     * @param plainBody       plain text body
     */
    def deliverAndMark(notificationOpt: Option[Notification], toEmail: String, subject: String, plainBody: String): Future[Unit] = {
      val htmlBody = s"<pre style=\"font-family:sans-serif;background:#f5f5f5;padding:10px;border-radius:4px;\">${escapeHtml(plainBody)}</pre>"

      log.info(s"[NotificationActor] Delivering -> to=$toEmail subj='$subject' bodyPreview='${plainBody.take(512)}'")

      emailService.sendHtml(toEmail, subject, htmlBody).flatMap { _ =>
        // On success, mark the DB notification as sent (if applicable)
        notificationOpt.flatMap(_.notificationId) match {
          case Some(id) =>
            notificationsRepo.markSent(id).map(_ => log.info(s"[NotificationActor] Marked sent notificationId=$id"))
          case None =>
            Future.successful(log.info(s"[NotificationActor] Delivered (no id present)"))
        }
      }.recoverWith { case ex =>
        // On send failure, increment attempts (if applicable) so retries/backoff can occur elsewhere
        log.error(ex, s"[NotificationActor] Error delivering to $toEmail")
        notificationOpt.flatMap(_.notificationId) match {
          case Some(id) =>
            notificationsRepo.incrementAttempts(id, maxAttempts).map(_ => log.warning(s"[NotificationActor] Incremented attempts for id=$id"))
          case None =>
            Future.successful(log.warning(s"[NotificationActor] Delivery failed and no notificationId present to update"))
        }
      }.map(_ => ())
    }

    // Main branching: if notificationId is present, try to load DB row and use it;
    // otherwise fall back to using recipient IDs from payload or default addresses.
    notifIdOpt match {
      case Some(nid) =>
        notificationsRepo.findById(nid).flatMap {
          case Some(dbNotif) =>
            val recipientUser = dbNotif.recipientUserId
            val recipientTeam = dbNotif.recipientTeamId

            recipientEmailFor(recipientUser, recipientTeam).flatMap { email =>
              val notifType = Option(dbNotif.notificationType).filter(_.nonEmpty).getOrElse("NOTIFICATION")
              val subj = s"[$notifType] Notification ${dbNotif.notificationId.getOrElse(nid)}"

              val body =
                s"${dbNotif.message.orElse(msgFromPayload).getOrElse("(no message)")} \n\nSentAt: $sentAt"

              deliverAndMark(Some(dbNotif), email, subj, body)
            }

          case None =>
            // DB row not found; attempt ad-hoc delivery using payload recipient info
            val recipientUser = (payload \ "recipientUserId").asOpt[Int]
            val recipientTeam = (payload \ "recipientTeamId").asOpt[Int]
            recipientEmailFor(recipientUser, recipientTeam).flatMap { email =>
              val subj = s"[${(payload \ "notificationType").asOpt[String].getOrElse("NOTIFICATION")}] (no-id)"
              val body = s"${msgFromPayload.getOrElse("(no message)")} \n\nSentAt: $sentAt"
              deliverAndMark(None, email, subj, body)
            }
        }

      case None =>
        // no id present -> ad-hoc send using recipientUserId / recipientTeamId fields
        val recipientUser = (payload \ "recipientUserId").asOpt[Int]
        val recipientTeam = (payload \ "recipientTeamId").asOpt[Int]
        recipientEmailFor(recipientUser, recipientTeam).flatMap { email =>
          val subj = s"[${(payload \ "notificationType").asOpt[String].getOrElse("NOTIFICATION")}] (ad-hoc)"
          val body = s"${msgFromPayload.getOrElse("(no message)")} \n\nSentAt: $sentAt"
          deliverAndMark(None, email, subj, body)
        }
    }
  }

  /**
   * Resolve an e-mail address for a recipient.
   *
   * Resolution order:
   *  1. If `userIdOpt` present -> lookup user (include deleted) and return its email if available
   *  2. Else if `teamIdOpt` present -> lookup team (active) and return its contactEmail if available
   *  3. Else -> fall back to sensible default address: "no-reply@example.com" or "team-<id>@example.com"
   *
   * @param userIdOpt optional user id to resolve
   * @param teamIdOpt optional team id to resolve when user not provided
   * @return Future[String] resolved email address
   */
  private def recipientEmailFor(userIdOpt: Option[Int], teamIdOpt: Option[Int]): Future[String] = {
    userIdOpt match {
      case Some(uid) =>
        usersRepo.findByIdIncludeDeleted(uid).map {
          case Some(u) => Option(u.email).getOrElse(fallbackTeamOrDefault(teamIdOpt))
          case None    => fallbackTeamOrDefault(teamIdOpt)
        }
      case None =>
        teamIdOpt match {
          case Some(tid) =>
            teamsRepo.findByIdActive(tid).map {
              case Some(t) => t.contactEmail.getOrElse(s"team-$tid@example.com")
              case None    => s"team-$tid@example.com"
            }
          case None =>
            Future.successful("no-reply@example.com")
        }
    }
  }

  /** Helper to produce a team-based fallback e-mail or the global default. */
  private def fallbackTeamOrDefault(teamIdOpt: Option[Int]): String =
    teamIdOpt match {
      case Some(tid) => s"team-$tid@example.com"
      case None      => "no-reply@example.com"
    }

  /** Truncate and stringify JSON payloads for safe logging. */
  private def safe(js: JsValue): String = {
    val s = Json.stringify(js)
    if (s.length <= 1024) s else s.take(1024) + "...(truncated)"
  }

  /**
   * Minimal HTML escaping used when embedding plain text inside a simple HTML wrapper.
   *
   * Note: This is intentionally small and pragmatic. For complex HTML templates
   * prefer a proper templating engine or a robust escaping library.
   */
  private def escapeHtml(s: String): String =
    Option(s).getOrElse("").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\"", "&quot;")
}
