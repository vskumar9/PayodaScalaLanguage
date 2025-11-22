package actors

import akka.actor.{Actor, ActorLogging, Props}
import play.api.libs.json._
import scala.concurrent.Future
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import repositories.{TasksRepository, UsersRepository, TeamsRepository}
import models.{EquipmentEvent, Task, User, Team}
import mail.Mailer

/**
 * Factory and message protocol for the TaskActor.
 *
 * The TaskActor receives task-related equipment events (wrapped in [[models.EquipmentEvent]])
 * and is responsible for:
 *  - looking up task, user and team data,
 *  - composing role-specific email notifications for task lifecycle events
 *    (CREATE, ASSIGN, STATUS, DELETE, etc.),
 *  - attempting HTML email sends and falling back to plain-text on failure,
 *  - logging useful diagnostics and truncating very large payloads for log safety.
 *
 * Use `TaskActor.props(...)` to create Props for this actor and send it
 * `TaskActor.HandleEvent(ev)` messages to process incoming events.
 */
object TaskActor {
  /**
   * Create Props for a TaskActor.
   *
   * @param tasksRepo  repository used to fetch task rows (active tasks)
   * @param usersRepo  repository used to lookup user details (include deleted when needed)
   * @param teamsRepo  repository used to lookup team contact details
   * @param emailService mailer used to send HTML/plain emails
   * @return Props for creating the actor
   */
  def props(
             tasksRepo: TasksRepository,
             usersRepo: UsersRepository,
             teamsRepo: TeamsRepository,
             emailService: Mailer
           ): Props =
    Props(new TaskActor(tasksRepo, usersRepo, teamsRepo, emailService))

  /**
   * Message used to hand an incoming equipment event to the actor.
   *
   * @param ev equipment event containing a JSON payload with an "eventType" and task-related fields
   */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor that processes task events and sends notifications to assigned users/teams.
 *
 * Behavior summary:
 *  - Parses incoming JSON payload and extracts `eventType` (CREATE, ASSIGN, STATUS, DELETE).
 *  - Loads the corresponding [[models.Task]] from [[TasksRepository]] (active tasks only).
 *  - Resolves one or more recipient emails (assigned user, then team contact fallback).
 *  - Builds a subject, plain-text body and HTML body tailored to the action and recipient.
 *  - Sends HTML email via the injected Mailer; on failure attempts plain-text send.
 *  - Logs success/failure and preserves actor loop stability by handling Future failures.
 *
 * Notes:
 *  - Date/Time strings in emails are formatted to the Asia/Kolkata timezone for human readability.
 *  - JSON payloads are truncated when logged to avoid excessively long log lines.
 *
 * Dependencies:
 *  - tasksRepo: to fetch task records (findByIdActive)
 *  - usersRepo: to resolve user email & display name
 *  - teamsRepo: to resolve team contact email (fallback)
 *  - emailService: to deliver HTML/plain emails
 *
 * @param tasksRepo   repository for task persistence/lookup
 * @param usersRepo   repository for user lookup (include deleted)
 * @param teamsRepo   repository for team lookup (active teams)
 * @param emailService mailer used to send email notifications
 */
class TaskActor(
                 tasksRepo: TasksRepository,
                 usersRepo: UsersRepository,
                 teamsRepo: TeamsRepository,
                 emailService: Mailer
               ) extends Actor with ActorLogging {

  import TaskActor._
  import context.dispatcher

  /** Zone used when formatting Instants for email output. */
  private val displayZone = ZoneId.of("Asia/Kolkata")

  /** Formatter used for human-readable timestamps in emails. */
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-LL-dd HH:mm:ss z").withZone(displayZone)

  /** Format an Instant into a string for emails/logs. */
  private def fmtInstant(i: Instant): String = displayFmt.format(i)

  /** Format an optional Instant or return "N/A" when missing. */
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")

  override def receive: Receive = {
    case HandleEvent(ev) =>
      val p = ev.payload
      val payloadEventType = (p \ "eventType").asOpt[String].map(_.trim.toUpperCase)

      payloadEventType match {
        case Some("CREATE")  =>
          log.info(s"[TaskActor] Received CREATE payload: ${safe(p)}")
          handleCreate(p)

        case Some("ASSIGN")  =>
          log.info(s"[TaskActor] Received ASSIGN payload: ${safe(p)}")
          handleAssign(p)

        case Some("STATUS")  =>
          log.info(s"[TaskActor] Received STATUS payload: ${safe(p)}")
          handleStatus(p)

        case Some("DELETE")  =>
          log.info(s"[TaskActor] Received DELETE payload: ${safe(p)}")
          handleDelete(p)

        case Some(other) =>
          log.warning(s"[TaskActor] Unhandled payload eventType=$other payload=${safe(p)}")

        case None =>
          log.warning(s"[TaskActor] Missing eventType inside payload: ${safe(p)}")
      }

    case other =>
      log.debug(s"[TaskActor] Ignoring unsupported message: $other")
  }

  /**
   * Handle CREATE payloads: expects "taskId" in payload, fetches task and emails recipients.
   *
   * Logs a warning if taskId missing or the DB row cannot be found.
   */
  private def handleCreate(payload: JsValue): Unit = {
    val taskIdOpt = (payload \ "taskId").asOpt[Int]
    taskIdOpt match {
      case Some(tid) =>
        tasksRepo.findByIdActive(tid).flatMap {
          case Some(taskRow) =>
            composeAndSendForTask("CREATE", taskRow, payload)
          case None =>
            Future.successful {
              log.warning(s"[TaskActor] CREATE: task id=$tid not found in DB; payload=${safe(payload)}")
            }
        }.recover { case ex =>
          log.error(ex, s"[TaskActor] Error fetching task for CREATE id=$tid")
        }

      case None =>
        log.warning(s"[TaskActor] CREATE payload missing taskId: ${safe(payload)}")
    }
  }

  /**
   * Handle ASSIGN payloads: expects "taskId", may include "assignedBy".
   * If assignedBy missing, falls back to task.createdBy.
   */
  private def handleAssign(payload: JsValue): Unit = {
    val taskIdOpt = (payload \ "taskId").asOpt[Int]
    val assignedByOpt = (payload \ "assignedBy").asOpt[Int]
    taskIdOpt match {
      case Some(tid) =>
        tasksRepo.findByIdActive(tid).flatMap {
          case Some(taskRow) =>
            val actorUser = assignedByOpt.orElse(Some(taskRow.createdBy))
            composeAndSendForTask("ASSIGN", taskRow, payload, actorUser)
          case None =>
            Future.successful(log.warning(s"[TaskActor] ASSIGN: task id=$tid not found in DB"))
        }.recover { case ex =>
          log.error(ex, s"[TaskActor] Error fetching task for ASSIGN id=$tid")
        }
      case None =>
        log.warning(s"[TaskActor] ASSIGN payload missing taskId: ${safe(payload)}")
    }
  }

  /**
   * Handle STATUS payloads: expects "taskId" and optional "changedBy".
   * Sends status change notification to assigned recipients.
   */
  private def handleStatus(payload: JsValue): Unit = {
    val taskIdOpt = (payload \ "taskId").asOpt[Int]
    val changedByOpt = (payload \ "changedBy").asOpt[Int]
    taskIdOpt match {
      case Some(tid) =>
        tasksRepo.findByIdActive(tid).flatMap {
          case Some(taskRow) =>
            composeAndSendForTask("STATUS", taskRow, payload, changedByOpt)
          case None =>
            Future.successful(log.warning(s"[TaskActor] STATUS: task id=$tid not found in DB"))
        }.recover { case ex =>
          log.error(ex, s"[TaskActor] Error fetching task for STATUS id=$tid")
        }
      case None =>
        log.warning(s"[TaskActor] STATUS payload missing taskId: ${safe(payload)}")
    }
  }

  /**
   * Handle DELETE payloads: expects "taskId" and optional "deletedBy".
   * Sends deletion notification to assigned recipients.
   */
  private def handleDelete(payload: JsValue): Unit = {
    val taskIdOpt = (payload \ "taskId").asOpt[Int]
    val deletedByOpt = (payload \ "deletedBy").asOpt[Int]
    taskIdOpt match {
      case Some(tid) =>
        tasksRepo.findByIdActive(tid).flatMap {
          case Some(taskRow) =>
            composeAndSendForTask("DELETE", taskRow, payload, deletedByOpt)
          case None =>
            Future.successful(log.warning(s"[TaskActor] DELETE: task id=$tid not found in DB"))
        }.recover { case ex =>
          log.error(ex, s"[TaskActor] Error fetching task for DELETE id=$tid")
        }
      case None =>
        log.warning(s"[TaskActor] DELETE payload missing taskId: ${safe(payload)}")
    }
  }

  /**
   * Compose emails and send them to task recipients.
   *
   * @param action action label (CREATE, ASSIGN, STATUS, DELETE, etc.)
   * @param taskRow the task model loaded from DB
   * @param payload original JSON payload (may contain message or additional context)
   * @param actorUserIdOpt optional user id who performed the action (used in the message)
   * @return Future[Unit] completed once send attempts are scheduled
   */
  private def composeAndSendForTask(
                                     action: String,
                                     taskRow: Task,
                                     payload: JsValue,
                                     actorUserIdOpt: Option[Int] = None
                                   ): Future[Unit] = {

    val recipientByUserF: Future[Option[User]] =
      taskRow.assignedToUserId match {
        case Some(uid) => usersRepo.findByIdIncludeDeleted(uid)
        case None => Future.successful(None)
      }

    val recipientTeamF: Future[Option[Team]] =
      taskRow.assignedTeamId match {
        case Some(tid) => teamsRepo.findByIdActive(tid)
        case None => Future.successful(None)
      }

    val actorF: Future[Option[User]] = actorUserIdOpt match {
      case Some(uid) => usersRepo.findByIdIncludeDeleted(uid)
      case None => Future.successful(None)
    }

    for {
      recUserOpt <- recipientByUserF
      recTeamOpt <- recipientTeamF
      actorOpt   <- actorF

      // Build recipient entries and deduplicate by email
      userEntryOpt = recUserOpt.map(u => ("AssignedUser", Some(u), u.email, u.fullName))
      teamEntryOpt = recTeamOpt.flatMap(t => t.contactEmail.map(email => ("AssignedTeam", None: Option[User], email, t.teamName)))

      mergedRecipients = {
        val candidates: Seq[(String, Option[User], String, String)] =
          userEntryOpt.toSeq ++ teamEntryOpt.toSeq

        candidates.foldLeft(Seq.empty[(String, Option[User], String, String)]) {
          case (acc, entry @ (_, _, email, _)) =>
            if (acc.exists(_._3 == email)) acc else acc :+ entry
        }
      }

      recipientsToSend = if (mergedRecipients.isEmpty)
        Seq(("Recipient", None: Option[User], "no-reply@example.com", "No Recipient"))
      else mergedRecipients

      _ <- sendForRecipients(recipientsToSend, taskRow, payload, action, actorOpt)
    } yield ()
  }

  /**
   * Send emails to the resolved recipients.
   *
   * Builds subject, plain text body and HTML body. Attempts HTML send first,
   * and on failure attempts plain-text fallback. All send attempts are logged.
   *
   * @param recipients sequence of (role, optional user, email, displayName)
   * @param taskRow the task being reported on
   * @param payload original payload (may contain `message`)
   * @param action action label
   * @param actorOpt optional actor user (who performed the action)
   * @return Future[Unit] completed when all send attempts finish (or have been scheduled)
   */
  private def sendForRecipients(
                                 recipients: Seq[(String, Option[User], String, String)],
                                 taskRow: Task,
                                 payload: JsValue,
                                 action: String,
                                 actorOpt: Option[User]
                               ): Future[Unit] = {

    val sentAt = fmtInstant(Instant.now())
    val actorName = actorOpt.map(_.fullName).getOrElse("System")

    val sendFutures: Seq[Future[Unit]] = recipients.map { case (role, userOpt, email, displayName) =>
      val subject = action match {
        case "CREATE" => s"[Task Created] ${taskRow.title}"
        case "ASSIGN" => s"[Task Assigned] ${taskRow.title}"
        case "STATUS" => s"[Task Status] ${taskRow.title}"
        case "DELETE" => s"[Task Deleted] ${taskRow.title}"
        case other    => s"[Task Notification] ${taskRow.title}"
      }

      val messageFromPayload = (payload \ "message").asOpt[String].getOrElse("(no message)")

      val plainLines = Seq(
        s"Action     : $action",
        s"Task ID    : ${taskRow.taskId.getOrElse(-1)}",
        s"Title      : ${taskRow.title}",
        s"Event ID   : ${taskRow.eventId}",
        s"AssignedTo : ${taskRow.assignedToUserId.getOrElse("N/A")}",
        s"AssignedTeam: ${taskRow.assignedTeamId.getOrElse("N/A")}",
        s"Priority   : ${taskRow.priority}",
        s"Status     : ${taskRow.status}",
        s"Estimated Start: ${fmtOptInstant(taskRow.estimatedStart)}",
        s"Estimated End  : ${fmtOptInstant(taskRow.estimatedEnd)}",
        "",
        s"Message    : $messageFromPayload",
        "",
        s"Performed by: $actorName",
        s"Performed at: $sentAt"
      )
      val plainBody = plainLines.mkString("\n")

      val htmlBody =
        s"""
           |<html><body style="font-family:sans-serif;">
           |  <h3>${escapeHtml(subject)}</h3>
           |  <p><strong>Title:</strong> ${escapeHtml(taskRow.title)}</p>
           |  <p><strong>Priority:</strong> ${escapeHtml(taskRow.priority)}</p>
           |  <p><strong>Status:</strong> ${escapeHtml(taskRow.status)}</p>
           |  <p><strong>Assigned To:</strong> ${escapeHtml(displayName)}</p>
           |  <p><strong>Message:</strong> ${escapeHtml(messageFromPayload)}</p>
           |  <p><strong>Performed by:</strong> ${escapeHtml(actorName)}</p>
           |  <p style="font-size:smaller;color:#666;">SentAt: ${escapeHtml(sentAt)}</p>
           |</body></html>
        """.stripMargin

      log.info(s"[TaskActor] Prepared email -> to=[$email] subject='$subject' preview='${plainBody.take(200)}'")

      // Send HTML first; on failure fall back to plain-text and log both outcomes.
      emailService.sendHtml(email, subject, htmlBody).map(_ => ()).recoverWith { case ex =>
        log.error(ex, s"[TaskActor] sendHtml failed for $email; attempting plain text send")
        emailService.send(email, subject, plainBody).map(_ => ()).recover { case ex2 =>
          log.error(ex2, s"[TaskActor] plain send also failed for $email")
        }
      }
    }

    Future.sequence(sendFutures).map(_ => ())
  }

  /**
   * Produce a safe, truncated JSON string for logging.
   *
   * @param js JSON to stringify
   * @return truncated JSON string (max 1024 chars) to avoid giant log lines
   */
  private def safe(js: JsValue): String = {
    val s = Json.stringify(js)
    if (s.length <= 1024) s else s.take(1024) + "...(truncated)"
  }

  /**
   * Minimal HTML escaping utility used when embedding text into HTML emails.
   *
   * Note: This is intentionally simple. For larger templates prefer a proper
   * templating/escaping library.
   *
   * @param s raw string
   * @return HTML-escaped string (null-safe)
   */
  private def escapeHtml(s: String): String =
    Option(s).getOrElse("")
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
}
