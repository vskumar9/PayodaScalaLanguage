package services

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import play.api.libs.json.Json
import play.api.Logging

import models._
import repositories._

/**
 * Service encapsulating equipment-related business logic.
 *
 * Responsibilities:
 *  - Provide read operations for equipment items.
 *  - Handle allocation and return flows (with repository coordination).
 *  - Create maintenance tickets and notification events when required.
 *  - Process overdue allocations (mark them overdue, create logs/events).
 *
 * This service orchestrates multiple repositories. Errors from lower layers
 * (repositories/services) are propagated as failed futures and should be
 * handled by controllers or the caller. Some operations intentionally use
 * `.recover` at the call site to log and continue when appropriate.
 *
 * @param equipmentItemRepo        Repository for equipment items
 * @param allocationRepo           Repository for equipment allocations
 * @param overdueLogRepo           Repository for overdue reminder logs
 * @param maintenanceTicketRepo    Repository for maintenance tickets
 * @param notificationEventRepo    Repository for notification events persisted for publishing
 * @param employeeRepo             Repository for employee lookups
 * @param ec                       ExecutionContext used for asynchronous operations
 */
@Singleton
class EquipmentService @Inject()(
                                  equipmentItemRepo: EquipmentItemRepository,
                                  allocationRepo: EquipmentAllocationRepository,
                                  overdueLogRepo: OverdueLogRepository,
                                  maintenanceTicketRepo: MaintenanceTicketRepository,
                                  notificationEventRepo: NotificationEventRepository,
                                  employeeRepo: EmployeeRepository
                                )(implicit ec: ExecutionContext) extends Logging {

  /**
   * List all equipment items (non-deleted).
   *
   * @return Future sequence of EquipmentItem
   */
  def listItems(): Future[Seq[EquipmentItem]] =
    equipmentItemRepo.findAll()

  /**
   * Get a single equipment item by id.
   *
   * @param id equipment id
   * @return Future optional EquipmentItem
   */
  def getItem(id: Int): Future[Option[EquipmentItem]] =
    equipmentItemRepo.findById(id)

  /**
   * Allocate an equipment item to an employee.
   *
   * Flow:
   *  - Validate that the equipment exists and is AVAILABLE.
   *  - Create an EquipmentAllocation row.
   *  - Update the EquipmentItem status to ALLOCATED.
   *  - Persist an INVENTORY_UPDATE NotificationEvent (PENDING).
   *
   * Errors:
   *  - Returns failed Future with NoSuchElementException if equipment not found.
   *  - Returns failed Future with IllegalStateException if equipment is not available.
   *
   * @param equipmentId            id of equipment to allocate
   * @param employeeId             id of employee who will use the equipment
   * @param allocatedByEmployeeId  id of employee who performed the allocation
   * @param purpose                purpose/notes for allocation
   * @param expectedReturnAt       expected return instant
   * @return Future[Int] generated allocation id
   */
  def allocateEquipment(
                         equipmentId: Int,
                         employeeId: Int,
                         allocatedByEmployeeId: Int,
                         purpose: String,
                         expectedReturnAt: Instant
                       ): Future[Int] = {

    val now = Instant.now()

    for {
      maybeItem <- equipmentItemRepo.findById(equipmentId)
      item <- maybeItem match {
        case Some(i) if i.status == "AVAILABLE" && !i.isDeleted =>
          Future.successful(i)
        case Some(_) =>
          Future.failed(new IllegalStateException("Equipment is not available for allocation"))
        case None =>
          Future.failed(new NoSuchElementException(s"Equipment $equipmentId not found"))
      }

      allocation = EquipmentAllocation(
        allocationId          = 0,
        equipmentId           = equipmentId,
        employeeId            = employeeId,
        allocatedByEmployeeId = allocatedByEmployeeId,
        purpose               = purpose,
        allocatedAt           = now,
        expectedReturnAt      = expectedReturnAt,
        returnedAt            = None,
        conditionOnReturn     = None,
        returnNotes           = None,
        status                = "ACTIVE",
        createdAt             = now,
        updatedAt             = now,
        isDeleted             = false,
        deletedAt             = None
      )

      allocId <- allocationRepo.create(allocation)
      _       <- equipmentItemRepo.updateStatusAndCondition(equipmentId, status = "ALLOCATED", condition = item.condition)

      _ <- notificationEventRepo.create(
        NotificationEvent(
          eventId      = 0L,
          eventType    = "INVENTORY_UPDATE",
          allocationId = Some(allocId),
          ticketId     = None,
          payload      = Json.obj(
            "action"       -> "ALLOCATED",
            "equipmentId"  -> equipmentId,
            "employeeId"   -> employeeId,
            "allocatedBy"  -> allocatedByEmployeeId,
            "allocatedAt"  -> now.toString
          ),
          status       = "PENDING",
          createdAt    = now,
          publishedAt  = None,
          lastError    = None
        )
      )

    } yield allocId
  }

  /**
   * Handle return of equipment.
   *
   * Flow:
   *  - Validate allocation exists and is ACTIVE.
   *  - Validate referenced equipment exists.
   *  - Mark allocation as returned and update equipment status/condition.
   *  - If conditionOnReturn == "DAMAGED" create a maintenance ticket and a MAINTENANCE_ALERT event.
   *  - Always create an INVENTORY_UPDATE event (PENDING) for the return.
   *
   * Errors:
   *  - Fails with NoSuchElementException when allocation or equipment not found.
   *  - Fails with IllegalStateException when allocation isn't ACTIVE.
   *
   * @param allocationId id of the allocation being returned
   * @param conditionOnReturn condition string (e.g., GOOD, DAMAGED)
   * @param returnNotes optional notes about the return
   * @param actorEmployeeId employee id who performed the return action (for audit/createdBy)
   * @return Future[Unit] completed when the flow completes (or failed Future on error)
   */
  def returnEquipment(
                       allocationId: Int,
                       conditionOnReturn: String,
                       returnNotes: Option[String],
                       actorEmployeeId: Int
                     ): Future[Unit] = {

    val now = Instant.now()

    for {
      maybeAlloc <- allocationRepo.findById(allocationId)
      alloc <- maybeAlloc match {
        case Some(a) if a.status == "ACTIVE" =>
          Future.successful(a)
        case Some(_) =>
          Future.failed(new IllegalStateException("Allocation is not active or already returned"))
        case None =>
          Future.failed(new NoSuchElementException(s"Allocation $allocationId not found"))
      }

      maybeItem <- equipmentItemRepo.findById(alloc.equipmentId)
      item <- maybeItem match {
        case Some(i) if !i.isDeleted => Future.successful(i)
        case _ => Future.failed(new NoSuchElementException(s"Equipment ${alloc.equipmentId} not found"))
      }
      _ <- allocationRepo.markReturned(allocationId, now, conditionOnReturn, returnNotes)

      newStatus =
        if (conditionOnReturn == "DAMAGED") "UNDER_MAINTENANCE"
        else "AVAILABLE"

      _ <- equipmentItemRepo.updateStatusAndCondition(
        equipmentId = item.equipmentId,
        status      = newStatus,
        condition   = conditionOnReturn
      )

      _ <- conditionOnReturn match {
        case "DAMAGED" =>
          val ticket = MaintenanceTicket(
            ticketId              = 0,
            equipmentId           = item.equipmentId,
            allocationId          = Some(allocationId),
            issueReportedAt       = now,
            issueNotes            = returnNotes.getOrElse("Equipment returned as DAMAGED"),
            status                = "OPEN",
            severity              = "MEDIUM",
            createdByEmployeeId   = actorEmployeeId,
            assignedToEmployeeId  = None,
            isResolved            = false,
            resolvedAt            = None,
            createdAt             = now,
            updatedAt             = now,
            isDeleted             = false,
            deletedAt             = None
          )
          for {
            ticketId <- maintenanceTicketRepo.create(ticket)
            _ <- notificationEventRepo.create(
              NotificationEvent(
                eventId      = 0L,
                eventType    = "MAINTENANCE_ALERT",
                allocationId = Some(allocationId),
                ticketId     = Some(ticketId),
                payload      = Json.obj(
                  "equipmentId" -> item.equipmentId,
                  "ticketId"    -> ticketId,
                  "severity"    -> "MEDIUM",
                  "reportedAt"  -> now.toString
                ),
                status       = "PENDING",
                createdAt    = now,
                publishedAt  = None,
                lastError    = None
              )
            )
          } yield ()

        case _ =>
          Future.unit
      }

      _ <- notificationEventRepo.create(
        NotificationEvent(
          eventId      = 0L,
          eventType    = "INVENTORY_UPDATE",
          allocationId = Some(allocationId),
          ticketId     = None,
          payload      = Json.obj(
            "action"       -> "RETURNED",
            "equipmentId"  -> alloc.equipmentId,
            "employeeId"   -> alloc.employeeId,
            "returnedAt"   -> now.toString,
            "condition"    -> conditionOnReturn
          ),
          status      = "PENDING",
          createdAt   = now,
          publishedAt = None,
          lastError   = None
        )
      )

    } yield ()
  }

  /**
   * Process overdue allocations by:
   *  - finding allocations that are overdue as of `now`
   *  - marking them as OVERDUE
   *  - creating OverdueLog entries
   *  - creating reminder NotificationEvents
   *
   * The method attempts best-effort processing for each allocation and logs
   * but does not throw away processing state for other allocations when one fails.
   *
   * @param now reference instant to consider as "current" time (defaults to Instant.now())
   * @return Future sequence of allocation ids that were processed (i.e., marked/attempted)
   */
  def processOverdues(now: Instant = Instant.now()): Future[Seq[Int]] = {
    for {
      overdues <- allocationRepo.findOverdue(now)
      processedIds <- Future.traverse(overdues) { alloc =>
        val allocationId = alloc.allocationId

        val log = models.OverdueLog(
          overdueId = 0,
          allocationId = allocationId,
          notifiedAt = now,
          reminderType = "FIRST",
          recipientEmployeeId = alloc.employeeId,
          channel = "EMAIL"
        )

        val event = models.NotificationEvent(
          eventId = 0L,
          eventType = "REMINDER",
          allocationId = Some(allocationId),
          ticketId = None,
          payload = Json.obj(
            "eventType"        -> "OVERDUE_REMINDER",
            "allocationId"     -> allocationId,
            "equipmentId"      -> alloc.equipmentId,
            "employeeId"       -> alloc.employeeId,
            "expectedReturnAt" -> alloc.expectedReturnAt.toString,
            "notifiedAt"       -> now.toString
          ),
          status = "PENDING",
          createdAt = now,
          publishedAt = None,
          lastError = None
        )

        allocationRepo.markOverdue(allocationId).flatMap { rowsAffected =>
          if (rowsAffected > 0) {
            overdueLogRepo.create(log).flatMap { _ =>
              notificationEventRepo.create(event).map { _ =>
                Some(allocationId)
              }.recover { ex =>
                logger.error(s"Failed to create notification event for allocationId=$allocationId", ex)
                Some(allocationId)
              }
            }.recover { ex =>
              logger.error(s"Failed to create overdue log for allocationId=$allocationId", ex)
              Some(allocationId)
            }
          } else {
            logger.warn(s"markOverdue affected 0 rows for allocationId=$allocationId (maybe already updated)")
            Future.successful(None)
          }
        }.recover { ex =>
          logger.error(s"Failed to mark allocationId=$allocationId as OVERDUE", ex)
          None
        }
      }
    } yield processedIds.flatten
  }

  /**
   * Helper to find the Employee model for a given user id.
   *
   * @param userId application user id
   * @return Future optional Employee
   */
  def findEmployeeByUserId(userId: Int): Future[Option[Employee]] =
    employeeRepo.findByUserId(userId)
}
