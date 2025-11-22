package security

import play.api.libs.json._
import play.api.mvc._
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import repositories.{UsersRepository, UserRolesRepository, RolesRepository}

/**
 * Represents an authenticated user along with their assigned roles.
 *
 * @param id       unique user identifier
 * @param username username of the authenticated user
 * @param roles    list of role names assigned to the user
 */
case class AuthenticatedUser(
                              id: Int,
                              username: String,
                              roles: List[String]
                            )

/**
 * Role-Based Access Control (RBAC) helper component.
 *
 * This class handles:
 *   - extracting the authenticated user ID from request attributes (set by [[JwtAuthFilter]])
 *   - loading the user's roles from the database
 *   - enforcing role-based authorization checks
 *   - validating JSON request bodies for protected endpoints
 *
 * It provides two main entry points:
 *
 *   1. `withRoles(...)`: Ensures the user has at least one required role before executing a block.
 *   2. `withRolesAndJsonBody(...)`: Same as above, but also validates and parses the JSON body.
 *
 * If authorization fails or the request body is invalid, an appropriate `Result` is returned.
 *
 * @param usersRepo       repository for users
 * @param userRolesRepo   repository for user → role mappings
 * @param rolesRepo       repository for role definitions
 * @param ec              execution context for asynchronous DB operations
 */
@Singleton
class RBAC @Inject()(
                      usersRepo: UsersRepository,
                      userRolesRepo: UserRolesRepository,
                      rolesRepo: RolesRepository
                    )(implicit ec: ExecutionContext) {

  /**
   * Safely extracts a user ID from a request attribute value.
   * JWT tokens store claims as strings; Play attributes may store them as string/int/long.
   *
   * @param attrValue attribute value from request
   * @return parsed integer user ID, if valid
   */
  private def extractUserIdFromAttr(attrValue: Any): Option[Int] = attrValue match {
    case i: Int    => Some(i)
    case s: String => Try(s.toInt).toOption
    case l: Long   => Try(l.toInt).toOption
    case _         => None
  }

  /**
   * Loads all role names assigned to a user.
   *
   * Performs two DB operations:
   *   1. fetch all user-role mappings
   *   2. fetch corresponding role names
   *
   * @param userId user identifier
   * @return list of role names assigned to the user
   */
  private def loadUserRoles(userId: Int): Future[List[String]] = {
    userRolesRepo.findByUser(userId).flatMap { userRoleSeq =>
      val roleIds = userRoleSeq.map(_.roleId).distinct

      // Fetch each role and extract role names
      Future.sequence(roleIds.map(id => rolesRepo.findById(id))).map { seqOpt =>
        seqOpt.flatMap(_.map(_.roleName)).toList
      }
    }
  }

  /**
   * Ensures that the authenticated user has at least one of the required roles.
   *
   * Steps:
   *   1. Reads the `userId` from request attributes set by [[JwtAuthFilter]].
   *   2. Loads the user's DB record.
   *   3. Loads the user's roles.
   *   4. Checks if user roles intersect with `requiredRoles`.
   *   5. Executes the provided block if authorized.
   *
   * If unauthorized, returns a detailed `Forbidden` response.
   *
   * @param request        incoming request
   * @param requiredRoles  set of roles allowed to access the resource
   * @param block          block to execute with authenticated user info
   * @return a `Result` wrapped in a `Future`
   */
  def withRoles[A](request: Request[A], requiredRoles: Set[String])
                  (block: AuthenticatedUser => Future[Result]): Future[Result] = {

    request.attrs.get(JwtAuthFilter.Attrs.UserId) match {
      case Some(rawUserId) =>
        extractUserIdFromAttr(rawUserId) match {
          case Some(userId) =>
            usersRepo.findByIdActive(userId).flatMap {
              case Some(user) =>
                loadUserRoles(userId).flatMap { roleNames =>
                  val hasRole = roleNames.exists(requiredRoles.contains)

                  if (hasRole) {
                    val au = AuthenticatedUser(
                      id = userId,
                      username = user.username,
                      roles = roleNames
                    )
                    block(au)
                  } else {
                    Future.successful(
                      Results.Forbidden(
                        Json.obj(
                          "error"            -> "Forbidden",
                          "requiredRoles"    -> requiredRoles,
                          "currentUserId"    -> userId,
                          "currentUserRoles" -> roleNames
                        )
                      )
                    )
                  }
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
   * Similar to [[withRoles]], but also validates a JSON request body
   * into a typed DTO using an implicit `Reads[T]`.
   *
   * Behavior:
   *   - If authentication fails → Unauthorized/Forbidden
   *   - If JSON body is invalid → BadRequest with detailed errors
   *   - Otherwise → executes the provided block
   *
   * @param request        JSON request
   * @param requiredRoles  allowed roles
   * @param block          block receiving (AuthenticatedUser, parsed DTO)
   * @param reads          JSON deserializer for the expected input DTO
   * @return a Result wrapped in a Future
   */
  def withRolesAndJsonBody[T](request: Request[JsValue], requiredRoles: Set[String])
                             (block: (AuthenticatedUser, T) => Future[Result])
                             (implicit reads: Reads[T]): Future[Result] = {

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
