package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import java.time.Instant

import models.{MaintenanceTicket, NotificationEvent}
import services.{MaintenanceService, KafkaProducerService}
import repositories.{MaintenanceTicketRepository, EmployeeRepository, NotificationEventRepository}
import security.RBAC

/**
 * Controller for managing maintenance tickets and emitting maintenance events.
 *
 * Responsibilities:
 *  - list, get, create, update status, assign and soft-delete maintenance tickets
 *  - create NotificationEvent rows and publish events to Kafka (fire-and-forget)
 *
 * Access control:
 *  - MaintenanceOnly: MaintenanceTeam (create/update/assign)
 *  - AdminOnly: Admin (list/get/delete and admin privileges)
 *
 * All asynchronous repository/service calls include `.recover` handlers to return
 * JSON error responses and to log unexpected failures.
 *
 * Routes:
 *   GET    /api/maintenance/tickets                -> listTickets
 *   GET    /api/maintenance/tickets/:id            -> getTicket
 *   POST   /api/maintenance/tickets                -> createTicket
 *   PUT    /api/maintenance/tickets/:id/status     -> updateStatus
 *   PUT    /api/maintenance/tickets/:id/assign     -> assignTicket
 *   DELETE /api/maintenance/tickets/:id            -> deleteTicket
 *
 * @param cc Controller components
 * @param maintenanceService Business service for ticket operations
 * @param ticketRepo Repository for maintenance tickets
 * @param employeeRepo Repository for employee lookups
 * @param notificationEventRepo Repository for notification events
 * @param kafkaProducer Kafka producer service used to publish events
 * @param rbac RBAC helper for role checks
 * @param ec ExecutionContext for futures
 */
@Singleton
class MaintenanceTicketController @Inject()(
                                             cc: ControllerComponents,
                                             maintenanceService: MaintenanceService,
                                             ticketRepo: MaintenanceTicketRepository,
                                             employeeRepo: EmployeeRepository,
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

  /** JSON formatter for MaintenanceTicket model. */
  implicit val ticketFormat: OFormat[MaintenanceTicket] = Json.format[MaintenanceTicket]

  /**
   * Request payload to create a maintenance ticket.
   *
   * @param equipmentId ID of the equipment having an issue
   * @param allocationId Optional allocation id associated with equipment
   * @param issueNotes Notes describing the issue
   * @param severity Severity (e.g., LOW, MEDIUM, HIGH)
   */
  case class CreateTicketRequest(
                                  equipmentId: Int,
                                  allocationId: Option[Int],
                                  issueNotes: String,
                                  severity: String
                                )

  /**
   * Request payload to update ticket status.
   *
   * @param status Status value (OPEN, IN_PROGRESS, RESOLVED, CLOSED)
   * @param isResolved Whether the ticket is marked resolved
   * @param resolvedAt Optional resolved timestamp
   */
  case class UpdateStatusRequest(
                                  status: String,          // OPEN, IN_PROGRESS, RESOLVED, CLOSED
                                  isResolved: Boolean,
                                  resolvedAt: Option[Instant]
                                )

  /**
   * Request payload to assign a ticket to an employee.
   *
   * @param assignedToEmployeeId Employee ID to assign the ticket to
   */
  case class AssignRequest(
                            assignedToEmployeeId: Int
                          )

  implicit val createTicketReads: OFormat[CreateTicketRequest] = Json.format[CreateTicketRequest]
  implicit val updateStatusReads: OFormat[UpdateStatusRequest] = Json.format[UpdateStatusRequest]
  implicit val assignReads: OFormat[AssignRequest]             = Json.format[AssignRequest]

  private val MaintenanceOnly = Set("MaintenanceTeam")
  private val AdminOnly       = Set("Admin")

  /**
   * Helper to create a NotificationEvent DB row and publish to Kafka
   * fire-and-forget style. The method handles its internal futures and logs
   * or marks the notification as failed/published as needed.
   *
   * @param eventType logical event type (e.g. "MAINTENANCE")
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
    }.recover { case NonFatal(ex) =>
      logger.error(s"Failed to create notification_event DB row for eventType=$eventType", ex)
    }

    ()
  }

  /**
   * List all maintenance tickets.
   *
   * Requires MaintenanceTeam or Admin roles.
   *
   * @return 200 OK with JSON array of tickets; 500 on error
   */
  def listTickets: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, MaintenanceOnly ++ AdminOnly) { _ =>
      ticketRepo.findAll()
        .map(tickets => Ok(Json.toJson(tickets)))
        .recover { case NonFatal(ex) =>
          logger.error("Error listing maintenance tickets", ex)
          InternalServerError(Json.obj("error" -> "Failed to list tickets", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Get a maintenance ticket by id.
   *
   * Requires MaintenanceTeam or Admin roles.
   *
   * @param id ticket id
   * @return 200 OK with ticket JSON, 404 if missing, 500 on error
   */
  def getTicket(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, MaintenanceOnly ++ AdminOnly) { _ =>
      maintenanceService.getTicket(id)
        .map {
          case Some(t) => Ok(Json.toJson(t))
          case None    => NotFound(Json.obj("error" -> s"Ticket $id not found"))
        }
        .recover { case NonFatal(ex) =>
          logger.error(s"Error fetching ticket $id", ex)
          InternalServerError(Json.obj("error" -> "Failed to fetch ticket", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Create a maintenance ticket.
   *
   * Requires MaintenanceTeam or Admin roles. Expects CreateTicketRequest JSON.
   *
   * On success:
   *  - creates ticket row,
   *  - emits MAINTENANCE TICKET_CREATED event (fire-and-forget),
   *  - returns 201 Created with ticketId.
   *
   * Returns 403 if the authenticated user has no linked employee record,
   * 400 on invalid JSON, and 500 on unexpected errors.
   */
  def createTicket: Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[CreateTicketRequest](request, MaintenanceOnly ++ AdminOnly) { (authUser, dto) =>
      employeeRepo.findByUserId(authUser.id).flatMap {
        case Some(emp) =>
          val now = Instant.now()
          val ticket = MaintenanceTicket(
            ticketId              = 0,
            equipmentId           = dto.equipmentId,
            allocationId          = dto.allocationId,
            issueReportedAt       = now,
            issueNotes            = dto.issueNotes,
            status                = "OPEN",
            severity              = dto.severity,
            createdByEmployeeId   = emp.employeeId,
            assignedToEmployeeId  = None,
            isResolved            = false,
            resolvedAt            = None,
            createdAt             = now,
            updatedAt             = now,
            isDeleted             = false,
            deletedAt             = None
          )

          ticketRepo.create(ticket).map { ticketId =>
            val payload = Json.obj(
              "eventType"         -> "TICKET_CREATED",
              "ticketId"          -> ticketId,
              "equipmentId"       -> ticket.equipmentId,
              "allocationId"      -> ticket.allocationId,
              "issueNotes"        -> ticket.issueNotes,
              "severity"          -> ticket.severity,
              "createdByEmployee" -> ticket.createdByEmployeeId,
              "createdAt"         -> now.toString
            )

            createAndPublishEventFireAndForget("MAINTENANCE", payload, allocationId = ticket.allocationId, ticketId = Some(ticketId))

            Created(Json.obj("ticketId" -> ticketId))
          }.recover { case NonFatal(ex) =>
            logger.error("Error creating maintenance ticket", ex)
            InternalServerError(Json.obj("error" -> "Failed to create ticket", "details" -> ex.getMessage))
          }

        case None =>
          Future.successful(Forbidden(Json.obj("error" -> "No employee record linked to current user")))
      }.recover { case NonFatal(ex) =>
        logger.error("Error resolving employee for ticket creation", ex)
        InternalServerError(Json.obj("error" -> "Failed to create ticket", "details" -> ex.getMessage))
      }
    }
  }

  /**
   * Update the status of a ticket.
   *
   * Requires MaintenanceTeam or Admin roles. Expects UpdateStatusRequest JSON.
   *
   * Emits TICKET_STATUS_UPDATED event on success.
   *
   * @param id ticket id
   * @return 200 on success, 404 if not found, 500 on error
   */
  def updateStatus(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[UpdateStatusRequest](request, MaintenanceOnly ++ AdminOnly) { (_authUser, dto) =>
      maintenanceService
        .updateStatus(id, dto.status, dto.isResolved, dto.resolvedAt)
        .map {
          case 1 =>
            val payload = Json.obj(
              "eventType"  -> "TICKET_STATUS_UPDATED",
              "ticketId"   -> id,
              "status"     -> dto.status,
              "isResolved" -> dto.isResolved,
              "resolvedAt" -> dto.resolvedAt.map(_.toString)
            )
            createAndPublishEventFireAndForget("MAINTENANCE", payload, allocationId = None, ticketId = Some(id))
            Ok(Json.obj("message" -> "Status updated"))
          case _ => NotFound(Json.obj("error" -> s"Ticket $id not found"))
        }
        .recover { case NonFatal(ex) =>
          logger.error(s"Error updating status for ticket $id", ex)
          InternalServerError(Json.obj("error" -> "Failed to update status", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Assign a ticket to an employee.
   *
   * Requires MaintenanceTeam or Admin roles. Expects AssignRequest JSON.
   *
   * Emits TICKET_ASSIGNED event on success.
   *
   * @param id ticket id
   * @return 200 on success, 404 if not found, 500 on error
   */
  def assignTicket(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[AssignRequest](request, MaintenanceOnly ++ AdminOnly) { (_authUser, dto) =>
      maintenanceService
        .assignTicket(id, dto.assignedToEmployeeId)
        .map {
          case 1 =>
            val payload = Json.obj(
              "eventType"            -> "TICKET_ASSIGNED",
              "ticketId"             -> id,
              "assignedToEmployeeId" -> dto.assignedToEmployeeId,
              "assignedAt"           -> Instant.now().toString
            )
            createAndPublishEventFireAndForget("MAINTENANCE", payload, allocationId = None, ticketId = Some(id))
            Ok(Json.obj("message" -> "Ticket assigned"))
          case _ => NotFound(Json.obj("error" -> s"Ticket $id not found"))
        }
        .recover { case NonFatal(ex) =>
          logger.error(s"Error assigning ticket $id", ex)
          InternalServerError(Json.obj("error" -> "Failed to assign ticket", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Soft-delete a ticket by id.
   *
   * Requires Admin role. Emits TICKET_DELETED event on success.
   *
   * @param id ticket id
   * @return 204 NoContent on success, 404 if not found, 500 on error
   */
  def deleteTicket(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      ticketRepo.softDelete(id).map {
        case rows if rows > 0 =>
          val now = Instant.now()
          val payload = Json.obj(
            "eventType" -> "TICKET_DELETED",
            "ticketId"  -> id,
            "deletedAt" -> now.toString
          )
          createAndPublishEventFireAndForget("MAINTENANCE", payload, allocationId = None, ticketId = Some(id))
          NoContent
        case _ => NotFound(Json.obj("error" -> s"Ticket $id not found"))
      }.recover { case NonFatal(ex) =>
        logger.error(s"Error deleting ticket $id", ex)
        InternalServerError(Json.obj("error" -> "Failed to delete ticket", "details" -> ex.getMessage))
      }
    }
  }
}
