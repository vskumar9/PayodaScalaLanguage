package security

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.libs.json._

import repositories.UserRepository
import scala.util.Try

/**
 * Represents the authenticated user extracted from JWT token and DB lookup.
 *
 * @param id       User ID from the database
 * @param username Username of the authenticated user
 * @param roles    List of role names assigned to the user
 */
case class AuthenticatedUser(
                              id: Int,
                              username: String,
                              roles: List[String]
                            )

/**
 * Role-Based Access Control (RBAC) helper used to enforce permissions
 * on controller actions after JWT authentication.
 *
 * Responsibilities:
 *   - Extract the authenticated user’s ID from request attributes
 *   - Load the user from the database
 *   - Check if the user has any required roles
 *   - Provide the authenticated user object to the secured controller block
 *
 * Used across controllers to secure endpoints such as:
 * {{{
 * rbac.withRoles(request, Set("Admin")) { user =>
 *     ...
 * }
 * }}}
 *
 * Supports JSON validation through `withRolesAndJsonBody`.
 */
@Singleton
class RBAC @Inject()(
                      userRepository: UserRepository
                    )(implicit ec: ExecutionContext) {

  /**
   * Ensures the incoming request belongs to a user who has at least one of the required roles.
   *
   * Steps:
   *   1. Extract userId from `JwtAuthFilter` request attributes.
   *   2. Load the user from database.
   *   3. Verify if the user's roles intersect with required roles.
   *   4. If authorized, pass `AuthenticatedUser` to the controller logic.
   *
   * Error responses:
   *   - 401 Missing or invalid token userId
   *   - 401 User not found
   *   - 403 User lacks required roles
   *
   * @param request The incoming Play request
   * @param requiredRoles A set of role names required to access the resource
   * @param block Controller logic executed when authorization succeeds
   * @tparam A Body type of the request
   * @return Future of Play Result
   */
  def withRoles[A](
                    request: Request[A],
                    requiredRoles: Set[String]
                  )(block: AuthenticatedUser => Future[Result]): Future[Result] = {

    request.attrs.get(JwtAuthFilter.Attrs.UserId) match {
      case Some(userIdStr) =>
        Try(userIdStr.toInt).toOption match {
          case Some(userId) =>
            userRepository.findById(userId).flatMap {
              case Some(user) =>
                val hasRole = user.roles.exists(requiredRoles.contains)
                if (hasRole) {
                  val au = AuthenticatedUser(user.userId, user.username, user.roles)
                  block(au)
                } else {
                  Future.successful(
                    Results.Forbidden(
                      Json.obj(
                        "error"            -> "Forbidden",
                        "requiredRoles"    -> requiredRoles,
                        "currentUserId"    -> user.userId,
                        "currentUserRoles" -> user.roles
                      )
                    )
                  )
                }

              case None =>
                Future.successful(
                  Results.Unauthorized(Json.obj("error" -> "User not found"))
                )
            }

          case None =>
            Future.successful(
              Results.Unauthorized(Json.obj("error" -> "Invalid user id in token"))
            )
        }

      case None =>
        Future.successful(
          Results.Unauthorized(Json.obj("error" -> "Missing authenticated user"))
        )
    }
  }

  /**
   * Same as `withRoles` but also validates a JSON request body against a provided type.
   *
   * Behavior:
   *   - Runs RBAC validation first
   *   - Then validates request JSON using an implicit `Reads[T]`
   *   - If JSON is valid, calls the controller block with:
   *       (AuthenticatedUser, DTO)
   *
   * Error responses:
   *   - 401 Unauthorized (token/role issues)
   *   - 400 Invalid JSON payload
   *
   * Example Usage:
   * {{{
   * rbac.withRolesAndJsonBody[CreateUserDto](request, Set("Admin")) { (auth, dto) =>
   *     // use dto
   * }
   * }}}
   *
   * @param request The request containing JSON payload
   * @param requiredRoles Required roles for access
   * @param block Controller logic when both RBAC and JSON pass
   * @param reads Implicit JSON reader for DTO
   * @tparam T Type to deserialize JSON body into
   * @return Future of Play Result
   */
  def withRolesAndJsonBody[T](
                               request: Request[JsValue],
                               requiredRoles: Set[String]
                             )(
                               block: (AuthenticatedUser, T) => Future[Result]
                             )(implicit reads: Reads[T]): Future[Result] = {

    withRoles(request, requiredRoles) { authUser =>
      request.body.validate[T].fold(
        errors => {
          val msg = JsError.toJson(errors)
          Future.successful(
            Results.BadRequest(
              Json.obj(
                "error"   -> "Invalid request body",
                "details" -> msg
              )
            )
          )
        },
        dto => block(authUser, dto)
      )
    }
  }
}
