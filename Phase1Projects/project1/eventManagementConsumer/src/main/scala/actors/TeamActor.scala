package actors

import akka.actor.{Actor, ActorLogging, Props}
import play.api.libs.json._
import scala.concurrent.Future
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import repositories.{TeamsRepository, UsersRepository, TeamMembersRepository}
import models.{EquipmentEvent, Team, User}
import mail.Mailer

/**
 * Companion object for [[TeamActor]] containing factory and message protocol.
 */
object TeamActor {
  /**
   * Create Props for a TeamActor.
   *
   * @param teamsRepo   repository used to fetch team rows (active and deleted when needed)
   * @param usersRepo   repository used to lookup user details (include deleted when needed)
   * @param membersRepo repository used to list team members
   * @param emailService mailer used to send HTML/plain emails
   * @return Props configured for TeamActor
   */
  def props(
             teamsRepo: TeamsRepository,
             usersRepo: UsersRepository,
             membersRepo: TeamMembersRepository,
             emailService: Mailer
           ): Props =
    Props(new TeamActor(teamsRepo, usersRepo, membersRepo, emailService))

  /**
   * Message used to hand an incoming equipment event to the actor.
   *
   * @param ev equipment event containing a JSON payload; payload should contain an `eventType`
   */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor responsible for handling team-related events and sending notifications.
 *
 * Responsibilities:
 *  - Listen for `EquipmentEvent` messages (typically from Kafka) that route to Team actions.
 *  - Supported inner payload `eventType`s:
 *      - CREATE, UPDATE, DELETE, RESTORE
 *      - ASSIGN (assign user to team), REMOVE_MEMBER
 *  - For each action, resolve team/user/member information via repositories,
 *    compose a concise HTML/plain notification, and attempt email delivery.
 *  - Deduplicate recipients (by email) and fall back to sensible default addresses.
 *  - Log operations and recover from failures to keep the actor loop healthy.
 *
 * Notes:
 *  - Datetime formatting uses Asia/Kolkata for human-friendly timestamps.
 *  - Email sending uses the injected `Mailer` and attempts plain-text fallback on HTML send failure.
 *
 * @param teamsRepo   repository for team lookup (findByIdActive)
 * @param usersRepo   repository for user lookup (findByIdIncludeDeleted)
 * @param membersRepo repository for listing team members (findByTeamActive)
 * @param emailService mailer used for HTML/plain sends
 */
class TeamActor(
                 teamsRepo: TeamsRepository,
                 usersRepo: UsersRepository,
                 membersRepo: TeamMembersRepository,
                 emailService: Mailer
               ) extends Actor with ActorLogging {

  import TeamActor._
  import context.dispatcher

  /** Time zone used when formatting Instants for email text. */
  private val displayZone: ZoneId = ZoneId.of("Asia/Kolkata")

  /** Formatter for human-readable timestamps used in messages. */
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-LL-dd HH:mm:ss z").withZone(displayZone)

  private def fmtInstant(i: Instant): String = displayFmt.format(i)
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")

  /**
   * Main receive loop:
   *  - extracts `eventType` from the payload JSON and routes to specific handlers
   *  - logs messages and warns on unknown or missing eventType
   */
  override def receive: Receive = {
    case HandleEvent(ev) =>
      val p = ev.payload
      val payloadEventType = (p \ "eventType").asOpt[String].map(_.trim.toUpperCase)

      payloadEventType match {
        case Some("CREATE")  =>
          log.info(s"[TeamActor] CREATE payload: ${safe(p)}")
          handleCreate(p)

        case Some("UPDATE")  =>
          log.info(s"[TeamActor] UPDATE payload: ${safe(p)}")
          handleUpdate(p)

        case Some("DELETE")  =>
          log.info(s"[TeamActor] DELETE payload: ${safe(p)}")
          handleDelete(p)

        case Some("RESTORE") =>
          log.info(s"[TeamActor] RESTORE payload: ${safe(p)}")
          handleRestore(p)

        case Some("ASSIGN") =>
          log.info(s"[TeamActor] ASSIGN payload: ${safe(p)}")
          handleAssignMember(p)

        case Some("REMOVE_MEMBER") =>
          log.info(s"[TeamActor] REMOVE_MEMBER payload: ${safe(p)}")
          handleRemoveMember(p)

        case Some(other) =>
          log.warning(s"[TeamActor] Unhandled team eventType=$other payload=${safe(p)}")

        case None =>
          log.warning(s"[TeamActor] Missing eventType in payload: ${safe(p)}")
      }

    case other =>
      log.debug(s"[TeamActor] Ignoring unsupported message: $other")
  }

  /**
   * Handle CREATE team payloads.
   *
   * Behavior:
   *  - If `teamId` present, fetch team; if not found, construct a minimal `Team` object from payload.
   *  - If only `teamName` present, build a minimal Team and notify recipients.
   *
   * @param payload JSON payload possibly containing `teamId`, `teamName`, and `createdBy`
   */
  private def handleCreate(payload: JsValue): Unit = {
    val teamIdOpt = (payload \ "teamId").asOpt[Int]
    val payloadName = (payload \ "teamName").asOpt[String]
    val createdByOpt = (payload \ "createdBy").asOpt[Int]

    teamIdOpt match {
      case Some(teamId) =>
        teamsRepo.findByIdActive(teamId).flatMap {
          case Some(teamRow) =>
            notifyTeamEvent(teamRow, "CREATE", createdByOpt, payload)
          case None =>
            val fake = Team(Some(teamId), payloadName.getOrElse(s"Team#$teamId"), None, None, None, isActive = true, Instant.now(), isDeleted = false, deletedAt = None)
            notifyTeamEvent(fake, "CREATE", createdByOpt, payload)
        }.recover { case ex =>
          log.error(ex, s"[TeamActor] Error fetching team for CREATE teamId=$teamId")
        }

      case None =>
        payloadName match {
          case Some(name) =>
            val fake = Team(None, name, None, None, None, isActive = true, Instant.now(), isDeleted = false, deletedAt = None)
            notifyTeamEvent(fake, "CREATE", createdByOpt, payload)
          case None =>
            log.warning(s"[TeamActor] CREATE payload missing teamId and teamName: ${safe(payload)}")
        }
    }
  }

  /**
   * Handle UPDATE payloads for teams.
   *
   * Attempts to fetch the team; if not found uses fallback values from payload to still notify recipients.
   */
  private def handleUpdate(payload: JsValue): Unit = {
    val teamIdOpt = (payload \ "teamId").asOpt[Int]
    val updatedByOpt = (payload \ "updatedBy").asOpt[Int]

    teamIdOpt match {
      case Some(teamId) =>
        teamsRepo.findByIdActive(teamId).flatMap {
          case Some(teamRow) =>
            notifyTeamEvent(teamRow, "UPDATE", updatedByOpt, payload)
          case None =>
            val fallbackName = (payload \ "teamName").asOpt[String].getOrElse(s"Team#$teamId")
            val fake = Team(Some(teamId), fallbackName, None, None, None, isActive = true, Instant.now(), isDeleted = false, deletedAt = None)
            notifyTeamEvent(fake, "UPDATE", updatedByOpt, payload)
        }.recover { case ex => log.error(ex, s"[TeamActor] Error fetching team for UPDATE teamId=$teamId") }

      case None =>
        log.warning(s"[TeamActor] UPDATE payload missing teamId: ${safe(payload)}")
    }
  }

  /**
   * Handle DELETE payloads for teams.
   *
   * If team not found, constructs a fallback Team marked deleted to include in notification.
   */
  private def handleDelete(payload: JsValue): Unit = {
    val teamIdOpt = (payload \ "teamId").asOpt[Int]
    val deletedByOpt = (payload \ "deletedBy").asOpt[Int]

    teamIdOpt match {
      case Some(teamId) =>
        teamsRepo.findByIdActive(teamId).flatMap {
          case Some(teamRow) =>
            notifyTeamEvent(teamRow, "DELETE", deletedByOpt, payload)
          case None =>
            val fallbackName = (payload \ "teamName").asOpt[String].getOrElse(s"Team#$teamId")
            val fake = Team(Some(teamId), fallbackName, None, None, None, isActive = false, Instant.now(), isDeleted = true, deletedAt = Some(Instant.now()))
            notifyTeamEvent(fake, "DELETE", deletedByOpt, payload)
        }.recover { case ex => log.error(ex, s"[TeamActor] Error fetching team for DELETE teamId=$teamId") }

      case None =>
        log.warning(s"[TeamActor] DELETE payload missing teamId: ${safe(payload)}")
    }
  }

  /**
   * Handle RESTORE payloads for teams.
   *
   * If team not found in DB, constructs a fallback Team marked active to notify recipients.
   */
  private def handleRestore(payload: JsValue): Unit = {
    val teamIdOpt = (payload \ "teamId").asOpt[Int]
    val restoredByOpt = (payload \ "restoredBy").asOpt[Int]

    teamIdOpt match {
      case Some(teamId) =>
        teamsRepo.findByIdActive(teamId).flatMap {
          case Some(teamRow) =>
            notifyTeamEvent(teamRow, "RESTORE", restoredByOpt, payload)
          case None =>
            val fallbackName = (payload \ "teamName").asOpt[String].getOrElse(s"Team#$teamId")
            val fake = Team(Some(teamId), fallbackName, None, None, None, isActive = true, Instant.now(), isDeleted = false, deletedAt = None)
            notifyTeamEvent(fake, "RESTORE", restoredByOpt, payload)
        }.recover { case ex => log.error(ex, s"[TeamActor] Error fetching team for RESTORE teamId=$teamId") }

      case None =>
        log.warning(s"[TeamActor] RESTORE payload missing teamId: ${safe(payload)}")
    }
  }

  /**
   * Handle ASSIGN payloads: notify the newly assigned user and/or team contact.
   *
   * Expected payload fields: teamId, userId, assignedBy (optional), message (optional)
   */
  private def handleAssignMember(payload: JsValue): Unit = {
    val teamIdOpt = (payload \ "teamId").asOpt[Int]
    val userIdOpt = (payload \ "userId").asOpt[Int]
    val assignedByOpt = (payload \ "assignedBy").asOpt[Int]

    (teamIdOpt, userIdOpt) match {
      case (Some(teamId), Some(userId)) =>
        val teamF = teamsRepo.findByIdActive(teamId)
        val userF = usersRepo.findByIdIncludeDeleted(userId)
        val actorF = assignedByOpt.map(usersRepo.findByIdIncludeDeleted).getOrElse(Future.successful(None))

        for {
          teamOpt <- teamF
          userOpt <- userF
          actorOpt <- actorF
        } yield {
          val teamName = teamOpt.map(_.teamName).getOrElse(s"Team#$teamId")
          val userName = userOpt.map(_.fullName).getOrElse(s"User#$userId")
          val actorName = actorOpt.map(_.fullName).getOrElse("System")

          val recipientEmail = userOpt.map(_.email).orElse(teamOpt.flatMap(_.contactEmail)).getOrElse("no-reply@example.com")

          val subject = s"[Team Assignment] $userName -> $teamName"
          val sentAt = fmtInstant(Instant.now())
          val plain =
            s"""Hello $userName,
               |
               |You have been assigned to team: $teamName
               |Assigned By: $actorName
               |At: $sentAt
               |
               |Message: ${(payload \ "message").asOpt[String].getOrElse("(no message)")}
             """.stripMargin
          val html = s"<pre>${escapeHtml(plain)}</pre>"

          log.info(s"[TeamActor] Assign -> to=$recipientEmail subject='$subject' preview='${plain.take(200)}'")

          emailService.sendHtml(recipientEmail, subject, html).map { _ =>
            log.info(s"[TeamActor] Assignment email sent to $recipientEmail for userId=$userId teamId=$teamId")
          }.recover { case ex =>
            log.error(ex, s"[TeamActor] Error sending assignment email to $recipientEmail")
          }
        }

      case _ =>
        log.warning(s"[TeamActor] ASSIGN payload missing teamId or userId: ${safe(payload)}")
    }
  }

  /**
   * Handle REMOVE_MEMBER payloads: notify the team contact (or fallback recipient).
   *
   * Expected payload fields: teamId, userId, removedBy (optional), message (optional)
   */
  private def handleRemoveMember(payload: JsValue): Unit = {
    val teamIdOpt = (payload \ "teamId").asOpt[Int]
    val userIdOpt = (payload \ "userId").asOpt[Int]
    val removedByOpt = (payload \ "removedBy").asOpt[Int]

    (teamIdOpt, userIdOpt) match {
      case (Some(teamId), Some(userId)) =>
        val teamF = teamsRepo.findByIdActive(teamId)
        val userF = usersRepo.findByIdIncludeDeleted(userId)
        val actorF = removedByOpt.map(usersRepo.findByIdIncludeDeleted).getOrElse(Future.successful(None))

        for {
          teamOpt <- teamF
          userOpt <- userF
          actorOpt <- actorF
        } yield {
          val teamName = teamOpt.map(_.teamName).getOrElse(s"Team#$teamId")
          val userName = userOpt.map(_.fullName).getOrElse(s"User#$userId")
          val actorName = actorOpt.map(_.fullName).getOrElse("System")

          val recipient = teamOpt.flatMap(_.contactEmail).getOrElse("ops@example.com")
          val subject = s"[Team Member Removed] $userName from $teamName"
          val sentAt = fmtInstant(Instant.now())

          val plain =
            s"""Member removed:
               |Member   : $userName
               |Team     : $teamName
               |RemovedBy: $actorName
               |At       : $sentAt
               |
               |Message: ${(payload \ "message").asOpt[String].getOrElse("(no message)")}
             """.stripMargin
          val html = s"<pre>${escapeHtml(plain)}</pre>"

          log.info(s"[TeamActor] RemoveMember -> to=$recipient subject='$subject' preview='${plain.take(200)}'")

          emailService.sendHtml(recipient, subject, html).map { _ =>
            log.info(s"[TeamActor] Removal email sent to $recipient")
          }.recover { case ex =>
            log.error(ex, s"[TeamActor] Error sending removal email to $recipient")
          }
        }

      case _ =>
        log.warning(s"[TeamActor] REMOVE_MEMBER payload missing teamId or userId: ${safe(payload)}")
    }
  }

  // ---------------------------------------------------------------------------
  // Notification helper
  // ---------------------------------------------------------------------------

  /**
   * Notify the team and its members about a team-level action.
   *
   * Steps:
   *  - Optionally resolve the actor (user who performed the action).
   *  - Resolve team member emails via membersRepo and usersRepo.
   *  - Build a deduplicated recipient list combining team contact and member emails.
   *  - Compose subject / plain / HTML messages and attempt to send to each recipient.
   *
   * @param team the Team model (may be a synthetic fallback if DB row missing)
   * @param action action label (CREATE, UPDATE, DELETE, RESTORE)
   * @param actorUserIdOpt optional id of the user who performed the action
   * @param payload original JSON payload (message/note may be present)
   * @return Future[Unit] completed once send attempts have been scheduled
   */
  private def notifyTeamEvent(team: Team, action: String, actorUserIdOpt: Option[Int], payload: JsValue): Future[Unit] = {
    val actorF = actorUserIdOpt.map(usersRepo.findByIdIncludeDeleted).getOrElse(Future.successful(None))

    // fetch team members' emails — assumption: membersRepo.findByTeamActive returns Future[Seq[models.TeamMember]]
    val membersEmailsF: Future[Seq[(String, Option[User])]] = team.teamId match {
      case Some(tid) =>
        // get TeamMember rows, resolve each to a User and keep users that have a non-empty email
        membersRepo.findByTeamActive(tid).flatMap { members: Seq[models.TeamMember] =>
          // for each TeamMember -> Future[Option[(email, Some(user))]]
          val resolvedFutures: Seq[Future[Option[(String, Option[User])]]] = members.map { member =>
            usersRepo.findByIdIncludeDeleted(member.userId).map {
              case Some(u) if u.email != null && u.email.trim.nonEmpty =>
                Some((u.email.trim, Some(u)))
              case _ =>
                None
            }
          }

          Future.sequence(resolvedFutures).map(_.flatten)
        }.recover { case ex =>
          log.error(ex, s"[TeamActor] Error while resolving team members for teamId=$tid")
          Seq.empty[(String, Option[User])]
        }

      case None =>
        Future.successful(Seq.empty[(String, Option[User])])
    }

    for {
      actorOpt <- actorF
      membersEmails <- membersEmailsF
      recipientCandidates = {
        val contactEmailOpt = team.contactEmail.map(e => ("TeamContact", None: Option[User], e))
        val membersList = membersEmails.map { case (email, userOpt) => ("Member", userOpt, email) }
        val combined = contactEmailOpt.toSeq ++ membersList
        combined.foldLeft(Seq.empty[(String, Option[User], String)]) {
          case (acc, entry @ (_, _, email)) =>
            if (acc.exists(_._3 == email)) acc else acc :+ entry
        }
      }
      recipients = if (recipientCandidates.isEmpty) Seq(("Fallback", None: Option[User], "no-reply@example.com")) else recipientCandidates

      _ = log.info(s"[TeamActor] notifyTeamEvent preparing action=$action team=${team.teamName} recipients=${recipients.map(_._3).mkString(",")}")

      sentAt = fmtInstant(Instant.now())
      actorName = actorOpt.map(_.fullName).getOrElse("System")
      // compose subject & concise message (no raw payload)
      subject = action match {
        case "CREATE" => s"[Team Created] ${team.teamName}"
        case "UPDATE" => s"[Team Updated] ${team.teamName}"
        case "DELETE" => s"[Team Deleted] ${team.teamName}"
        case "RESTORE" => s"[Team Restored] ${team.teamName}"
        case other => s"[Team Notification] ${team.teamName}"
      }
      messageFromPayload = (payload \ "message").asOpt[String].orElse((payload \ "note").asOpt[String]).getOrElse(s"$action performed on team ${team.teamName}")
      plainBody = Seq(
        s"Action   : $action",
        s"Team     : ${team.teamName}",
        s"TeamId   : ${team.teamId.getOrElse("N/A")}",
        s"By       : $actorName",
        s"At       : $sentAt",
        "",
        s"Message  : $messageFromPayload"
      ).mkString("\n")
      htmlBody = s"<pre>${escapeHtml(plainBody)}</pre>"
      sendFs = recipients.map { case (_role, userOpt, email) =>
        emailService.sendHtml(email, subject, htmlBody).map { _ =>
          log.info(s"[TeamActor] Email sent to=$email action=$action team=${team.teamId.getOrElse("N/A")}")
        }.recoverWith { case ex =>
          log.error(ex, s"[TeamActor] sendHtml failed for $email; attempting plain send")
          emailService.send(email, subject, plainBody).map { _ =>
            log.info(s"[TeamActor] Fallback plain email sent to=$email action=$action team=${team.teamId.getOrElse("N/A")}")
          }.recover { case ex2 =>
            log.error(ex2, s"[TeamActor] Fallback plain send failed for $email action=$action team=${team.teamId.getOrElse("N/A")}")
          }
        }
      }
    } yield Future.sequence(sendFs).map(_ => ())
  }.flatMap(identity)

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  /**
   * Truncate and stringify JSON safely for logs (max 1024 chars).
   *
   * @param js JSON value
   * @return truncated string
   */
  private def safe(js: JsValue): String = {
    val s = Json.stringify(js)
    if (s.length <= 1024) s else s.take(1024) + "...(truncated)"
  }

  /**
   * Minimal HTML escaping used when embedding text into HTML emails.
   *
   * Note: For complex templates prefer a templating/escaping library.
   *
   * @param s raw string
   * @return escaped string
   */
  private def escapeHtml(s: String): String =
    Option(s).getOrElse("").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\"", "&quot;")
}
