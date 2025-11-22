package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

import models.Employee
import repositories.EmployeeRepository
import security.RBAC

/**
 * Controller for managing employee records through CRUD operations.
 *
 * All endpoints are protected using RBAC (Role-Based Access Control) and
 * require the caller to have the **Admin** role.
 *
 * Includes global `.recover` handlers in each async operation to provide
 * clean JSON error responses instead of unhandled exceptions.
 *
 * Endpoints:
 *   - GET    /api/employees                     → list employees
 *   - GET    /api/employees/:id                 → get employee by ID
 *   - GET    /api/employees/byUser/:userId      → get employee by userId
 *   - POST   /api/employees                     → create employee
 *   - PUT    /api/employees/:id                 → update employee
 *   - DELETE /api/employees/:id                 → delete employee
 */
@Singleton
class EmployeeController @Inject()(
                                    cc: ControllerComponents,
                                    employeeRepository: EmployeeRepository,
                                    rbac: RBAC
                                  )(implicit ec: ExecutionContext)
  extends AbstractController(cc) {

  /** JSON formatter for Java Instant values. */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    override def writes(i: Instant): JsValue = JsString(i.toString)
    override def reads(json: JsValue): JsResult[Instant] =
      json.validate[String].map(Instant.parse)
  }

  /** JSON formatter for Employee model. */
  implicit val employeeFormat: OFormat[Employee] = Json.format[Employee]

  /**
   * Request body for creating a new employee.
   *
   * @param userId      ID of the associated user
   * @param department  Department name
   * @param designation Optional job title
   * @param isActive    Whether the employee is active
   */
  case class CreateEmployeeRequest(
                                    userId: Int,
                                    department: String,
                                    designation: Option[String],
                                    isActive: Boolean
                                  )

  /**
   * Request body for updating an employee.
   *
   * @param department  New department name
   * @param designation Updated job title
   * @param isActive    Updated active status
   */
  case class UpdateEmployeeRequest(
                                    department: String,
                                    designation: Option[String],
                                    isActive: Boolean
                                  )

  implicit val createEmployeeReads: OFormat[CreateEmployeeRequest] = Json.format[CreateEmployeeRequest]
  implicit val updateEmployeeReads: OFormat[UpdateEmployeeRequest] = Json.format[UpdateEmployeeRequest]

  private val AdminOnly = Set("Admin")

  /**
   * Lists all employees in the system.
   *
   * Requires admin access.
   *
   * @return 200 OK with JSON list of employees
   *         500 InternalServerError on repository failure
   */
  def listEmployees: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      employeeRepository
        .findAll()
        .map(employees => Ok(Json.toJson(employees)))
        .recover {
          case ex: Throwable =>
            InternalServerError(Json.obj("error" -> s"Failed to load employees: ${ex.getMessage}"))
        }
    }
  }

  /**
   * Retrieves an employee by their employeeId.
   *
   * @param id Employee ID
   * @return 200 OK with employee JSON
   *         404 NotFound if not found
   *         500 InternalServerError on failure
   */
  def getEmployee(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      employeeRepository
        .findById(id)
        .map {
          case Some(emp) => Ok(Json.toJson(emp))
          case None      => NotFound(Json.obj("error" -> s"Employee with id $id not found"))
        }
        .recover {
          case ex =>
            InternalServerError(Json.obj("error" -> s"Error retrieving employee: ${ex.getMessage}"))
        }
    }
  }

  /**
   * Retrieves an employee by their associated userId.
   *
   * @param userId Linked user ID
   * @return 200 OK with employee JSON
   *         404 NotFound if no employee exists
   *         500 InternalServerError on failure
   */
  def getByUserId(userId: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      employeeRepository
        .findByUserId(userId)
        .map {
          case Some(emp) => Ok(Json.toJson(emp))
          case None      => NotFound(Json.obj("error" -> s"No employee found for userId $userId"))
        }
        .recover {
          case ex =>
            InternalServerError(Json.obj("error" -> s"Error fetching employee: ${ex.getMessage}"))
        }
    }
  }

  /**
   * Creates a new employee record.
   *
   * Requires admin access.
   *
   * Example JSON:
   * {{{
   * {
   *   "userId": 1,
   *   "department": "Engineering",
   *   "designation": "Developer",
   *   "isActive": true
   * }
   * }}}
   *
   * @return 201 Created with new employeeId
   *         500 InternalServerError on repository failure
   */
  def createEmployee: Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[CreateEmployeeRequest](request, AdminOnly) { (_, dto) =>
      val now = Instant.now()

      val employee = Employee(
        employeeId  = 0,
        userId      = dto.userId,
        department  = dto.department,
        designation = dto.designation,
        isActive    = dto.isActive,
        createdAt   = now,
        updatedAt   = now
      )

      employeeRepository
        .create(employee)
        .map(newId => Created(Json.obj("employeeId" -> newId)))
        .recover {
          case ex =>
            InternalServerError(Json.obj("error" -> s"Failed to create employee: ${ex.getMessage}"))
        }
    }
  }

  /**
   * Updates an existing employee record.
   *
   * Requires admin access.
   *
   * @param id Employee ID
   * @return 200 OK with updated employee
   *         404 NotFound if employee doesn't exist
   *         500 InternalServerError on failure
   */
  def updateEmployee(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[UpdateEmployeeRequest](request, AdminOnly) { (_, dto) =>

      employeeRepository
        .findById(id)
        .flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              department  = dto.department,
              designation = dto.designation,
              isActive    = dto.isActive
            )

            employeeRepository
              .update(updated)
              .map {
                case 1 => Ok(Json.toJson(updated))
                case _ => InternalServerError(Json.obj("error" -> "Failed to update employee"))
              }

          case None =>
            Future.successful(NotFound(Json.obj("error" -> s"Employee with id $id not found")))
        }
        .recover {
          case ex =>
            InternalServerError(Json.obj("error" -> s"Error updating employee: ${ex.getMessage}"))
        }
    }
  }

  /**
   * Deletes an employee by ID.
   *
   * @param id Employee ID
   * @return 204 NoContent if successfully deleted
   *         404 NotFound if no employee exists
   *         500 InternalServerError on repository error
   */
  def deleteEmployee(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      employeeRepository
        .delete(id)
        .map {
          case rows if rows > 0 => NoContent
          case _                => NotFound(Json.obj("error" -> s"Employee $id not found"))
        }
        .recover {
          case ex =>
            InternalServerError(Json.obj("error" -> s"Failed to delete employee: ${ex.getMessage}"))
        }
    }
  }
}
