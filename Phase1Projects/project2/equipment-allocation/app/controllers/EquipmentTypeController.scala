package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import java.time.Instant

import models.{EquipmentType, NotificationEvent}
import repositories.{EquipmentTypeRepository, NotificationEventRepository}
import security.RBAC
import services.KafkaProducerService

/**
 * Controller for managing equipment types (CRUD) and emitting inventory events.
 *
 * Responsibilities:
 *  - list, get, create, update, and soft-delete equipment types
 *  - create NotificationEvent rows and publish events to Kafka (fire-and-forget)
 *
 * Access control:
 *  - AdminOnly: Admin (all operations)
 *
 * All asynchronous repository/service calls include `.recover` handlers to return
 * JSON error responses and to log unexpected failures.
 *
 * Routes:
 *   GET    /api/equipment/types          -> listTypes
 *   GET    /api/equipment/types/:id      -> getType
 *   POST   /api/equipment/types          -> createType
 *   PUT    /api/equipment/types/:id      -> updateType
 *   DELETE /api/equipment/types/:id      -> deleteType
 *
 * @param cc Controller components
 * @param equipmentTypeRepo Repository for equipment types
 * @param notificationEventRepo Repository for notification events
 * @param kafkaProducer Kafka producer service used to publish events
 * @param rbac RBAC helper for role checks
 * @param ec ExecutionContext for futures
 */
@Singleton
class EquipmentTypeController @Inject()(
                                         cc: ControllerComponents,
                                         equipmentTypeRepo: EquipmentTypeRepository,
                                         notificationEventRepo: NotificationEventRepository,
                                         kafkaProducer: KafkaProducerService,
                                         rbac: RBAC
                                       )(implicit ec: ExecutionContext)
  extends AbstractController(cc) with Logging {

  /** ISO-8601 formatter for Instant values. */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    def writes(i: Instant): JsValue = JsString(i.toString)
    def reads(json: JsValue): JsResult[Instant] = json.validate[String].map(Instant.parse)
  }

  /** JSON formatter for EquipmentType model. */
  implicit val equipmentTypeFormat: OFormat[EquipmentType] = Json.format[EquipmentType]

  /**
   * Request payload for creating or updating equipment types.
   *
   * @param typeName   Name of the equipment type
   * @param description Optional description
   * @param isActive   Whether the type is active (defaults to true)
   */
  case class CreateEquipmentTypeRequest(
                                         typeName: String,
                                         description: Option[String],
                                         isActive: Boolean = true
                                       )
  implicit val createTypeReads: OFormat[CreateEquipmentTypeRequest] = Json.format[CreateEquipmentTypeRequest]

  private val AdminOnly = Set("Admin")

  /**
   * Create a NotificationEvent DB row and publish the payload to Kafka.
   *
   * This method intentionally uses fire-and-forget semantics: it handles its
   * own futures and logs or marks the notification as failed/published as needed.
   *
   * @param eventType logical event type
   * @param payload JSON payload to publish
   * @param allocationId optional allocation id
   * @param ticketId optional ticket id
   */
  private def createAndPublishEventFireAndForget(
                                                  eventType: String,
                                                  payload: JsObject,
                                                  allocationId: Option[Int] = None,
                                                  ticketId: Option[Int] = None
                                                ): Unit = {
    val now = Instant.now()
    val ev = NotificationEvent(
      eventId      = 0L,
      eventType    = eventType,
      allocationId = allocationId,
      ticketId     = ticketId,
      payload      = payload,
      status       = "PENDING",
      createdAt    = now,
      publishedAt  = None,
      lastError    = None
    )

    // create DB row -> publish -> mark published/failed (all handled internally)
    notificationEventRepo.create(ev).map { eventId =>
      val publishF = kafkaProducer.publishEquipment(eventType, payload)

      publishF.map { _meta =>
        notificationEventRepo.markPublished(eventId).recover {
          case ex => logger.warn(s"Published event $eventId but failed to markPublished in DB", ex)
        }
      }.recoverWith { case ex =>
        logger.error(s"Failed to publish kafka eventId=$eventId type=$eventType", ex)
        notificationEventRepo.markFailed(eventId, ex.getMessage).recover {
          case dbEx => logger.warn(s"Failed to mark notification event $eventId as FAILED", dbEx)
        }
      }
    }.recover { case ex =>
      logger.error(s"Failed to create notification_event DB row for eventType=$eventType", ex)
    }

    ()
  }

  /**
   * List all equipment types.
   *
   * Requires Admin role.
   *
   * @return 200 OK with JSON array of equipment types, or 500 on error
   */
  def listTypes: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      equipmentTypeRepo.findAll()
        .map(types => Ok(Json.toJson(types)))
        .recover { case NonFatal(ex) =>
          logger.error("Error listing equipment types", ex)
          InternalServerError(Json.obj("error" -> "Failed to list equipment types", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Get equipment type by id.
   *
   * Requires Admin role.
   *
   * @param id type id
   * @return 200 OK with EquipmentType JSON, 404 if not found, 500 on error
   */
  def getType(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      equipmentTypeRepo.findById(id)
        .map {
          case Some(t) => Ok(Json.toJson(t))
          case None    => NotFound(Json.obj("error" -> s"EquipmentType $id not found"))
        }
        .recover { case NonFatal(ex) =>
          logger.error(s"Error fetching equipment type $id", ex)
          InternalServerError(Json.obj("error" -> "Failed to fetch equipment type", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Create a new equipment type.
   *
   * Requires Admin role. Expects CreateEquipmentTypeRequest JSON.
   *
   * On success: creates the type, emits an INVENTORY/EQUIPMENT event (fire-and-forget)
   * and returns 201 Created with typeId.
   */
  def createType: Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      request.body.validate[CreateEquipmentTypeRequest].fold(
        errors => Future.successful(BadRequest(JsError.toJson(errors))),
        dto => {
          val now = Instant.now()
          val et = EquipmentType(
            typeId      = 0,
            typeName    = dto.typeName.trim,
            description = dto.description.map(_.trim),
            isActive    = dto.isActive,
            createdAt   = now,
            updatedAt   = now,
            isDeleted   = false,
            deletedAt   = None
          )

          equipmentTypeRepo.create(et).map { id =>
            val payload = Json.obj(
              "eventType" -> "EQUIPMENT_TYPE_CREATED",
              "typeId"    -> id,
              "typeName"  -> et.typeName,
              "createdAt" -> now.toString
            )
            createAndPublishEventFireAndForget("EQUIPMENT", payload, allocationId = None, ticketId = None)

            Created(Json.obj("typeId" -> id))
          }.recover { case NonFatal(ex) =>
            logger.error("Error creating equipment type", ex)
            InternalServerError(Json.obj("error" -> "Failed to create equipment type", "details" -> ex.getMessage))
          }
        }
      )
    }
  }

  /**
   * Update an existing equipment type.
   *
   * Requires Admin role. Expects CreateEquipmentTypeRequest JSON.
   *
   * Returns 200 with updated entity, 404 if not found, 500 on error.
   */
  def updateType(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      request.body.validate[CreateEquipmentTypeRequest].fold(
        errors => Future.successful(BadRequest(JsError.toJson(errors))),
        dto => {
          equipmentTypeRepo.findById(id).flatMap {
            case Some(existing) =>
              val updated = existing.copy(
                typeName    = dto.typeName.trim,
                description = dto.description.map(_.trim),
                isActive    = dto.isActive,
                updatedAt   = Instant.now()
              )
              equipmentTypeRepo.update(updated).map {
                case 1 =>
                  val payload = Json.obj(
                    "eventType"   -> "EQUIPMENT_TYPE_UPDATED",
                    "typeId"      -> id,
                    "typeName"    -> updated.typeName,
                    "updatedAt"   -> updated.updatedAt.toString
                  )
                  createAndPublishEventFireAndForget("EQUIPMENT", payload, allocationId = None, ticketId = None)
                  Ok(Json.toJson(updated))
                case _ =>
                  InternalServerError(Json.obj("error" -> "Failed to update equipment type"))
              }.recover { case NonFatal(ex) =>
                logger.error(s"Error updating equipment type $id", ex)
                InternalServerError(Json.obj("error" -> "Failed to update equipment type", "details" -> ex.getMessage))
              }

            case None =>
              Future.successful(NotFound(Json.obj("error" -> s"EquipmentType $id not found")))
          }.recover { case NonFatal(ex) =>
            logger.error(s"Error finding equipment type $id for update", ex)
            InternalServerError(Json.obj("error" -> "Failed to update equipment type", "details" -> ex.getMessage))
          }
        }
      )
    }
  }

  /**
   * Soft-delete an equipment type by ID.
   *
   * Requires Admin role. On success emits a EQUIPMENT event and returns 204 NoContent.
   *
   * @param id type id
   * @return 204 NoContent on success, 404 if not found, 500 on error
   */
  def deleteType(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      equipmentTypeRepo.softDelete(id).map {
        case rows if rows > 0 =>
          val now = Instant.now()
          val payload = Json.obj(
            "eventType" -> "EQUIPMENT_TYPE_DELETED",
            "typeId"    -> id,
            "deletedAt" -> now.toString
          )
          createAndPublishEventFireAndForget("EQUIPMENT", payload, allocationId = None, ticketId = None)
          NoContent
        case _ =>
          NotFound(Json.obj("error" -> s"EquipmentType $id not found"))
      }.recover { case NonFatal(ex) =>
        logger.error(s"Error deleting equipment type $id", ex)
        InternalServerError(Json.obj("error" -> "Failed to delete equipment type", "details" -> ex.getMessage))
      }
    }
  }
}
