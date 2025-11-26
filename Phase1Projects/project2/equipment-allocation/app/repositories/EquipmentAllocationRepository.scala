package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging
import models._
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for performing CRUD operations and queries against the
 * `equipment_allocations` table and related joined reads.
 *
 * Responsibilities:
 *  - map between database rows and domain models ([[EquipmentAllocation]], [[EquipmentItem]], [[Employee]])
 *  - provide asynchronous methods to create allocations, mark returns/overdues,
 *    soft-delete allocations, and fetch allocation details
 *
 * Each public method attaches a `.recover` handler to log unexpected database
 * errors and re-throw (so upper layers — controllers/services — can translate
 * the failure into an HTTP response). Logging is performed here to capture DB
 * level context.
 *
 * Note: Uses Slick `MySQLProfile` and Play's `DatabaseConfigProvider`.
 *
 * @param dbConfigProvider Play database config provider
 * @param ec                ExecutionContext for running DB actions
 */
@Singleton
class EquipmentAllocationRepository @Inject()(
                                               protected val dbConfigProvider: DatabaseConfigProvider
                                             )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile]
    with Logging {

  import profile.api._

  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  private def tsLit(i: Instant): LiteralColumn[Timestamp] = LiteralColumn(Timestamp.from(i))

  /**
   * Internal Slick row representation for the `equipment_allocations` table.
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

  /**
   * Slick table mapping for allocations.
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

  /**
   * Minimal Slick mapping for equipment_items table used in joins.
   * Maps directly to the domain model [[EquipmentItem]] via `.mapTo`.
   */
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

  /**
   * Minimal Slick mapping for employees table used in joins.
   * Maps directly to the domain model [[Employee]] via `.mapTo`.
   */
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

  /**
   * Aggregated allocation details composed of allocation + optional equipment + employee info.
   *
   * @param allocation allocation model
   * @param equipment optional equipment item
   * @param employee optional employee (who uses)
   * @param allocatedBy optional employee who allocated
   */
  case class AllocationDetails(
                                allocation: EquipmentAllocation,
                                equipment: Option[EquipmentItem],
                                employee: Option[Employee],
                                allocatedBy: Option[Employee]
                              )

  /**
   * Convert a DB row to a domain model.
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
   * Find allocation by id (non-deleted).
   *
   * @param id allocation id
   * @return Future[Option[EquipmentAllocation]]
   */
  def findById(id: Int): Future[Option[EquipmentAllocation]] =
    db.run(
      allocations
        .filter(a => a.allocationId === id && a.isDeleted === false)
        .result
        .headOption
    ).map(_.map(toModel)).recover { case NonFatal(ex) =>
      logger.error(s"Error in findById(id=$id)", ex)
      throw ex
    }

  /**
   * Find an ACTIVE allocation for a given equipment id (non-deleted).
   *
   * @param equipmentId equipment id
   * @return Future[Option[EquipmentAllocation]]
   */
  def findActiveByEquipment(equipmentId: Int): Future[Option[EquipmentAllocation]] =
    db.run(
      allocations
        .filter(a => a.isDeleted === false && a.equipmentId === equipmentId && a.status === "ACTIVE")
        .result.headOption
    ).map(_.map(toModel)).recover { case NonFatal(ex) =>
      logger.error(s"Error in findActiveByEquipment(equipmentId=$equipmentId)", ex)
      throw ex
    }

  /**
   * Find overdue allocations as of the supplied `now` instant.
   *
   * Note: This implementation fetches candidate ACTIVE allocations then filters
   * in-memory by expectedReturnAt < now and returnedAt.isEmpty. This preserves
   * behavior from earlier code; you may push the predicate into the DB query
   * if needed for performance.
   *
   * @param now reference instant to compare expectedReturnAt
   * @return Future[Seq[EquipmentAllocation]]
   */
  def findOverdue(now: Instant): Future[Seq[EquipmentAllocation]] = {
    val candidateQuery = allocations.filter(a => a.isDeleted === false && a.status === "ACTIVE")
    db.run(candidateQuery.result).map { rows =>
      rows.filter { r =>
        r.expectedReturnAt.isBefore(now) && r.returnedAt.isEmpty
      }.map(toModel)
    }.recover { case NonFatal(ex) =>
      logger.error(s"Error in findOverdue(now=$now)", ex)
      throw ex
    }
  }

  /**
   * List all allocations (non-deleted).
   *
   * @return Future[Seq[EquipmentAllocation]]
   */
  def listAllAllocations(): Future[Seq[EquipmentAllocation]] =
    db.run(
      allocations.filter(_.isDeleted === false).result
    ).map(_.map(toModel)).recover { case NonFatal(ex) =>
      logger.error("Error in listAllAllocations()", ex)
      throw ex
    }

  /**
   * List all allocation details joined with equipment and employee info.
   *
   * @return Future[Seq[AllocationDetails]]
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
      rows.map {
        case (allocRow, equipOpt, empOpt, byEmpOpt) =>
          val allocModel = toModel(allocRow)
          AllocationDetails(
            allocation = allocModel,
            equipment = equipOpt,
            employee = empOpt,
            allocatedBy = byEmpOpt
          )
      }
    }.recover { case NonFatal(ex) =>
      logger.error("Error in listAllAllocationDetails()", ex)
      throw ex
    }
  }

  /**
   * Create a new allocation row and return the generated allocation id.
   *
   * @param a allocation model (allocationId ignored)
   * @return Future[Int] generated allocationId
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

    db.run((allocations returning allocations.map(_.allocationId)) += row).recover { case NonFatal(ex) =>
      logger.error(s"Error creating allocation for equipmentId=${a.equipmentId}", ex)
      throw ex
    }
  }

  /**
   * Mark an allocation as returned and update return metadata.
   *
   * @param allocationId allocation id
   * @param returnedAt timestamp of return
   * @param conditionOnReturn condition string (e.g., GOOD, DAMAGED)
   * @param returnNotes optional notes
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in markReturned(allocationId=$allocationId)", ex)
      throw ex
    }
  }

  /**
   * Mark an allocation as overdue (status = "OVERDUE").
   *
   * @param allocationId allocation id
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in markOverdue(allocationId=$allocationId)", ex)
      throw ex
    }
  }

  /**
   * Soft-delete an allocation by marking `isDeleted = true` and setting `deletedAt`.
   *
   * @param id allocation id
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in softDelete(allocationId=$id)", ex)
      throw ex
    }
  }
}
