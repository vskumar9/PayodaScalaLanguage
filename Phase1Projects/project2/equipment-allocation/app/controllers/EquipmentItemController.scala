package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import java.time.Instant

import org.apache.kafka.clients.producer.RecordMetadata

import models.{EquipmentItem, NotificationEvent}

import repositories.{EquipmentItemRepository, NotificationEventRepository}

import services.KafkaProducerService
import security.RBAC

/**
 * Controller for managing inventory equipment items.
 *
 * Responsibilities:
 *  - list, get, create, update and soft-delete equipment items
 *  - create notification_event rows and publish events to Kafka (INVENTORY topic)
 *
 * Access control:
 *  - AdminOnly: Admin (create/update/delete)
 *  - InventoryView: Admin, InventoryTeam (list/get)
 *
 * All asynchronous operations include `.recover` handlers to return tidy JSON
 * error responses and to log unexpected failures.
 *
 * Routes:
 *   GET    /api/equipment/items          -> listItems
 *   GET    /api/equipment/items/:id      -> getItem
 *   POST   /api/equipment/items          -> createItem
 *   PUT    /api/equipment/items/:id      -> updateItem
 *   DELETE /api/equipment/items/:id      -> deleteItem
 *
 * @param cc Controller components
 * @param equipmentItemRepo Repository for equipment items
 * @param notificationEventRepo Repository for notification events
 * @param kafkaProducer Kafka producer service used to publish events
 * @param rbac RBAC helper for role checks
 * @param ec ExecutionContext for futures
 */
@Singleton
class EquipmentItemController @Inject()(
                                         cc: ControllerComponents,
                                         equipmentItemRepo: EquipmentItemRepository,
                                         notificationEventRepo: NotificationEventRepository,
                                         kafkaProducer: KafkaProducerService,
                                         rbac: RBAC
                                       )(implicit ec: ExecutionContext)
  extends AbstractController(cc)
    with Logging {

  /** JSON format for Instant using ISO-8601 textual representation. */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    def writes(i: Instant): JsValue = JsString(i.toString)
    def reads(json: JsValue): JsResult[Instant] = json.validate[String].map(Instant.parse)
  }

  /** JSON formats for domain models. */
  implicit val equipmentItemFormat: OFormat[EquipmentItem] = Json.format[EquipmentItem]
  implicit val notificationEventFormat: OFormat[NotificationEvent] = Json.format[NotificationEvent]

  /**
   * Request payload for creating (and updating) an equipment item.
   *
   * @param assetTag     Asset tag (unique identifier displayed on device)
   * @param serialNumber Manufacturer serial number
   * @param typeId       Equipment type id (foreign key)
   * @param location     Optional storage/location string
   */
  case class CreateEquipmentItemRequest(
                                         assetTag: String,
                                         serialNumber: String,
                                         typeId: Int,
                                         location: Option[String]
                                       )
  implicit val createItemReads: OFormat[CreateEquipmentItemRequest] = Json.format[CreateEquipmentItemRequest]

  private val AdminOnly      = Set("Admin")
  private val InventoryView  = Set("Admin", "InventoryTeam")

  /**
   * Creates a NotificationEvent DB row and publishes the JSON payload to Kafka.
   *
   * Returns Some(eventId) when DB row was created (and may be published), or
   * None when creating the DB row failed. The internal logic marks the event as
   * published/failed in the DB and logs any errors. The returned future itself
   * recovers to Some(eventId) / None but will surface failures as None.
   *
   * @param eventType logical event type (e.g. "INVENTORY")
   * @param payload JSON payload to publish
   * @param allocationId optional allocation id (unused for inventory events)
   * @param ticketId optional ticket id
   * @return Future[Option[eventId]]
   */
  private def createAndPublishEvent(
                                     eventType: String,
                                     payload: JsObject,
                                     allocationId: Option[Int] = None,
                                     ticketId: Option[Int] = None
                                   ): Future[Option[Long]] = {
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

    notificationEventRepo.create(ev).flatMap { eventId =>
      val sendF: Future[RecordMetadata] = kafkaProducer.publishEquipment(eventType, payload)

      sendF.flatMap { _meta =>
        notificationEventRepo.markPublished(eventId).map { _ =>
          Some(eventId)
        }.recover { case markErr =>
          logger.warn(s"Published event $eventId but failed to markPublished in DB", markErr)
          Some(eventId)
        }
      }.recoverWith { case ex =>
        logger.error(s"Failed to publish kafka eventId=$eventId type=$eventType", ex)
        notificationEventRepo.markFailed(eventId, ex.getMessage).map { _ =>
          None
        }.recover { case markErr =>
          logger.warn(s"Failed to mark notification event $eventId as FAILED", markErr)
          None
        }
      }
    }.recover { case ex =>
      logger.error(s"Failed to create notification_event DB row for eventType=$eventType", ex)
      None
    }
  }

  /**
   * List all equipment items.
   *
   * Requires InventoryView role.
   *
   * @return 200 OK with JSON array of EquipmentItem on success,
   *         500 InternalServerError on failure.
   */
  def listItems: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, InventoryView) { _ =>
      equipmentItemRepo.findAll()
        .map(items => Ok(Json.toJson(items)))
        .recover { case NonFatal(ex) =>
          logger.error("Error listing equipment items", ex)
          InternalServerError(Json.obj("error" -> "Failed to list equipment items", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Get a single equipment item by its ID.
   *
   * Requires InventoryView role.
   *
   * @param id equipment item id
   * @return 200 OK with EquipmentItem JSON, 404 NotFound if not present,
   *         500 InternalServerError on failure.
   */
  def getItem(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, InventoryView) { _ =>
      equipmentItemRepo.findById(id)
        .map {
          case Some(item) => Ok(Json.toJson(item))
          case None       => NotFound(Json.obj("error" -> s"EquipmentItem $id not found"))
        }
        .recover { case NonFatal(ex) =>
          logger.error(s"Error fetching equipment item $id", ex)
          InternalServerError(Json.obj("error" -> "Failed to fetch equipment item", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Create a new equipment item.
   *
   * Requires Admin role.
   *
   * Expected JSON: CreateEquipmentItemRequest
   *
   * On success:
   *   - creates equipment_item row,
   *   - creates notification_event row and publishes INVENTORY CREATE event (best-effort),
   *   - returns 201 Created with equipmentId.
   *
   * On failure returns appropriate 400/500 responses.
   */
  def createItem: Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      request.body.validate[CreateEquipmentItemRequest].fold(
        errors => Future.successful(BadRequest(JsError.toJson(errors))),
        dto => {
          val now = Instant.now()
          val item = EquipmentItem(
            equipmentId  = 0,
            assetTag     = dto.assetTag.trim,
            serialNumber = dto.serialNumber.trim,
            typeId       = dto.typeId,
            location     = dto.location.map(_.trim),
            status       = "AVAILABLE",
            condition    = "GOOD",
            createdAt    = now,
            updatedAt    = now,
            isDeleted    = false,
            deletedAt    = None
          )

          val flowF: Future[Result] =
            for {
              newIdOpt <- equipmentItemRepo.create(item).map(Some(_)).recover { case ex =>
                logger.error("Failed to create equipment item", ex); None
              }
              resp <- newIdOpt match {
                case Some(equipmentId) =>
                  val payload = Json.obj(
                    "eventType"    -> "CREATE",
                    "equipmentId"  -> equipmentId,
                    "assetTag"     -> item.assetTag,
                    "serialNumber" -> item.serialNumber,
                    "typeId"       -> item.typeId,
                    "createdAt"    -> now.toString
                  )

                  createAndPublishEvent("INVENTORY", payload, allocationId = None, ticketId = None)
                    .map(_ => Created(Json.obj("equipmentId" -> equipmentId)))
                    .recover { case NonFatal(ex) =>
                      // If publishing fails, we still return Created because the item was created.
                      logger.warn(s"Created equipment item $equipmentId but event publish failed", ex)
                      Created(Json.obj("equipmentId" -> equipmentId, "warning" -> "event_publish_failed"))
                    }

                case None =>
                  Future.successful(InternalServerError(Json.obj("error" -> "Failed to create equipment item")))
              }
            } yield resp

          flowF.recover { case NonFatal(ex) =>
            logger.error("Unexpected error during createItem flow", ex)
            InternalServerError(Json.obj("error" -> "Failed to create equipment item", "details" -> ex.getMessage))
          }
        }
      )
    }
  }

  /**
   * Update an existing equipment item.
   *
   * Requires Admin role.
   *
   * Expected JSON: CreateEquipmentItemRequest (same shape used for update)
   *
   * On success:
   *   - updates the equipment_item row,
   *   - creates & publishes INVENTORY UPDATE event (best-effort),
   *   - returns 200 OK with updated entity.
   *
   * Returns 404 when the item doesn't exist, 500 on failure.
   */
  def updateItem(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      request.body.validate[CreateEquipmentItemRequest].fold(
        errors => Future.successful(BadRequest(JsError.toJson(errors))),
        dto => {
          equipmentItemRepo.findById(id).flatMap {
            case Some(existing) =>
              val now = Instant.now()
              val updated = existing.copy(
                assetTag     = dto.assetTag.trim,
                serialNumber = dto.serialNumber.trim,
                typeId       = dto.typeId,
                location     = dto.location.map(_.trim),
                updatedAt    = now
              )

              equipmentItemRepo.update(updated).flatMap {
                case 1 =>
                  val payload = Json.obj(
                    "eventType"    -> "UPDATE",
                    "equipmentId"  -> id,
                    "assetTag"     -> updated.assetTag,
                    "serialNumber" -> updated.serialNumber,
                    "typeId"       -> updated.typeId,
                    "updatedAt"    -> now.toString
                  )

                  createAndPublishEvent("INVENTORY", payload).map { _ =>
                    Ok(Json.toJson(updated))
                  }.recover { case NonFatal(ex) =>
                    // item updated successfully, but publishing failed
                    logger.warn(s"Updated equipment item $id but event publish failed", ex)
                    Ok(Json.obj("warning" -> "event_publish_failed", "item" -> Json.toJson(updated)))
                  }

                case _ =>
                  Future.successful(InternalServerError(Json.obj("error" -> "Failed to update equipment item")))
              }

            case None =>
              Future.successful(NotFound(Json.obj("error" -> s"EquipmentItem $id not found")))
          }.recover { case NonFatal(ex) =>
            logger.error(s"Error during updateItem for id $id", ex)
            InternalServerError(Json.obj("error" -> "Failed to update equipment item", "details" -> ex.getMessage))
          }
        }
      )
    }
  }

  /**
   * Soft-delete an equipment item by ID.
   *
   * Requires Admin role.
   *
   * On success:
   *   - marks item as deleted via repository,
   *   - publishes INVENTORY DELETE event (best-effort),
   *   - returns 204 NoContent.
   *
   * Returns 404 when the item doesn't exist, 500 on failure.
   */
  def deleteItem(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      val now = Instant.now()
      equipmentItemRepo.softDelete(id).flatMap {
        case rows if rows > 0 =>
          val payload = Json.obj(
            "eventType"   -> "DELETE",
            "equipmentId" -> id,
            "deletedAt"   -> now.toString
          )

          createAndPublishEvent("INVENTORY", payload).map { _ =>
            NoContent
          }.recover { case NonFatal(ex) =>
            // delete succeeded, but publishing failed
            logger.warn(s"Deleted equipment item $id but event publish failed", ex)
            // still return NoContent because delete succeeded
            NoContent
          }

        case _ =>
          Future.successful(NotFound(Json.obj("error" -> s"EquipmentItem $id not found")))
      }.recover { case NonFatal(ex) =>
        logger.error(s"Error during deleteItem for id $id", ex)
        InternalServerError(Json.obj("error" -> "Failed to delete equipment item", "details" -> ex.getMessage))
      }
    }
  }
}
