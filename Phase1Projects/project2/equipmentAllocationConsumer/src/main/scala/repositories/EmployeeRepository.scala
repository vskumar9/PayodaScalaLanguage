package repositories

import models.Employee
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import java.sql.Timestamp
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing `Employee` records using Slick.
 *
 * This repository encapsulates all database operations related to employees,
 * including creation, lookup, update, and soft deletion. It maps rows from the
 * `employees` table into the domain model [[models.Employee]] and hides all SQL/Slick
 * details from calling services, controllers, or actors.
 *
 * ### Key Features
 * - Uses **soft-delete** semantics (`isDeleted`, `deletedAt`).
 * - Provides lookups by `employeeId`, `userId`, and bulk `findAll`.
 * - Automatically manages `createdAt` and `updatedAt` timestamps on create/update.
 * - Converts `Instant` ↔ SQL `Timestamp` via Slick MappedColumnType.
 *
 * ### Typical Usage
 * Used by:
 *   - AllocationActor
 *   - MaintenanceActor
 *   - ReminderActor
 *   - Controllers for employee management
 *
 * @param dbConfigProvider Play Slick database configuration provider.
 * @param ec               ExecutionContext for async DB operations.
 */
@Singleton
class EmployeeRepository @Inject()(
                                    dbConfigProvider: DatabaseConfigProvider
                                  )(implicit ec: ExecutionContext) {

  /** Slick database configuration (driver + database). */
  private val dbConfig = dbConfigProvider.get[JdbcProfile]

  /** Slick database handle used to execute DBIO actions. */
  private val db = dbConfig.db

  /** Slick JDBC profile (Postgres, MySQL, H2, etc.). */
  protected val profile: JdbcProfile = dbConfig.profile
  import profile.api._

  /**
   * Implicit Slick column mapping for converting between:
   *   - Scala `Instant`
   *   - SQL `TIMESTAMP`
   *
   * This allows seamless reading/writing of `Instant` in Slick queries.
   */
  implicit val instantColumnType: profile.api.BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /**
   * Internal Slick row representation of an Employee.
   *
   * This mirrors the database schema but is not exposed outside the repository.
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
   * Slick table mapping for the `employees` table.
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

    /** Maps DB columns → EmployeeRow case class. */
    def * =
      (employeeId, userId, department, designation, isActive,
        createdAt, updatedAt, isDeleted, deletedAt)
        .mapTo[EmployeeRow]
  }

  /** Slick table query handle for `employees`. */
  private val employees = TableQuery[EmployeesTable]

  /**
   * Convert an internal `EmployeeRow` into the domain model [[Employee]].
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
   * Find an employee by primary key.
   *
   * Filters out soft-deleted rows (`isDeleted = false`).
   *
   * @param id Employee ID.
   * @return Future(Some(Employee)) if found, otherwise Future(None).
   */
  def findById(id: Int): Future[Option[Employee]] =
    db.run(
      employees
        .filter(e => e.employeeId === id && e.isDeleted === false)
        .result
        .headOption
    ).map(_.map(toEmployee))

  /**
   * Find an employee by their linked user account ID.
   *
   * Useful for login/profile lookup flows.
   *
   * @param userId Foreign key linking employee to a user.
   * @return Future(Some(Employee)) if exists, otherwise None.
   */
  def findByUserId(userId: Int): Future[Option[Employee]] =
    db.run(
      employees
        .filter(e => e.userId === userId && e.isDeleted === false)
        .result
        .headOption
    ).map(_.map(toEmployee))

  /**
   * Retrieve all active (non–soft-deleted) employees.
   *
   * @return Future sequence of Employee models.
   */
  def findAll(): Future[Seq[Employee]] =
    db.run(
      employees
        .filter(_.isDeleted === false)
        .result
    ).map(_.map(toEmployee))

  /**
   * Create a new employee entry.
   *
   * Automatically:
   *   - Generates new `employeeId` via auto-increment
   *   - Sets `createdAt` and `updatedAt` timestamps to `Instant.now()`
   *   - Ensures `isDeleted = false`
   *
   * @param employee Data used to create the employee (ignores given timestamps/ID).
   * @return Future containing the generated employee ID.
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

    db.run((employees returning employees.map(_.employeeId)) += row)
  }

  /**
   * Update an existing employee entry.
   *
   * Only updates:
   *   - department
   *   - designation
   *   - isActive flag
   *   - updatedAt timestamp
   *
   * If the employee does not exist or is soft-deleted, returns 0.
   *
   * @param employee Updated employee model.
   * @return Future[Int] number of affected rows (0 or 1).
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

    db.run(action.transactionally)
  }

  /**
   * Soft-delete an employee.
   *
   * Sets:
   *   - `isDeleted = true`
   *   - `deletedAt = now`
   *   - updates `updatedAt`
   *
   * Does not physically remove the row from the database.
   *
   * @param id Employee ID.
   * @return Future[Int] number of rows updated (0 if not found or already deleted).
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

    db.run(action.transactionally)
  }
}
