package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import play.api.Logging

import services.{TaskService, NotificationService}
import repositories.NotificationEventRepository
import services.KafkaProducerService
import security.RBAC

/**
 * Controller for managing tasks tied to events.
 *
 * Provides endpoints to create tasks, list tasks for an event, get a task,
 * update tasks, update task status, soft-delete tasks, and trigger processing
 * of due notifications. Access is guarded by [[security.RBAC]] roles.
 *
 * Business logic is delegated to [[services.TaskService]] and notification
 * concerns to [[services.NotificationService]] and [[repositories.NotificationEventRepository]].
 *
 * @param cc                    Play controller components
 * @param taskService           service performing task CRUD
 * @param notificationService   service for creating and scheduling notifications
 * @param notificationEventRepo repository for notification events (audit/log)
 * @param kafkaProducer         service to publish events to Kafka
 * @param rbac                  role based access control helper
 * @param ec                    execution context for async operations
 */
@Singleton
class TaskController @Inject()(
                                cc: ControllerComponents,
                                taskService: TaskService,
                                notificationService: NotificationService,
                                notificationEventRepo: NotificationEventRepository,
                                kafkaProducer: KafkaProducerService,
                                rbac: RBAC
                              )(implicit ec: ExecutionContext) extends AbstractController(cc) with Logging {

  /**
   * JSON format for Instant (ISO-8601 string).
   */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    def writes(i: Instant): JsValue = JsString(i.toString)
    def reads(json: JsValue): JsResult[Instant] = json.validate[String].map(Instant.parse)
  }

  /**
   * DTO used to create a new task.
   *
   * @param eventId            event the task belongs to
   * @param title              title of the task
   * @param description        optional description
   * @param assignedTeamId     optional team id to which the task is assigned
   * @param assignedToUserId   optional user id the task is assigned to
   * @param priority           optional priority (default handled server-side)
   * @param estimatedStart     optional estimated start Instant
   * @param estimatedEnd       optional estimated end Instant
   * @param specialRequirements optional special requirements notes
   */
  case class TaskCreateDto(eventId: Int, title: String, description: Option[String], assignedTeamId: Option[Int], assignedToUserId: Option[Int], priority: Option[String], estimatedStart: Option[Instant], estimatedEnd: Option[Instant], specialRequirements: Option[String])

  /**
   * DTO used to update an existing task (partial updates supported).
   */
  case class TaskUpdateDto(title: Option[String], description: Option[String], assignedTeamId: Option[Int], assignedToUserId: Option[Int], priority: Option[String], status: Option[String], estimatedStart: Option[Instant], estimatedEnd: Option[Instant], specialRequirements: Option[String])

  /**
   * DTO for updating only the status of a task.
   */
  case class UpdateStatusDto(status: String)

  implicit val taskCreateReads = Json.reads[TaskCreateDto]
  implicit val taskUpdateReads = Json.reads[TaskUpdateDto]
  implicit val updateStatusReads = Json.reads[UpdateStatusDto]
  implicit val taskWrites = Json.writes[models.Task]

  private val canCreate = Set("Admin", "EventManager")
  private val canAssign = Set("Admin", "EventManager")
  private val canUpdateStatus = Set("Admin", "EventManager", "TeamMember")
  private val canView = Set("Admin", "EventManager", "TeamMember", "Viewer")

  /**
   * Create a notification_event DB row and publish to Kafka (fire-and-forget).
   *
   * This will attempt to create a DB row, publish to Kafka and mark the row as published.
   * If publishing fails the row will be marked FAILED. Errors are logged; this method
   * deliberately does not block or change the HTTP response.
   *
   * @param eventType high-level event type, e.g. "TASK"
   * @param payload   JSON payload for the notification
   * @param refId     optional reference id (e.g. task id)
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
   * Create a new task.
   *
   * Requires roles in [[canCreate]]. Expects JSON body matching [[TaskCreateDto]].
   * On success returns 201 Created with the new task id. Unexpected errors produce 500.
   */
  def create = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[TaskCreateDto](request, canCreate) { (authUser, dto) =>
      val createdBy = authUser.id
      val priority = dto.priority.getOrElse("normal")
      taskService.createTask(dto.eventId, createdBy, dto.title, dto.description, dto.assignedTeamId, dto.assignedToUserId, priority, dto.estimatedStart, dto.estimatedEnd, dto.specialRequirements)
        .map { taskId =>
          val payload = Json.obj(
            "eventType" -> "CREATE",
            "taskId" -> taskId,
            "eventId" -> dto.eventId,
            "createdBy" -> createdBy,
            "assignedTeamId" -> dto.assignedTeamId,
            "assignedToUserId" -> dto.assignedToUserId,
            "estimatedStart" -> dto.estimatedStart.map(_.toString)
          )
          createAndPublishEventFireAndForget("TASK", payload, Some(taskId))
          Created(Json.obj("taskId" -> taskId))
        }
        .recover { case ex =>
          logger.error("createTask failed", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * List tasks for an event.
   *
   * Requires roles in [[canView]]. Returns 200 with JSON array or 500 on error.
   */
  def listForEvent(eventId: Int) = Action.async { request =>
    rbac.withRoles(request, canView) { _ =>
      taskService.listTasksForEvent(eventId)
        .map(list => Ok(Json.toJson(list)))
        .recover { case ex =>
          logger.error(s"listTasksForEvent failed for eventId=$eventId", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Get a single task by id.
   *
   * Requires roles in [[canView]]. Returns 200 with task JSON, 404 if not found, or 500 on error.
   */
  def getTask(id: Int) = Action.async { request =>
    rbac.withRoles(request, canView) { _ =>
      taskService.getTask(id).map {
        case Some(t) => Ok(Json.toJson(t))
        case None => NotFound(Json.obj("error" -> "Task not found"))
      }.recover { case ex =>
        logger.error(s"getTask failed for id=$id", ex)
        InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Update an existing task (partial updates supported).
   *
   * Requires roles in [[canAssign]]. If assignment fields are changed, assignment notifications
   * and reminders are created and an event is published. Returns 200 on success, 404 if not found,
   * or 500 on unexpected errors.
   */
  def update(id: Int) = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[TaskUpdateDto](request, canAssign) { (authUser, dto) =>
      taskService.getTask(id).flatMap {
        case Some(existing) =>
          val merged = existing.copy(
            title = dto.title.getOrElse(existing.title),
            description = dto.description.orElse(existing.description),
            assignedTeamId = dto.assignedTeamId.orElse(existing.assignedTeamId),
            assignedToUserId = dto.assignedToUserId.orElse(existing.assignedToUserId),
            priority = dto.priority.getOrElse(existing.priority),
            status = dto.status.getOrElse(existing.status),
            estimatedStart = dto.estimatedStart.orElse(existing.estimatedStart),
            estimatedEnd = dto.estimatedEnd.orElse(existing.estimatedEnd),
            specialRequirements = dto.specialRequirements.orElse(existing.specialRequirements),
            updatedAt = Instant.now()
          )
          taskService.updateTask(id, merged).map {
            case 1 =>
              if (dto.assignedTeamId.isDefined || dto.assignedToUserId.isDefined) {
                // create assignment notifications (side-effecting)
                notificationService.createAssignmentNotification(merged.eventId, id, merged.assignedTeamId, merged.assignedToUserId, merged.title, merged.estimatedStart)
                notificationService.scheduleReminderNotifications(merged.eventId, id, merged.assignedTeamId, merged.assignedToUserId, merged.title, merged.estimatedStart)

                val payload = Json.obj(
                  "eventType" -> "ASSIGN",
                  "taskId" -> id,
                  "eventId" -> merged.eventId,
                  "assignedTeamId" -> merged.assignedTeamId,
                  "assignedToUserId" -> merged.assignedToUserId,
                  "assignedBy" -> authUser.id,
                  "assignedAt" -> Instant.now().toString
                )
                createAndPublishEventFireAndForget("TASK", payload, Some(id))
              }
              Ok(Json.obj("status" -> "updated"))
            case _ => InternalServerError(Json.obj("error" -> "update failed"))
          }.recover { case ex =>
            logger.error(s"updateTask failed for id=$id", ex)
            InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
          }

        case None => Future.successful(NotFound(Json.obj("error" -> "Task not found")))
      }.recover { case ex =>
        logger.error(s"getTask (for update) failed for id=$id", ex)
        InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Update only the status of a task.
   *
   * Requires roles in [[canUpdateStatus]]. Publishes a status-change event on success.
   */
  def updateStatus(id: Int) = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[UpdateStatusDto](request, canUpdateStatus) { (authUser, dto) =>
      taskService.updateTaskStatus(id, dto.status)
        .map {
          case 1 =>
            val payload = Json.obj("eventType" -> "STATUS", "taskId" -> id, "newStatus" -> dto.status, "changedBy" -> authUser.id, "changedAt" -> Instant.now().toString)
            createAndPublishEventFireAndForget("TASK", payload, Some(id))
            Ok(Json.obj("status" -> "updated"))
          case _ => NotFound(Json.obj("error" -> "Task not found"))
        }
        .recover { case ex =>
          logger.error(s"updateTaskStatus failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Soft-delete a task.
   *
   * Requires roles in [[canAssign]]. Publishes a delete event on success.
   */
  def softDelete(id: Int) = Action.async { request =>
    rbac.withRoles(request, canAssign) { authUser =>
      taskService.softDeleteTask(id)
        .map {
          case 1 =>
            val payload = Json.obj("eventType" -> "DELETE", "taskId" -> id, "deletedBy" -> authUser.id, "deletedAt" -> Instant.now().toString)
            createAndPublishEventFireAndForget("TASK", payload, Some(id))
            Ok(Json.obj("status" -> "deleted"))
          case _ => NotFound(Json.obj("error" -> "Task not found"))
        }
        .recover { case ex =>
          logger.error(s"softDeleteTask failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Trigger processing of due notifications.
   *
   * Requires Admin or System role. Returns 200 once processing has been started or 500 on error.
   */
  def processDueNotifications() = Action.async { request =>
    rbac.withRoles(request, Set("Admin", "System")) { _ =>
      notificationService.processDueNotifications()
        .map(_ => Ok(Json.obj("status" -> "processing_started")))
        .recover { case ex =>
          logger.error("processDueNotifications failed", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }
}
