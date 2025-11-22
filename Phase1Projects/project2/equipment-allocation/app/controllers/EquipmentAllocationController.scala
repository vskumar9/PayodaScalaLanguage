package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import play.api.Logging

import models.{EquipmentAllocation, NotificationEvent, EquipmentItem, Employee}
import services.{EquipmentService, KafkaProducerService}
import repositories.{EquipmentAllocationRepository, NotificationEventRepository}
import security.RBAC

/**
 * Controller responsible for equipment allocation lifecycle:
 *  - allocate equipment to employees
 *  - return equipment
 *  - query allocations and overdue processing
 *
 * Access is controlled via RBAC. Several roles are defined:
 *  - ReceptionOnly: ReceptionStaff, Admin         (create/return)
 *  - InventoryView: Admin, InventoryTeam, ReceptionStaff (view)
 *  - AdminOnly: Admin                             (admin-only tasks)
 *
 * The controller uses `equipmentService` for business operations, repositories
 * for persistence and `kafkaProducer` + `notificationEventRepo` to persist and
 * publish notification events. All asynchronous operations include `.recover`
 * handlers to return tidy JSON errors and to log unexpected failures.
 *
 * Routes:
 *   GET  /api/equipment/allocations               → listAllocations
 *   GET  /api/equipment/allocations/:id           → getAllocation
 *   POST /api/equipment/allocations               → allocate
 *   POST /api/equipment/allocations/:id/return    → returnEquipment
 *   POST /api/jobs/overdues/run                   → listAllocationsOverdue
 *
 * @param cc Controller components
 * @param equipmentService Business service to allocate/return equipment
 * @param allocationRepo Persistence for equipment allocations
 * @param notificationEventRepo Persistence for notification events
 * @param kafkaProducer Producer to publish events to Kafka
 * @param rbac RBAC helper for role checks
 * @param ec ExecutionContext for futures
 */
@Singleton
class EquipmentAllocationController @Inject()(
                                               cc: ControllerComponents,
                                               equipmentService: EquipmentService,
                                               allocationRepo: EquipmentAllocationRepository,
                                               notificationEventRepo: NotificationEventRepository,
                                               kafkaProducer: KafkaProducerService,
                                               rbac: RBAC
                                             )(implicit ec: ExecutionContext)
  extends AbstractController(cc) with Logging {

  /** JSON format for Instant using ISO-8601 text representation. */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    def writes(i: Instant): JsValue = JsString(i.toString)
    def reads(json: JsValue): JsResult[Instant] = json.validate[String].map(Instant.parse)
  }

  /** JSON format for Allocation, EquipmentItem and Employee models. */
  implicit val allocationFormat: OFormat[EquipmentAllocation] = Json.format[EquipmentAllocation]
  implicit val equipmentFormat: OFormat[EquipmentItem] = Json.format[EquipmentItem]
  implicit val employeeFormat: OFormat[Employee] = Json.format[Employee]

  /**
   * Request payload to allocate equipment.
   *
   * @param equipmentId ID of equipment to allocate
   * @param employeeId  ID of employee receiving equipment
   * @param purpose     Purpose / reason for allocation
   * @param expectedReturnAt Expected return timestamp
   */
  case class AllocateRequest(
                              equipmentId: Int,
                              employeeId: Int,
                              purpose: String,
                              expectedReturnAt: Instant
                            )

  /**
   * Request payload when returning equipment.
   *
   * @param conditionOnReturn "GOOD" or "DAMAGED"
   * @param returnNotes Optional notes about the return
   */
  case class ReturnRequest(
                            conditionOnReturn: String,      // GOOD / DAMAGED
                            returnNotes: Option[String]
                          )

  implicit val allocateReads: OFormat[AllocateRequest] = Json.format[AllocateRequest]
  implicit val returnReads: OFormat[ReturnRequest]     = Json.format[ReturnRequest]

  private val ReceptionOnly = Set("ReceptionStaff", "Admin")
  private val InventoryView = Set("Admin", "InventoryTeam", "ReceptionStaff")
  private val AdminOnly     = Set("Admin")

  /**
   * Helper to create a NotificationEvent DB row and publish to Kafka
   * fire-and-forget style. The method intentionally returns Unit — the internal
   * futures are handled with recover/recoverWith to mark the DB row as
   * published/failed and to log errors.
   *
   * @param eventType logical event type (e.g. "ALLOCATION", "MAINTENANCE")
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
      logger.error(s"Failed to create notification_event DB row for eventType=$eventType allocationId=${allocationId.getOrElse("-")}", ex)
    }

    ()
  }

  /**
   * Get allocation by id.
   *
   * Requires InventoryView role.
   *
   * @param id Allocation ID
   * @return 200 OK with allocation JSON, 404 if not found, 500 on error
   */
  def getAllocation(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, InventoryView) { _ =>
      allocationRepo.findById(id).map {
        case Some(a) => Ok(Json.toJson(a))
        case None    => NotFound(Json.obj("error" -> s"Allocation $id not found"))
      }.recover { case ex =>
        logger.error(s"Error fetching allocation $id", ex)
        InternalServerError(Json.obj("error" -> "Failed to fetch allocation", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Allocate an equipment to an employee.
   *
   * Requires ReceptionOnly role. Expects AllocateRequest JSON.
   *
   * On success:
   *   - creates allocation (via equipmentService),
   *   - creates & publishes ALLOCATION notification event (fire-and-forget),
   *   - returns 201 Created with allocationId.
   *
   * Handles repository/service exceptions and returns appropriate HTTP codes:
   *   - 404 NotFound when equipment missing,
   *   - 409 Conflict when equipment unavailable,
   *   - 500 InternalServerError on unexpected errors.
   */
  def allocate: Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[AllocateRequest](request, ReceptionOnly) { (authUser, dto) =>
      val userId = authUser.id

      equipmentService.findEmployeeByUserId(userId).flatMap {
        case None =>
          Future.successful(Unauthorized(Json.obj("error" -> "No employee record found for authenticated user")))

        case Some(employee) =>
          val allocatedByEmployeeId = employee.employeeId

          equipmentService
            .allocateEquipment(
              equipmentId           = dto.equipmentId,
              employeeId            = dto.employeeId,
              allocatedByEmployeeId = allocatedByEmployeeId,
              purpose               = dto.purpose,
              expectedReturnAt      = dto.expectedReturnAt
            )
            .map { allocId =>
              val now = Instant.now()
              val payload = Json.obj(
                "eventType"         -> "CREATE",
                "allocationId"      -> allocId,
                "equipmentId"       -> dto.equipmentId,
                "employeeId"        -> dto.employeeId,
                "allocatedBy"       -> allocatedByEmployeeId,
                "purpose"           -> dto.purpose,
                "allocatedAt"       -> now.toString,
                "expectedReturnAt"  -> dto.expectedReturnAt.toString,
                "status"            -> "ACTIVE"
              )

              createAndPublishEventFireAndForget("ALLOCATION", payload, allocationId = Some(allocId))

              Created(Json.obj("allocationId" -> allocId))
            }
            .recover {
              case _: NoSuchElementException =>
                NotFound(Json.obj("error" -> s"Equipment ${dto.equipmentId} not found"))
              case _: IllegalStateException =>
                Conflict(Json.obj("error" -> "Equipment is not available for allocation"))
              case ex =>
                logger.error("Unexpected error during allocation", ex)
                InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
            }
      }.recover { case ex =>
        logger.error("Error during allocation flow", ex)
        InternalServerError(Json.obj("error" -> "Failed to allocate equipment", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Process return of equipment for a given allocation id.
   *
   * Requires ReceptionOnly role. Expects ReturnRequest JSON.
   *
   * On success:
   *   - calls equipmentService.returnEquipment,
   *   - publishes RETURN and possibly MAINTENANCE events.
   *
   * Returns:
   *   - 200 OK on success,
   *   - 404 NotFound if allocation/equipment missing,
   *   - 409 Conflict on invalid state,
   *   - 500 InternalServerError on unexpected errors.
   */
  def returnEquipment(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[ReturnRequest](request, ReceptionOnly) { (authUser, dto) =>
      val actorUserId = authUser.id

      val actorEmployeeIdF: Future[Int] =
        equipmentService.findEmployeeByUserId(actorUserId).map {
          case Some(emp) => emp.employeeId
          case None      =>
            logger.warn(s"No Employee record found for user ${actorUserId}, using user id as actor id fallback")
            actorUserId
        }

      actorEmployeeIdF.flatMap { actorEmployeeId =>
        equipmentService
          .returnEquipment(
            allocationId      = id,
            conditionOnReturn = dto.conditionOnReturn,
            returnNotes       = dto.returnNotes,
            actorEmployeeId   = actorEmployeeId
          )
          .map { _ =>
            val now = Instant.now()
            val payload = Json.obj(
              "eventType"         -> "RETURN",
              "allocationId"      -> id,
              "conditionOnReturn" -> dto.conditionOnReturn,
              "returnNotes"       -> dto.returnNotes,
              "actorEmployeeId"   -> actorEmployeeId,
              "returnedAt"        -> now.toString,
              "status"            -> "RETURNED"
            )

            createAndPublishEventFireAndForget("ALLOCATION", payload, allocationId = Some(id))

            if (dto.conditionOnReturn.equalsIgnoreCase("DAMAGED")) {
              val alertPayload = Json.obj(
                "eventType"    -> "MAINTENANCE_ALERT",
                "allocationId" -> id,
                "reportedBy"   -> actorEmployeeId,
                "reportedAt"   -> now.toString,
                "severity"     -> "HIGH",
                "notes"        -> dto.returnNotes
              )
              createAndPublishEventFireAndForget("MAINTENANCE", alertPayload, allocationId = Some(id))
            }

            Ok(Json.obj("message" -> "Equipment returned successfully"))
          }
          .recover {
            case e: NoSuchElementException =>
              NotFound(Json.obj("error" -> e.getMessage))
            case e: IllegalStateException =>
              Conflict(Json.obj("error" -> e.getMessage))
            case ex =>
              logger.error("Unexpected error during returnEquipment", ex)
              InternalServerError(Json.obj("error" -> "Internal server error", "details" -> ex.getMessage))
          }
      }.recover { case ex =>
        logger.error(s"Error resolving actor employee for user $actorUserId", ex)
        InternalServerError(Json.obj("error" -> "Failed to process return", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Execute overdue processing job and publish reminders for affected allocations.
   *
   * Requires Admin role.
   *
   * @return 200 OK with summary of affected allocations or 500 on failure
   */
  def runOverdueJob: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      equipmentService.processOverdues().map {
        case seq: Seq[_] =>
          val ids = seq.collect { case i: Int => i }
          val now = Instant.now()
          ids.foreach { aid =>
            val payload = Json.obj(
              "eventType"     -> "OVERDUE_REMINDER",
              "allocationId"  -> aid,
              "notifiedAt"    -> now.toString
            )
            createAndPublishEventFireAndForget("REMINDER", payload, allocationId = Some(aid))
          }
          Ok(Json.obj("message" -> "Overdue job executed", "affected" -> ids.size))

        case _ =>
          Ok(Json.obj("message" -> "Overdue job executed"))
      }.recover {
        case ex =>
          logger.error("Error running overdue job", ex)
          InternalServerError(Json.obj("error" -> "Failed to run overdue job", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * List overdue allocations (active allocations which are overdue).
   *
   * Requires InventoryView role.
   *
   * NOTE: The repository call used in the original code passed a placeholder
   * Instant; keep semantics upstream or update repository signature as needed.
   *
   * @return 200 OK with JSON array of overdue allocations, 500 on error
   */
  def listAllocationsOverdue: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, InventoryView) { _ =>
      allocationRepo
        .findOverdue(Instant.ofEpochMilli(Long.MaxValue))
        .map { allocs =>
          Ok(Json.toJson(allocs))
        }
        .recover { case ex =>
          logger.error("Error listing overdue allocations", ex)
          InternalServerError(Json.obj("error" -> "Failed to list overdue allocations", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * List all allocations.
   *
   * Requires InventoryView role.
   *
   * @return 200 OK with JSON array of allocations, 500 on failure
   */
  def listAllocations: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, InventoryView) { _ =>
      allocationRepo
        .listAllAllocations()
        .map(allocs => Ok(Json.toJson(allocs)))
        .recover { case ex =>
          logger.error("Error listing allocations", ex)
          InternalServerError(Json.obj("error" -> "Failed to list allocations", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * List allocation details joined with equipment and employee info.
   *
   * Requires InventoryView role.
   *
   * @return 200 OK with JSON array of detailed allocation objects, 500 on error
   */
  def listAllocationDetails: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, InventoryView) { _ =>
      allocationRepo.listAllAllocationDetails().map { detailsSeq =>
        val jsArray = detailsSeq.map { d =>
          JsObject(Seq(
            "allocation"  -> Json.toJson(d.allocation),
            "equipment"   -> d.equipment.map(Json.toJson(_)).getOrElse(JsNull),
            "employee"    -> d.employee.map(Json.toJson(_)).getOrElse(JsNull),
            "allocatedBy" -> d.allocatedBy.map(Json.toJson(_)).getOrElse(JsNull)
          ))
        }
        Ok(JsArray(jsArray.toVector))
      }.recover { case ex =>
        logger.error("Error listing allocation details", ex)
        InternalServerError(Json.obj("error" -> "Failed to list allocation details", "details" -> ex.getMessage))
      }
    }
  }
}
