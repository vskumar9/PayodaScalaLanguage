package repositories

import models._
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing `EquipmentAllocation` records and their related information.
 *
 * This repository provides a full set of operations for:
 *   - Creating allocations
 *   - Fetching live/active/overdue allocations
 *   - Joining allocations with equipment + employee information
 *   - Updating allocation status (returned, overdue)
 *   - Performing soft deletes
 *
 * It is used heavily by:
 *   - AllocationActor (processing Kafka ALLOCATION events)
 *   - ReminderActor (checking overdue items)
 *   - InventoryActor (updates on item return or damage)
 *   - Controllers providing admin views
 *
 * ### Soft Delete
 * Allocations are never physically removed; instead:
 *   - `isDeleted = true`
 *   - `deletedAt = timestamp`
 *
 * This preserves historical traceability of allocation activity.
 *
 * ### Database Mapping
 * Slick `AllocationRow` is used internally for mapping DB rows, while callers
 * receive the domain model [[EquipmentAllocation]].
 *
 * @param dbConfigProvider Injected Play Slick configuration
 * @param ec ExecutionContext for asynchronous DB operations
 */
@Singleton
class EquipmentAllocationRepository @Inject()(
                                               protected val dbConfigProvider: DatabaseConfigProvider
                                             )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile] {

  import profile.api._

  /**
   * Slick mapping to convert between:
   *   - Scala `Instant`
   *   - MySQL `TIMESTAMP`
   */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /**
   * Internal Slick representation of an allocation DB row.
   *
   * Not exposed externally; converted to [[EquipmentAllocation]].
   */
  private case class AllocationRow(
                                    allocationId: Int,
                                    equipmentId: Int,
                                    employeeId: Int,
                                    allocatedByEmployeeId: Int,
                                    purpose: String,
                                    allocatedAt: Instant,
                                    expectedReturnAt: Instant,
                                    returnedAt: Option[Instant],
                                    conditionOnReturn: Option[String],
                                    returnNotes: Option[String],
                                    status: String,
                                    createdAt: Instant,
                                    updatedAt: Instant,
                                    isDeleted: Boolean,
                                    deletedAt: Option[Instant]
                                  )

  // ---------------------------------------------------------------------------
  // TABLE DEFINITIONS
  // ---------------------------------------------------------------------------

  /**
   * Slick table mapping for `equipment_allocations`.
   */
  private class AllocationsTable(tag: Tag) extends Table[AllocationRow](tag, "equipment_allocations") {
    def allocationId           = column[Int]("allocation_id", O.PrimaryKey, O.AutoInc)
    def equipmentId            = column[Int]("equipment_id")
    def employeeId             = column[Int]("employee_id")
    def allocatedByEmployeeId  = column[Int]("allocated_by_employee_id")
    def purpose                = column[String]("purpose")
    def allocatedAt            = column[Instant]("allocated_at")
    def expectedReturnAt       = column[Instant]("expected_return_at")
    def returnedAt             = column[Option[Instant]]("returned_at")
    def conditionOnReturn      = column[Option[String]]("condition_on_return")
    def returnNotes            = column[Option[String]]("return_notes")
    def status                 = column[String]("status")
    def createdAt              = column[Instant]("created_at")
    def updatedAt              = column[Instant]("updated_at")
    def isDeleted              = column[Boolean]("is_deleted")
    def deletedAt              = column[Option[Instant]]("deleted_at")

    def * =
      (allocationId, equipmentId, employeeId, allocatedByEmployeeId, purpose, allocatedAt,
        expectedReturnAt, returnedAt, conditionOnReturn, returnNotes, status,
        createdAt, updatedAt, isDeleted, deletedAt).mapTo[AllocationRow]
  }

  private val allocations = TableQuery[AllocationsTable]

  /** Slick table for joining equipment details. */
  private class EquipmentItemsTable(tag: Tag) extends Table[EquipmentItem](tag, "equipment_items") {
    def equipmentId   = column[Int]("equipment_id", O.PrimaryKey)
    def assetTag      = column[String]("asset_tag")
    def serialNumber  = column[String]("serial_number")
    def typeId        = column[Int]("type_id")
    def location      = column[Option[String]]("location")
    def status        = column[String]("status")
    def condition     = column[String]("condition")
    def createdAt     = column[Instant]("created_at")
    def updatedAt     = column[Instant]("updated_at")
    def isDeleted     = column[Boolean]("is_deleted")
    def deletedAt     = column[Option[Instant]]("deleted_at")

    def * = (equipmentId, assetTag, serialNumber, typeId, location, status, condition, createdAt, updatedAt, isDeleted, deletedAt).mapTo[EquipmentItem]
  }

  private val equipmentItems = TableQuery[EquipmentItemsTable]

  /** Slick table for joining employee (assignee / allocatedBy). */
  private class EmployeesTable(tag: Tag) extends Table[Employee](tag, "employees") {
    def employeeId  = column[Int]("employee_id", O.PrimaryKey)
    def userId      = column[Int]("user_id")
    def department  = column[String]("department")
    def designation = column[Option[String]]("designation")
    def isActive    = column[Boolean]("is_active")
    def createdAt   = column[Instant]("created_at")
    def updatedAt   = column[Instant]("updated_at")
    def isDeleted   = column[Boolean]("is_deleted")
    def deletedAt   = column[Option[Instant]]("deleted_at")

    def * = (employeeId, userId, department, designation, isActive, createdAt, updatedAt, isDeleted, deletedAt).mapTo[Employee]
  }

  private val employees = TableQuery[EmployeesTable]

  // ---------------------------------------------------------------------------
  // DOMAIN MAPPING + COMBINED DTO
  // ---------------------------------------------------------------------------

  /**
   * Combined details object containing:
   *   - Allocation
   *   - Equipment item (if exists)
   *   - Employee who received item
   *   - Employee who allocated item
   */
  case class AllocationDetails(
                                allocation: EquipmentAllocation,
                                equipment: Option[EquipmentItem],
                                employee: Option[Employee],
                                allocatedBy: Option[Employee]
                              )

  /**
   * Convert internal DB row model → domain model [[EquipmentAllocation]].
   */
  private def toModel(r: AllocationRow): EquipmentAllocation =
    EquipmentAllocation(
      allocationId          = r.allocationId,
      equipmentId           = r.equipmentId,
      employeeId            = r.employeeId,
      allocatedByEmployeeId = r.allocatedByEmployeeId,
      purpose               = r.purpose,
      allocatedAt           = r.allocatedAt,
      expectedReturnAt      = r.expectedReturnAt,
      returnedAt            = r.returnedAt,
      conditionOnReturn     = r.conditionOnReturn,
      returnNotes           = r.returnNotes,
      status                = r.status,
      createdAt             = r.createdAt,
      updatedAt             = r.updatedAt,
      isDeleted             = r.isDeleted,
      deletedAt             = r.deletedAt
    )

  /**
   * Fetch a single allocation by ID (excluding soft-deleted).
   */
  def findById(id: Int): Future[Option[EquipmentAllocation]] =
    db.run(
      allocations
        .filter(a => a.allocationId === id && a.isDeleted === false)
        .result
        .headOption
    ).map(_.map(toModel))

  /**
   * Retrieve the currently active allocation for a given equipment item.
   *
   * @param equipmentId ID of the equipment
   * @return None if equipment is free or no active allocation exists.
   */
  def findActiveByEquipment(equipmentId: Int): Future[Option[EquipmentAllocation]] =
    db.run(
      allocations
        .filter(a => a.isDeleted === false && a.equipmentId === equipmentId && a.status === "ACTIVE")
        .result.headOption
    ).map(_.map(toModel))

  /**
   * Return all overdue allocations (where expectedReturnAt < now and return has not occurred).
   *
   * This is used primarily by ReminderActor.
   */
  def findOverdue(now: Instant): Future[Seq[EquipmentAllocation]] = {
    val candidateQuery = allocations.filter(a => a.isDeleted === false && a.status === "ACTIVE")
    db.run(candidateQuery.result).map { rows =>
      rows.filter(r => r.expectedReturnAt.isBefore(now) && r.returnedAt.isEmpty)
        .map(toModel)
    }
  }

  /**
   * List all non-deleted allocations.
   */
  def listAllAllocations(): Future[Seq[EquipmentAllocation]] =
    db.run(allocations.filter(_.isDeleted === false).result).map(_.map(toModel))

  /**
   * Returns full joined allocation details including:
   *  - Equipment
   *  - Assignee employee
   *  - Allocated-by employee
   *
   * Used for admin dashboards and audit-friendly views.
   */
  def listAllAllocationDetails(): Future[Seq[AllocationDetails]] = {
    val baseJoin =
      allocations
        .filter(_.isDeleted === false)
        .joinLeft(equipmentItems).on(_.equipmentId === _.equipmentId)
        .joinLeft(employees).on { case ((alloc, _), emp) => alloc.employeeId === emp.employeeId }
        .joinLeft(employees).on { case (((alloc, _), _), byEmp) => alloc.allocatedByEmployeeId === byEmp.employeeId }

    val q = baseJoin.map { case (((allocRow, equipOpt), empOpt), byEmpOpt) =>
      (allocRow, equipOpt, empOpt, byEmpOpt)
    }

    db.run(q.result).map { rows =>
      rows.map { case (allocRow, equipOpt, empOpt, byEmpOpt) =>
        AllocationDetails(
          allocation = toModel(allocRow),
          equipment = equipOpt,
          employee = empOpt,
          allocatedBy = byEmpOpt
        )
      }
    }
  }

  /**
   * Create a new allocation record.
   *
   * Automatically:
   *   - Generates ID
   *   - Sets createdAt / updatedAt timestamps
   *   - Sets isDeleted = false
   */
  def create(a: EquipmentAllocation): Future[Int] = {
    val now = Instant.now()
    val row = AllocationRow(
      allocationId = 0,
      equipmentId = a.equipmentId,
      employeeId = a.employeeId,
      allocatedByEmployeeId = a.allocatedByEmployeeId,
      purpose = a.purpose,
      allocatedAt = a.allocatedAt,
      expectedReturnAt = a.expectedReturnAt,
      returnedAt = a.returnedAt,
      conditionOnReturn = a.conditionOnReturn,
      returnNotes = a.returnNotes,
      status = a.status,
      createdAt = now,
      updatedAt = now,
      isDeleted = false,
      deletedAt = None
    )

    db.run((allocations returning allocations.map(_.allocationId)) += row)
  }

  /**
   * Mark an allocation as returned and capture condition + notes.
   *
   * Updates:
   *   - returnedAt
   *   - conditionOnReturn
   *   - returnNotes
   *   - status = "RETURNED"
   */
  def markReturned(
                    allocationId: Int,
                    returnedAt: Instant,
                    conditionOnReturn: String,
                    returnNotes: Option[String]
                  ): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- allocations.filter(a => a.allocationId === allocationId && a.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(
            returnedAt = Some(returnedAt),
            conditionOnReturn = Some(conditionOnReturn),
            returnNotes = returnNotes,
            status = "RETURNED",
            updatedAt = now
          )
          allocations.filter(_.allocationId === allocationId).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }

  /**
   * Mark an allocation as overdue.
   *
   * Used by:
   *   - ReminderActor
   *   - Scheduled maintenance jobs
   */
  def markOverdue(allocationId: Int): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- allocations.filter(a => a.allocationId === allocationId && a.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(status = "OVERDUE", updatedAt = now)
          allocations.filter(_.allocationId === allocationId).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }

  /**
   * Soft-delete an allocation (preserves history).
   *
   * Sets:
   *   - isDeleted = true
   *   - deletedAt = now
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- allocations.filter(a => a.allocationId === id && a.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(isDeleted = true, deletedAt = Some(now), updatedAt = now)
          allocations.filter(_.allocationId === id).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }
}
