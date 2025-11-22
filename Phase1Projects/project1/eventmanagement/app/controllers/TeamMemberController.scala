package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import play.api.Logging

import services.{TeamMemberService, TeamService}
import repositories.NotificationEventRepository
import services.KafkaProducerService
import security.RBAC

/**
 * Controller that manages team members.
 *
 * Endpoints:
 *  - addMember       : add a user to a team
 *  - listByTeam      : list members of a team
 *  - removeMember    : soft-delete a team member
 *  - restoreMember   : restore a previously removed team member
 *
 * This controller delegates member CRUD to [[services.TeamMemberService]] and
 * team lookups to [[services.TeamService]]. Notification events are recorded
 * via [[repositories.NotificationEventRepository]] and published to Kafka using
 * [[services.KafkaProducerService]].
 *
 * Access checks are performed by [[security.RBAC]].
 *
 * @param cc                    Play controller components
 * @param membersService        service that performs team member operations
 * @param teamService           service to validate / lookup teams
 * @param notificationEventRepo repository for notification events (audit/log)
 * @param kafkaProducer         service that publishes events to Kafka
 * @param rbac                  role-based access control helper
 * @param ec                    execution context for async operations
 */
@Singleton
class TeamMemberController @Inject()(
                                      cc: ControllerComponents,
                                      membersService: TeamMemberService,
                                      teamService: TeamService,
                                      notificationEventRepo: NotificationEventRepository,
                                      kafkaProducer: KafkaProducerService,
                                      rbac: RBAC
                                    )(implicit ec: ExecutionContext) extends AbstractController(cc) with Logging {

  /**
   * JSON formatter for java.time.Instant that reads/writes ISO-8601 strings.
   */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    def writes(i: Instant): JsValue = JsString(i.toString)
    def reads(json: JsValue): JsResult[Instant] = json.validate[String].map(Instant.parse)
  }

  /**
   * DTO to add a member to a team.
   *
   * @param teamId           id of the team
   * @param userId           id of the user to add
   * @param roleInTeam       optional role description in the team
   * @param isPrimaryContact optional flag to mark as primary contact
   */
  case class AddMemberDto(teamId: Int, userId: Int, roleInTeam: Option[String], isPrimaryContact: Option[Boolean])

  /**
   * DTO to remove (soft-delete) a team member by teamMemberId.
   *
   * @param teamMemberId id of the team member relation to remove
   */
  case class RemoveMemberDto(teamMemberId: Int)

  implicit val addMemberReads = Json.reads[AddMemberDto]
  implicit val removeMemberReads = Json.reads[RemoveMemberDto]
  implicit val teamMemberWrites = Json.writes[models.TeamMember]

  private val canManage = Set("Admin", "EventManager")
  private val canView = Set("Admin", "EventManager", "TeamMember", "Viewer")

  /**
   * Create a notification_event DB row and publish to Kafka in a fire-and-forget style.
   *
   * Errors during creation/publish are logged and the notification event row will be
   * marked FAILED when publish fails. This method intentionally does not block the HTTP response.
   *
   * @param eventType high-level event type, e.g. "TEAM_MEMBER_ADDED"
   * @param payload   JSON payload to publish
   * @param refId     optional reference id (e.g. teamMemberId)
   */
  private def createAndPublishEventFireAndForget(eventType: String, payload: JsObject, refId: Option[Int] = None): Unit = {
    val now = Instant.now()
    val ev = models.NotificationEvent(0L, eventType, None, None, payload, "PENDING", now, None, None)
    notificationEventRepo.create(ev).map { eid =>
      kafkaProducer.publishEquipment(eventType, payload).map { _ =>
        notificationEventRepo.markPublished(eid).recover { case ex => logger.warn(s"Published event $eid but failed to markPublished", ex) }
      }.recoverWith { case ex =>
        logger.error(s"Failed to publish kafka eventId=$eid type=$eventType", ex)
        notificationEventRepo.markFailed(eid, ex.getMessage).recover { case dbEx => logger.warn(s"Failed to mark event $eid as FAILED", dbEx) }
      }
    }.recover { case ex =>
      logger.error("Failed to create notification_event row", ex)
    }
    ()
  }

  /**
   * Add a member to a team.
   *
   * Requires roles in `canManage`. Expects JSON body matching [[AddMemberDto]].
   * Validates the team exists before adding. On success returns 201 with the new
   * teamMemberId. Unexpected errors are logged and return 500.
   */
  def addMember = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[AddMemberDto](request, canManage) { (authUser, dto) =>
      teamService.getTeam(dto.teamId).flatMap {
        case Some(_) =>
          val primary = dto.isPrimaryContact.getOrElse(false)
          membersService.addMember(dto.teamId, dto.userId, dto.roleInTeam, primary)
            .map { id =>
              val payload = Json.obj(
                "eventType" -> "TEAM_MEMBER_ADDED",
                "teamMemberId" -> id,
                "teamId" -> dto.teamId,
                "userId" -> dto.userId,
                "addedBy" -> authUser.id,
                "addedAt" -> Instant.now().toString
              )
              createAndPublishEventFireAndForget("TEAM_MEMBER_ADDED", payload, Some(id))
              Created(Json.obj("teamMemberId" -> id))
            }
            .recover { case ex =>
              logger.error(s"addMember failed for teamId=${dto.teamId} userId=${dto.userId}", ex)
              InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
            }

        case None =>
          Future.successful(BadRequest(Json.obj("error" -> "Team not found")))
      }.recover { case ex =>
        logger.error(s"getTeam failed for teamId=${dto.teamId}", ex)
        InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * List members for a team.
   *
   * Requires roles in `canView`. Returns 200 with JSON array or 500 on error.
   */
  def listByTeam(teamId: Int) = Action.async { request =>
    rbac.withRoles(request, canView) { _ =>
      membersService.getMembersByTeam(teamId)
        .map(list => Ok(Json.toJson(list)))
        .recover { case ex =>
          logger.error(s"getMembersByTeam failed for teamId=$teamId", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Soft-delete a team member.
   *
   * Requires roles in `canManage`. Expects JSON body matching [[RemoveMemberDto]].
   * On success returns 200. Unexpected errors produce 500.
   */
  def removeMember = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[RemoveMemberDto](request, canManage) { (authUser, dto) =>
      membersService.softDeleteMember(dto.teamMemberId)
        .map {
          case 1 =>
            val payload = Json.obj(
              "eventType" -> "TEAM_MEMBER_REMOVED",
              "teamMemberId" -> dto.teamMemberId,
              "removedBy" -> authUser.id,
              "removedAt" -> Instant.now().toString
            )
            createAndPublishEventFireAndForget("TEAM_MEMBER_REMOVED", payload, Some(dto.teamMemberId))
            Ok(Json.obj("status" -> "removed"))
          case _ => NotFound(Json.obj("error" -> "Team member not found"))
        }
        .recover { case ex =>
          logger.error(s"softDeleteMember failed for teamMemberId=${dto.teamMemberId}", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Restore a soft-deleted team member.
   *
   * Requires roles in `canManage`. On success returns 200. Errors return 500.
   */
  def restoreMember(teamMemberId: Int) = Action.async { request =>
    rbac.withRoles(request, canManage) { authUser =>
      membersService.restoreMember(teamMemberId)
        .map {
          case 1 =>
            val payload = Json.obj(
              "eventType" -> "TEAM_MEMBER_RESTORED",
              "teamMemberId" -> teamMemberId,
              "restoredBy" -> authUser.id,
              "restoredAt" -> Instant.now().toString
            )
            createAndPublishEventFireAndForget("TEAM_MEMBER_RESTORED", payload, Some(teamMemberId))
            Ok(Json.obj("status" -> "restored"))
          case _ => NotFound(Json.obj("error" -> "Team member not found or not deleted"))
        }
        .recover { case ex =>
          logger.error(s"restoreMember failed for teamMemberId=$teamMemberId", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }
}
