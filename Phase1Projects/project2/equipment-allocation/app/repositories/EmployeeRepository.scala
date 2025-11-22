package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging

import models.Employee
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import java.time.Instant
import java.sql.Timestamp

/**
 * Repository for CRUD operations on Employee records using Slick.
 *
 * Responsibilities:
 *  - map domain [[models.Employee]] to/from database rows
 *  - provide asynchronous methods to find, create, update and soft-delete employees
 *
 * All public methods include `.recover` handlers which log unexpected errors
 * and re-raise them so higher layers (controllers/services) can decide how to
 * convert failures into HTTP responses. Logging is intentionally done here to
 * capture DB-level errors with context.
 *
 * @param dbConfigProvider Play's database config provider (Slick)
 * @param ec ExecutionContext used to run asynchronous DB actions
 */
@Singleton
class EmployeeRepository @Inject()(
                                    dbConfigProvider: DatabaseConfigProvider
                                  )(implicit ec: ExecutionContext)
  extends Logging {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]
  private val db = dbConfig.db

  protected val profile: JdbcProfile = dbConfig.profile
  import profile.api._

  /**
   * Internal row representation mapped to the `employees` table.
   *
   * This mirrors the DB schema and is used for Slick mapping.
   */
  private case class EmployeeRow(
                                  employeeId: Int,
                                  userId: Int,
                                  department: String,
                                  designation: Option[String],
                                  isActive: Boolean,
                                  createdAt: Instant,
                                  updatedAt: Instant,
                                  isDeleted: Boolean,
                                  deletedAt: Option[Instant]
                                )

  /**
   * Slick table definition for the `employees` table.
   *
   * Columns:
   *  - employee_id (PK, auto-increment)
   *  - user_id
   *  - department
   *  - designation
   *  - is_active
   *  - created_at
   *  - updated_at
   *  - is_deleted
   *  - deleted_at
   */
  private class EmployeesTable(tag: Tag) extends Table[EmployeeRow](tag, "employees") {
    def employeeId  = column[Int]("employee_id", O.PrimaryKey, O.AutoInc)
    def userId      = column[Int]("user_id")
    def department  = column[String]("department")
    def designation = column[Option[String]]("designation")
    def isActive    = column[Boolean]("is_active")
    def createdAt   = column[Instant]("created_at")
    def updatedAt   = column[Instant]("updated_at")
    def isDeleted   = column[Boolean]("is_deleted")
    def deletedAt   = column[Option[Instant]]("deleted_at")

    def * =
      (employeeId, userId, department, designation, isActive,
        createdAt, updatedAt, isDeleted, deletedAt)
        .mapTo[EmployeeRow]
  }

  private val employees = TableQuery[EmployeesTable]

  /**
   * Slick mapping from EmployeeRow -> Employee domain model.
   *
   * @param r EmployeeRow instance
   * @return Employee domain object
   */
  private def toEmployee(r: EmployeeRow): Employee =
    Employee(
      employeeId  = r.employeeId,
      userId      = r.userId,
      department  = r.department,
      designation = r.designation,
      isActive    = r.isActive,
      createdAt   = r.createdAt,
      updatedAt   = r.updatedAt,
      isDeleted   = r.isDeleted,
      deletedAt   = r.deletedAt
    )

  /**
   * Find an employee by its primary id.
   *
   * Only returns non-deleted rows.
   *
   * @param id employee id
   * @return Future[Option[Employee]] - Some(employee) if found, None otherwise
   */
  def findById(id: Int): Future[Option[Employee]] =
    db.run(
      employees
        .filter(e => e.employeeId === id && e.isDeleted === false)
        .result
        .headOption
    ).map(_.map(toEmployee)).recover { case NonFatal(ex) =>
      logger.error(s"Error in findById(id=$id)", ex)
      throw ex
    }

  /**
   * Find an employee by the associated userId.
   *
   * Only returns non-deleted rows.
   *
   * @param userId linked user id
   * @return Future[Option[Employee]]
   */
  def findByUserId(userId: Int): Future[Option[Employee]] =
    db.run(
      employees
        .filter(e => e.userId === userId && e.isDeleted === false)
        .result
        .headOption
    ).map(_.map(toEmployee)).recover { case NonFatal(ex) =>
      logger.error(s"Error in findByUserId(userId=$userId)", ex)
      throw ex
    }

  /**
   * Retrieve all non-deleted employees.
   *
   * @return Future[Seq[Employee]]
   */
  def findAll(): Future[Seq[Employee]] =
    db.run(
      employees
        .filter(_.isDeleted === false)
        .result
    ).map(_.map(toEmployee)).recover { case NonFatal(ex) =>
      logger.error("Error in findAll()", ex)
      throw ex
    }

  /**
   * Create a new employee record.
   *
   * The returned Future contains the generated employee id (auto-increment).
   *
   * @param employee Employee domain object (employeeId field is ignored)
   * @return Future[Int] generated employeeId
   */
  def create(employee: Employee): Future[Int] = {
    val now = Instant.now()
    val row = EmployeeRow(
      employeeId = 0,
      userId = employee.userId,
      department = employee.department,
      designation = employee.designation,
      isActive = employee.isActive,
      createdAt = now,
      updatedAt = now,
      isDeleted = false,
      deletedAt = None
    )

    db.run((employees returning employees.map(_.employeeId)) += row).recover { case NonFatal(ex) =>
      logger.error(s"Error creating employee for userId=${employee.userId}", ex)
      throw ex
    }
  }

  /**
   * Update an existing employee.
   *
   * If the employee does not exist or is deleted, returns 0.
   *
   * @param employee Employee with updated fields (employeeId must be set)
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
   */
  def update(employee: Employee): Future[Int] = {
    val now = Instant.now()

    val action = for {
      maybeExisting <- employees.filter(e => e.employeeId === employee.employeeId && e.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(
            department = employee.department,
            designation = employee.designation,
            isActive = employee.isActive,
            updatedAt = now
          )
          employees.filter(_.employeeId === employee.employeeId).update(updated)
        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error updating employee ${employee.employeeId}", ex)
      throw ex
    }
  }

  /**
   * Soft-delete an employee by marking `isDeleted = true` and setting `deletedAt`.
   *
   * Returns number of rows updated (1 if deleted, 0 if not found).
   *
   * @param id employee id to soft-delete
   * @return Future[Int]
   */
  def delete(id: Int): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- employees.filter(e => e.employeeId === id && e.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(isDeleted = true, deletedAt = Some(now), updatedAt = now)
          employees.filter(_.employeeId === id).update(updated)
        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error deleting(employeeId=$id)", ex)
      throw ex
    }
  }
}
