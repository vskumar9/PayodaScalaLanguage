package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import play.api.Logging

import services.EventService
import repositories.NotificationEventRepository
import security.RBAC

/**
 * Controller for managing events.
 *
 * Provides endpoints for creating, reading, listing, updating, soft-deleting,
 * and restoring events. Access is guarded by [[security.RBAC]] roles.
 *
 * This controller delegates business logic to [[services.EventService]] and
 * writes notification events to [[repositories.NotificationEventRepository]]
 * and a Kafka producer service for downstream processing.
 *
 * @param cc                    Play controller components
 * @param eventService          service that performs event CRUD operations
 * @param notificationEventRepo repository for notification event records
 * @param kafkaProducer         service that publishes events to Kafka
 * @param rbac                  role-based access control helper
 * @param ec                    execution context for async operations
 */
@Singleton
class EventController @Inject()(
                                 cc: ControllerComponents,
                                 eventService: EventService,
                                 notificationEventRepo: NotificationEventRepository,
                                 kafkaProducer: services.KafkaProducerService,
                                 rbac: RBAC
                               )(implicit ec: ExecutionContext) extends AbstractController(cc) with Logging {

  /**
   * JSON formatter for java.time.Instant that serializes as ISO-8601 string.
   */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    def writes(i: Instant): JsValue = JsString(i.toString)
    def reads(json: JsValue): JsResult[Instant] = json.validate[String].map(Instant.parse)
  }

  /**
   * DTO for creating an event.
   *
   * @param title              title of the event
   * @param eventType          application-level event type/category
   * @param description        optional description
   * @param eventDate          when the event occurs
   * @param expectedGuestCount optional expected guest count
   * @param location           optional location string
   */
  case class EventCreateDto(title: String, eventType: String, description: Option[String], eventDate: Instant, expectedGuestCount: Option[Int], location: Option[String])

  /**
   * DTO for updating an event (all fields optional so partial updates are possible).
   *
   * @param title              optional new title
   * @param eventType          optional new eventType
   * @param description        optional new description
   * @param eventDate          optional new eventDate
   * @param expectedGuestCount optional new expectedGuestCount
   * @param location           optional new location
   * @param status             optional new status
   */
  case class EventUpdateDto(title: Option[String], eventType: Option[String], description: Option[String], eventDate: Option[Instant], expectedGuestCount: Option[Int], location: Option[String], status: Option[String])

  implicit val eventCreateReads = Json.reads[EventCreateDto]
  implicit val eventUpdateReads = Json.reads[EventUpdateDto]
  implicit val eventWrites      = Json.writes[models.Event]

  private val canCreate = Set("Admin", "EventManager")
  private val canEdit = Set("Admin", "EventManager")
  private val canView = Set("Admin", "EventManager", "Viewer")

  /**
   * Create a notification_event DB row and publish to Kafka in a fire-and-forget style.
   *
   * This method logs errors and marks the notification row as FAILED when publishing
   * fails. It intentionally does not block the HTTP response on publish.
   *
   * @param eventType the high-level notification type (e.g. "EVENT")
   * @param payload   json payload to publish
   * @param eventRefId optional application event id (for traceability)
   */
  private def createAndPublishEventFireAndForget(
                                                  eventType: String,
                                                  payload: JsObject,
                                                  eventRefId: Option[Int] = None
                                                ): Unit = {
    val now = Instant.now()
    val ev = models.NotificationEvent(
      eventId     = 0L,
      eventType   = eventType,
      allocationId = None,
      ticketId    = None,
      payload     = payload,
      status      = "PENDING",
      createdAt   = now,
      publishedAt = None,
      lastError   = None
    )

    notificationEventRepo.create(ev).map { eid =>
      kafkaProducer.publishEquipment(eventType, payload).map { _meta =>
        notificationEventRepo.markPublished(eid).recover {
          case ex => logger.warn(s"Published event $eid but failed to markPublished in DB", ex)
        }
      }.recoverWith { case ex =>
        logger.error(s"Failed to publish kafka eventId=$eid type=$eventType", ex)
        notificationEventRepo.markFailed(eid, ex.getMessage).recover {
          case dbEx => logger.warn(s"Failed to mark notification event $eid as FAILED", dbEx)
        }
      }
    }.recover { case ex =>
      logger.error(s"Failed to create notification_event DB row for eventType=$eventType", ex)
    }
    ()
  }

  /**
   * Create a new event.
   *
   * Expects a JSON body matching [[EventCreateDto]]. Only roles present in `canCreate`
   * can create events. On success returns 201 Created with the new event id. Unexpected
   * errors are logged and return 500.
   */
  def create = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[EventCreateDto](request, canCreate) { (authUser, dto) =>
      val createdBy = authUser.id
      eventService.createEvent(createdBy, dto.title, dto.eventType, dto.description, dto.eventDate, dto.expectedGuestCount, dto.location)
        .map { id =>
          val payload = Json.obj(
            "eventType" -> "CREATE",
            "eventId" -> id,
            "title" -> dto.title,
            "eventDate" -> dto.eventDate.toString,
            "createdBy" -> createdBy
          )
          createAndPublishEventFireAndForget("EVENT", payload, Some(id))
          Created(Json.obj("eventId" -> id))
        }
        .recover { case ex =>
          logger.error("createEvent failed", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Get an event by id.
   *
   * Requires a role in `canView`. Returns 200 with event JSON or 404 if not found.
   * Unexpected errors return 500.
   */
  def get(id: Int) = Action.async { request =>
    rbac.withRoles(request, canView) { _ =>
      eventService.getEvent(id)
        .map {
          case Some(ev) => Ok(Json.toJson(ev))
          case None => NotFound(Json.obj("error" -> "Event not found"))
        }
        .recover { case ex =>
          logger.error(s"getEvent failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * List active events.
   *
   * Requires a role in `canView`. Returns 200 with JSON array of events.
   * Errors return 500.
   */
  def list() = Action.async { request =>
    rbac.withRoles(request, canView) { _ =>
      eventService.listActive()
        .map(list => Ok(Json.toJson(list)))
        .recover { case ex =>
          logger.error("listActive failed", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Update an existing event.
   *
   * Accepts partial update payload matching [[EventUpdateDto]].
   * Requires roles in `canEdit`. Returns 200 on success, 404 when not found,
   * and 500 on unexpected errors.
   */
  def update(id: Int) = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[EventUpdateDto](request, canEdit) { (authUser, dto) =>
      eventService.getEvent(id).flatMap {
        case Some(existing) =>
          val merged = existing.copy(
            title = dto.title.getOrElse(existing.title),
            eventType = dto.eventType.getOrElse(existing.eventType),
            description = dto.description.orElse(existing.description),
            eventDate = dto.eventDate.getOrElse(existing.eventDate),
            expectedGuestCount = dto.expectedGuestCount.orElse(existing.expectedGuestCount),
            location = dto.location.orElse(existing.location),
            status = dto.status.getOrElse(existing.status),
            updatedAt = Instant.now()
          )
          eventService.updateEvent(id, merged).map {
            case 1 =>
              val payload = Json.obj(
                "eventType" -> "UPDATE",
                "eventId" -> id,
                "updatedBy" -> authUser.id,
                "updatedAt" -> Instant.now().toString
              )
              createAndPublishEventFireAndForget("EVENT", payload, Some(id))
              Ok(Json.obj("status" -> "updated"))
            case _ => InternalServerError(Json.obj("error" -> "update failed"))
          }.recover { case ex =>
            logger.error(s"updateEvent failed for id=$id", ex)
            InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
          }

        case None => Future.successful(NotFound(Json.obj("error" -> "Event not found")))
      }.recover { case ex =>
        logger.error(s"getEvent (for update) failed for id=$id", ex)
        InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Soft-delete an event.
   *
   * Marks the event deleted. Requires roles in `canEdit`. Returns 200 on success,
   * 404 if not found or already deleted, and 500 for unexpected failures.
   */
  def softDelete(id: Int) = Action.async { request =>
    rbac.withRoles(request, canEdit) { authUser =>
      eventService.softDeleteEvent(id)
        .map {
          case 1 =>
            val payload = Json.obj(
              "eventType" -> "DELETE",
              "eventId" -> id,
              "deletedBy" -> authUser.id,
              "deletedAt" -> Instant.now().toString
            )
            createAndPublishEventFireAndForget("EVENT", payload, Some(id))
            Ok(Json.obj("status" -> "deleted"))
          case _ => NotFound(Json.obj("error" -> "Event not found or already deleted"))
        }
        .recover { case ex =>
          logger.error(s"softDeleteEvent failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Restore a soft-deleted event.
   *
   * Requires roles in `canEdit`. Returns 200 on success, 404 if not found or not deleted,
   * and 500 for unexpected failures.
   */
  def restore(id: Int) = Action.async { request =>
    rbac.withRoles(request, canEdit) { authUser =>
      eventService.restoreEvent(id)
        .map {
          case 1 =>
            val payload = Json.obj(
              "eventType" -> "RESTORED",
              "eventId" -> id,
              "restoredBy" -> authUser.id,
              "restoredAt" -> Instant.now().toString
            )
            createAndPublishEventFireAndForget("EVENT", payload, Some(id))
            Ok(Json.obj("status" -> "restored"))
          case _ => NotFound(Json.obj("error" -> "Event not found or not deleted"))
        }
        .recover { case ex =>
          logger.error(s"restoreEvent failed for id=$id", ex)
          InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
        }
    }
  }
}
