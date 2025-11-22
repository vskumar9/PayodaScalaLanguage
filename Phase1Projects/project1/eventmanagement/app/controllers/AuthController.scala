package controllers

import play.api.libs.json._
import play.api.mvc._
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import services.AuthService
import security.{JwtUtil, JwtAuthFilter}
import repositories.UsersRepository

/**
 * Controller responsible for authentication endpoints: login, logout and retrieving
 * the currently authenticated user's details.
 *
 * This controller delegates authentication logic to [[AuthService]] and token
 * generation to [[JwtUtil]]. It also reads user information from [[UsersRepository]].
 *
 * @param cc         Play controller components.
 * @param authService Service that handles authentication and user lookup.
 * @param jwtUtil     Utility for generating/verifying JWT tokens.
 * @param usersRepo   Repository for reading user details.
 * @param ec          ExecutionContext for async operations.
 */
@Singleton
class AuthController @Inject()(
                                cc: ControllerComponents,
                                authService: AuthService,
                                jwtUtil: JwtUtil,
                                usersRepo: UsersRepository
                              )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /**
   * Request payload for login endpoint.
   *
   * @param username user's login name
   * @param password user's password
   */
  case class LoginRequest(username: String, password: String)

  /**
   * Response payload for successful login.
   *
   * @param token     JWT token string
   * @param userId    numeric id of the authenticated user
   * @param username  username for convenience
   * @param expiresIn token lifetime in seconds
   */
  case class LoginResponse(token: String, userId: Int, username: String, expiresIn: Long)

  /**
   * Response payload for the "me" endpoint.
   *
   * @param userId   numeric id of the user
   * @param username username
   * @param email    user's email address
   * @param fullName user's full name
   * @param phone    optional phone number
   */
  case class MeResponse(userId: Int, username: String, email: String, fullName: String, phone: Option[String])

  /** JSON readers/writers for request/response payloads. */
  implicit val loginRequestReads   : Reads[LoginRequest]   = Json.reads[LoginRequest]
  implicit val loginResponseWrites: OWrites[LoginResponse] = Json.writes[LoginResponse]
  implicit val meResponseWrites   : OWrites[MeResponse]    = Json.writes[MeResponse]

  /** Token expiry (seconds). Currently set to 24 hours. */
  private val expiresInSeconds: Long = 24 * 60 * 60L

  /**
   * Safely extract an integer user id from an attribute value that might be Int, Long or String.
   *
   * @param attrValue attribute extracted from request attributes
   * @return Some(userId) when conversion succeeds, otherwise None
   */
  private def extractUserId(attrValue: Any): Option[Int] = attrValue match {
    case i: Int    => Some(i)
    case s: String => Try(s.toInt).toOption
    case l: Long   => Try(l.toInt).toOption
    case _         => None
  }

  /**
   * Handle login requests.
   *
   * Expects a JSON body matching [[LoginRequest]]. On success returns [[LoginResponse]]
   * containing a JWT token and basic user info. Returns appropriate 4xx responses for
   * invalid input or credentials. All unexpected errors are handled and return 500 with
   * a generic message and an optional detail.
   *
   * @return Action that accepts JSON and produces JSON.
   */
  def login(): Action[JsValue] = Action.async(parse.json) { request =>
    request.body.validate[LoginRequest].fold(
      errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request"))),
      loginReq =>
        authService.login(loginReq.username, loginReq.password)
          .map {
            case Some(user) =>
              user.userId match {
                case Some(uid) =>
                  val token = jwtUtil.generateToken(uid.toString)
                  Ok(Json.toJson(LoginResponse(token, uid, user.username, expiresInSeconds)))
                case None =>
                  InternalServerError(Json.obj("error" -> "User record has no id"))
              }
            case None =>
              Unauthorized(Json.obj("error" -> "Invalid credentials"))
          }
          .recover { case ex: Throwable =>
            // Generic 500 handler for unexpected exceptions during login
            InternalServerError(Json.obj(
              "error"   -> "Internal server error",
              "details" -> ex.getMessage
            ))
          }
    )
  }

  /**
   * Logout endpoint.
   *
   * Currently this is a placeholder that returns a success message. If token invalidation
   * or blacklisting is needed, implement that logic here.
   *
   * @return Action that produces a JSON success message.
   */
  def logout(): Action[AnyContent] = Action {
    Ok(Json.obj("message" -> "Logged out successfully"))
  }

  /**
   * Returns information about the currently authenticated user.
   *
   * The user id is read from request attributes populated by [[JwtAuthFilter]].
   * If the user id is missing/invalid or the user cannot be found, the appropriate
   * 401 response is returned. Unexpected repository errors are caught and reported
   * as a 500 error.
   *
   * @return Action that produces JSON with user details or an error.
   */
  def me(): Action[AnyContent] = Action.async { request =>
    request.attrs.get(JwtAuthFilter.Attrs.UserId) match {
      case Some(raw) =>
        extractUserId(raw) match {
          case Some(uid) =>
            usersRepo.findByIdActive(uid).map {
              case Some(u) =>
                Ok(Json.toJson(MeResponse(
                  userId = u.userId.getOrElse(uid),
                  username = u.username,
                  email = u.email,
                  fullName = u.fullName,
                  phone = u.phone
                )))
              case None =>
                Unauthorized(Json.obj("error" -> "User not found"))
            }.recover { case ex: Throwable =>
              // Generic 500 handler for unexpected exceptions during user lookup
              InternalServerError(Json.obj(
                "error"   -> "Internal server error",
                "details" -> ex.getMessage
              ))
            }

          case None =>
            Future.successful(Unauthorized(Json.obj("error" -> "Invalid user id in token")))
        }

      case None =>
        Future.successful(Unauthorized(Json.obj("error" -> "No authenticated user")))
    }
  }
}
