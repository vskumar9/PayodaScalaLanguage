package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import java.time.Instant

import models.User
import services.AuthService
import repositories.UserRepository
import security.RBAC
import utils.{ValidationUtil, UserErrorUtil}
import utils.UserErrorUtil.FieldError

import java.sql.SQLException

/**
 * Controller for user management (CRUD) and user creation via AuthService.
 *
 * Provides endpoints to list, fetch, create, update and delete users.
 * All endpoints are protected with RBAC and require the "Admin" role.
 *
 * Each asynchronous repository/service call includes `.recover` handlers to
 * return tidy JSON error responses and to log unexpected failures.
 *
 * Routes:
 *   GET    /api/users           -> listUsers
 *   GET    /api/users/:id       -> getUser
 *   POST   /api/users           -> createUser
 *   PUT    /api/users/:id       -> updateUser
 *   DELETE /api/users/:id       -> deleteUser
 *
 * @param cc Controller components
 * @param authService Service responsible for user creation and password hashing
 * @param userRepository Repository for user persistence operations
 * @param rbac RBAC helper for role checks
 * @param ec ExecutionContext for futures
 */
@Singleton
class UserController @Inject()(
                                cc: ControllerComponents,
                                authService: AuthService,
                                userRepository: UserRepository,
                                rbac: RBAC
                              )(implicit ec: ExecutionContext)
  extends AbstractController(cc) {

  /** ISO-8601 formatter for Instant values used in User model JSON. */
  implicit val instantFormat: Format[Instant] = new Format[Instant] {
    def writes(i: Instant): JsValue = JsString(i.toString)
    def reads(json: JsValue): JsResult[Instant] = json.validate[String].map(Instant.parse)
  }

  /** JSON formatter for User domain model. */
  implicit val userFormat: OFormat[User] = Json.format[User]

  /**
   * Request payload for creating a user.
   *
   * @param username chosen username
   * @param password plaintext password (will be hashed by AuthService)
   * @param fullName full display name
   * @param email contact email
   * @param phone optional phone number
   * @param isActive whether the user is active
   * @param roles list of roles assigned to the user
   */
  case class CreateUserRequest(
                                username: String,
                                password: String,
                                fullName: String,
                                email: String,
                                phone: Option[String],
                                isActive: Boolean,
                                roles: List[String]
                              )

  /**
   * Request payload for updating an existing user.
   *
   * @param fullName full display name
   * @param email contact email
   * @param phone optional phone number
   * @param isActive whether the user is active
   * @param roles list of roles assigned to the user
   */
  case class UpdateUserRequest(
                                fullName: String,
                                email: String,
                                phone: Option[String],
                                isActive: Boolean,
                                roles: List[String]
                              )

  implicit val createUserReads: OFormat[CreateUserRequest] = Json.format[CreateUserRequest]
  implicit val updateUserReads: OFormat[UpdateUserRequest] = Json.format[UpdateUserRequest]

  private val AdminOnly = Set("Admin")

  /**
   * Validate CreateUserRequest and return a sequence of field errors.
   *
   * @param dto create request DTO
   * @return sequence of field validation errors
   */
  private def validateCreateUser(dto: CreateUserRequest): Seq[FieldError] = {
    val errors = collection.mutable.ListBuffer[FieldError]()

    if (dto.username.trim.isEmpty)
      errors += FieldError("username", "Username is required")

    if (dto.fullName.trim.isEmpty)
      errors += FieldError("fullName", "Full name is required")

    ValidationUtil.validatePassword(dto.password)
      .foreach(msg => errors += FieldError("password", msg))

    ValidationUtil.validateEmail(dto.email)
      .foreach(msg => errors += FieldError("email", msg))

    ValidationUtil.validatePhone(dto.phone)
      .foreach(msg => errors += FieldError("phone", msg))

    if (dto.roles.isEmpty)
      errors += FieldError("roles", "At least one role is required")

    errors.toList
  }

  /**
   * Validate UpdateUserRequest and return a sequence of field errors.
   *
   * @param dto update request DTO
   * @return sequence of field validation errors
   */
  private def validateUpdateUser(dto: UpdateUserRequest): Seq[FieldError] = {
    val errors = collection.mutable.ListBuffer[FieldError]()

    if (dto.fullName.trim.isEmpty)
      errors += FieldError("fullName", "Full name is required")

    ValidationUtil.validateEmail(dto.email)
      .foreach(msg => errors += FieldError("email", msg))

    ValidationUtil.validatePhone(dto.phone)
      .foreach(msg => errors += FieldError("phone", msg))

    if (dto.roles.isEmpty)
      errors += FieldError("roles", "At least one role is required")

    errors.toList
  }

  /**
   * List all users.
   *
   * Requires Admin role.
   *
   * @return 200 OK with JSON array of users or 500 on failure
   */
  def listUsers: Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      userRepository.findAll()
        .map(users => Ok(Json.toJson(users)))
        .recover { case NonFatal(ex) =>
          InternalServerError(Json.obj("error" -> "Failed to list users", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Get a single user by id.
   *
   * Requires Admin role.
   *
   * @param id user id
   * @return 200 OK with user JSON, 404 if missing, 500 on error
   */
  def getUser(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      userRepository.findById(id)
        .map {
          case Some(user) => Ok(Json.toJson(user))
          case None       => NotFound(Json.obj("error" -> s"User $id not found"))
        }
        .recover { case NonFatal(ex) =>
          InternalServerError(Json.obj("error" -> "Failed to fetch user", "details" -> ex.getMessage))
        }
    }
  }

  /**
   * Create a new user.
   *
   * Requires Admin role. Expects CreateUserRequest JSON payload.
   *
   * On success returns 201 Created with new userId.
   * Returns 400 for validation or business errors, 500 for unexpected failures.
   */
  def createUser: Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[CreateUserRequest](request, AdminOnly) { (_adminUser, dto) =>
      val fieldErrors = validateCreateUser(dto)

      if (fieldErrors.nonEmpty) {
        Future.successful(UserErrorUtil.validationErrorResult(fieldErrors))
      } else {
        val now = Instant.now()
        val user = User(
          userId = 0,
          username = dto.username.trim,
          passwordHash = dto.password, // will be hashed by AuthService
          fullName = dto.fullName.trim,
          email = dto.email.trim,
          phone = dto.phone.map(_.trim),
          isActive = dto.isActive,
          createdAt = now,
          updatedAt = now,
          roles = dto.roles
        )

        authService.createUser(user).map { newId =>
          Created(Json.obj("userId" -> newId))
        }.recover {
          case e: IllegalArgumentException =>
            BadRequest(Json.obj("error" -> e.getMessage))
          case e: SQLException =>
            UserErrorUtil.handleSqlException(e)
          case NonFatal(ex) =>
            InternalServerError(Json.obj("error" -> "Failed to create user", "details" -> ex.getMessage))
        }
      }
    }
  }

  /**
   * Update an existing user.
   *
   * Requires Admin role. Expects UpdateUserRequest JSON payload.
   *
   * @param id user id
   * @return 200 OK with updated user, 404 if not found, 400/500 on error
   */
  def updateUser(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    rbac.withRolesAndJsonBody[UpdateUserRequest](request, AdminOnly) { (_adminUser, dto) =>
      val fieldErrors = validateUpdateUser(dto)

      if (fieldErrors.nonEmpty) {
        Future.successful(UserErrorUtil.validationErrorResult(fieldErrors))
      } else {
        userRepository.findById(id).flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              fullName = dto.fullName.trim,
              email = dto.email.trim,
              phone = dto.phone.map(_.trim),
              isActive = dto.isActive,
              roles = dto.roles,
              updatedAt = Instant.now()
            )

            userRepository.update(updated).map {
              case 1 => Ok(Json.toJson(updated))
              case _ => InternalServerError(Json.obj("error" -> "Failed to update user"))
            }.recover {
              case e: IllegalArgumentException =>
                BadRequest(Json.obj("error" -> e.getMessage))
              case e: SQLException =>
                UserErrorUtil.handleSqlException(e)
              case NonFatal(ex) =>
                InternalServerError(Json.obj("error" -> "Failed to update user", "details" -> ex.getMessage))
            }

          case None =>
            Future.successful(NotFound(Json.obj("error" -> s"User $id not found")))
        }.recover { case NonFatal(ex) =>
          InternalServerError(Json.obj("error" -> "Failed to update user", "details" -> ex.getMessage))
        }
      }
    }
  }

  /**
   * Delete a user by id.
   *
   * Requires Admin role.
   *
   * @param id user id
   * @return 204 NoContent on success, 404 if not found, 500 on error
   */
  def deleteUser(id: Int): Action[AnyContent] = Action.async { request =>
    rbac.withRoles(request, AdminOnly) { _ =>
      userRepository.delete(id)
        .map {
          case rows if rows > 0 => NoContent
          case _                => NotFound(Json.obj("error" -> s"User $id not found"))
        }
        .recover { case NonFatal(ex) =>
          InternalServerError(Json.obj("error" -> "Failed to delete user", "details" -> ex.getMessage))
        }
    }
  }
}
