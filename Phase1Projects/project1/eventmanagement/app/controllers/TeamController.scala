package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import play.api.Logging

import services.TeamService
import repositories.NotificationEventRepository
import services.KafkaProducerService
import security.RBAC

/**
 * Controller for managing teams.
 *
 * Provides endpoints to create, read, list, update, soft-delete and restore teams.
 * Access to endpoints is guarded by [[security.RBAC]] role checks. Notification
 * events are persisted to [[repositories.NotificationEventRepository]] and published
 * to Kafka via [[services.KafkaProducerService]].
 *
 * @param cc                    Play controller components
 * @param teamService           service performing team CRUD operations
 * @param notificationEventRepo repository for notification events (audit/log)
 * @param kafkaProducer         service to publish events to Kafka
 * @param rbac                  role-based access control helper
 * @param ec                    execution context for async operations
 */
@Singleton
class TeamController @Inject()(
                                cc: ControllerComponents,
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
   * DTO used to create a team.
   *
   * @param teamName     display name of the team
   * @param description  optional description of the team
   * @param contactEmail optional contact email for the team
   * @param contactPhone optional contact phone for the team
   */
  case class TeamCreateDto(teamName: String, description: Option[String], contactEmail: Option[String], contactPhone: Option[String])

  /**
   * DTO used to update a team (partial updates supported).
   *
   * @param teamName     optional new team name
   * @param description  optional new description
   * @param contactEmail optional new contact email
   * @param contactPhone optional new contact phone
   * @param isActive     optional flag to activate/deactivate the team
   */
  case class TeamUpdateDto(teamName: Option[String], description: Option[String], contactEmail: Option[String], contactPhone: Option[String], isActive: Option[Boolean])

  implicit val teamCreateReads = Json.reads[TeamCreateDto]
  implicit val teamUpdateReads = Json.reads[TeamUpdateDto]
  implicit val teamWrites = Json.writes[models.Team]

  private val canManage = Set("Admin", "EventManager")
  private val canView = Set("Admin", "EventManager", "Viewer", "TeamMember")

  /**
   * Create a notification_event DB row and publish to Kafka in a fire-and-forget manner.
   *
   * This function logs errors and will attempt to mark events as published or failed.
   * It deliberately does not block HTTP responses on publish.
   *
   * @param eventType high-level event type, e.g. "TEAM"
   * @param payload   JSON payload to publish
   */
  private def createAndPublishEventFireAndForget(eventType: String, payload: JsObject): Unit = {
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
   * Create a new team.
   *
   * Requires roles in `canManage`. Expects JSON body matching [[TeamCreateDto]].
   * On success returns 201 Created with the new team id. Unexpected errors are logged
   * and returned as 500 Internal Server Error.
   */
  def create = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[TeamCreateDto](request, canManage) { (authUser, dto) =>
      teamService.createTeam(dto.teamName, dto.description, dto.contactEmail, dto.contactPhone)
        .map { id =>
          val payload = Json.obj(
            "eventType" -> "CREATE",
            "teamId" -> id,
            "teamName" -> dto.teamName,
            "createdBy" -> authUser.id,
            "createdAt" -> Instant.now().toString
          )
          createAndPublishEventFireAndForget("TEAM", payload)
          Created(Json.obj("teamId" -> id))
        }
        .recover { case ex =>
          logger.error("createTeam failed", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Get a team by id.
   *
   * Requires roles in `canView`. Returns 200 with team JSON, 404 if not found,
   * or 500 on unexpected errors.
   */
  def get(id: Int) = Action.async { request =>
    rbac.withRoles(request, canView) { _ =>
      teamService.getTeam(id)
        .map {
          case Some(t) => Ok(Json.toJson(t))
          case None => NotFound(Json.obj("error" -> "Team not found"))
        }
        .recover { case ex =>
          logger.error(s"getTeam failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * List active teams.
   *
   * Requires roles in `canView`. Returns 200 with JSON array or 500 on error.
   */
  def list() = Action.async { request =>
    rbac.withRoles(request, canView) { _ =>
      teamService.listActiveTeams()
        .map(ts => Ok(Json.toJson(ts)))
        .recover { case ex =>
          logger.error("listActiveTeams failed", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Update an existing team (partial updates supported).
   *
   * Requires roles in `canManage`. Returns 200 on success, 404 if not found,
   * or 500 on unexpected errors.
   */
  def update(id: Int) = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[TeamUpdateDto](request, canManage) { (authUser, dto) =>
      teamService.getTeam(id).flatMap {
        case Some(existing) =>
          val merged = existing.copy(
            teamName = dto.teamName.getOrElse(existing.teamName),
            description = dto.description.orElse(existing.description),
            contactEmail = dto.contactEmail.orElse(existing.contactEmail),
            contactPhone = dto.contactPhone.orElse(existing.contactPhone),
            isActive = dto.isActive.getOrElse(existing.isActive)
          )
          teamService.updateTeam(id, merged)
            .map {
              case 1 =>
                val payload = Json.obj(
                  "eventType" -> "UPDATE",
                  "teamId" -> id,
                  "updatedBy" -> authUser.id,
                  "updatedAt" -> Instant.now().toString
                )
                createAndPublishEventFireAndForget("TEAM", payload)
                Ok(Json.obj("status" -> "updated"))
              case _ => InternalServerError(Json.obj("error" -> "update failed"))
            }
            .recover { case ex =>
              logger.error(s"updateTeam failed for id=$id", ex)
              InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
            }

        case None => Future.successful(NotFound(Json.obj("error" -> "Team not found")))
      }.recover { case ex =>
        logger.error(s"getTeam (for update) failed for id=$id", ex)
        InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Soft-delete a team.
   *
   * Requires roles in `canManage`. Publishes a delete event on success.
   */
  def softDelete(id: Int) = Action.async { request =>
    rbac.withRoles(request, canManage) { authUser =>
      teamService.softDeleteTeam(id)
        .map {
          case 1 =>
            val payload = Json.obj("eventType" -> "DELETE", "teamId" -> id, "deletedBy" -> authUser.id, "deletedAt" -> Instant.now().toString)
            createAndPublishEventFireAndForget("TEAM", payload)
            Ok(Json.obj("status" -> "deleted"))
          case _ => NotFound(Json.obj("error" -> "Team not found or already deleted"))
        }
        .recover { case ex =>
          logger.error(s"softDeleteTeam failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Restore a soft-deleted team.
   *
   * Requires roles in `canManage`. Publishes a restore event on success.
   */
  def restore(id: Int) = Action.async { request =>
    rbac.withRoles(request, canManage) { authUser =>
      teamService.restoreTeam(id)
        .map {
          case 1 =>
            val payload = Json.obj("eventType" -> "RESTORE", "teamId" -> id, "restoredBy" -> authUser.id, "restoredAt" -> Instant.now().toString)
            createAndPublishEventFireAndForget("TEAM", payload)
            Ok(Json.obj("status" -> "restored"))
          case _ => NotFound(Json.obj("error" -> "Team not found or not deleted"))
        }
        .recover { case ex =>
          logger.error(s"restoreTeam failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }
}
