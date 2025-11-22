package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import services.AuthService
import scala.concurrent.{ExecutionContext, Future}
import security.JwtUtil
import security.JwtAuthFilter

/**
 * Authentication controller responsible for handling user login, logout,
 * and retrieving authenticated user information.
 *
 * This controller integrates with [[services.AuthService]] for validating
 * user credentials and with [[security.JwtUtil]] for generating JWT tokens.
 *
 * Endpoints:
 *   - POST /api/login   → Authenticate user and return JWT token
 *   - POST /api/logout  → Log out user (stateless)
 *   - GET  /api/me      → Get authenticated user info using JWT
 *
 * @param cc         Controller components for Play MVC
 * @param authService Service handling credential verification
 * @param jwtUtil     Utility for generating and validating JWT tokens
 * @param ec          Execution context for asynchronous operations
 */
@Singleton
class AuthController @Inject()(
                                cc: ControllerComponents,
                                authService: AuthService,
                                jwtUtil: JwtUtil
                              )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /** Request payload for login containing username and password. */
  case class LoginRequest(username: String, password: String)

  /** Response payload for successful login containing JWT token and user info. */
  case class LoginResponse(token: String, userId: Int, username: String, expiresIn: Long)

  implicit val loginRequestReads   = Json.reads[LoginRequest]
  implicit val loginResponseWrites = Json.writes[LoginResponse]

  /**
   * Authenticates a user using the provided username and password.
   *
   * Expects a JSON body:
   * {{{
   * {
   *   "username": "user1",
   *   "password": "secret"
   * }
   * }}}
   *
   * On successful authentication:
   *   - generates a JWT token valid for 24 hours,
   *   - returns user ID, username, and token expiry time.
   *
   * @return 200 OK with [[LoginResponse]] on success,
   *         400 BadRequest if JSON is invalid,
   *         401 Unauthorized for invalid credentials.
   */
  def login(): Action[JsValue] = Action.async(parse.json) { request =>
    request.body.validate[LoginRequest].fold(
      _ => Future.successful(BadRequest(Json.obj("error" -> "Invalid request"))),
      loginReq => {
        authService.login(loginReq.username, loginReq.password).map {
          case Some(user) =>
            val token = jwtUtil.generateToken(user.userId.toString)
            val expiresIn = 86400L // 24 hours

            Ok(Json.toJson(LoginResponse(token, user.userId, user.username, expiresIn)))

          case None =>
            Unauthorized(Json.obj("error" -> "Invalid credentials"))
        }
      }
    )
  }

  /**
   * Logs out the authenticated user.
   *
   * Note: Since JWT authentication is stateless, this endpoint simply returns
   * a success message. Token invalidation must be implemented separately if required.
   *
   * @return 200 OK with confirmation message.
   */
  def logout(): Action[AnyContent] = Action {
    Ok(Json.obj("message" -> "Logged out successfully"))
  }

  /**
   * Returns information about the currently authenticated user.
   *
   * Requires a valid JWT token, typically passed in the `Authorization` header
   * (handled by [[security.JwtAuthFilter]]).
   *
   * @return 200 OK with the authenticated user's ID,
   *         401 Unauthorized if no valid JWT token is provided.
   */
  def me(): Action[AnyContent] = Action { request =>
    request.attrs.get(JwtAuthFilter.Attrs.UserId) match {
      case Some(userId) =>
        Ok(Json.obj("userId" -> userId))
      case None =>
        Unauthorized(Json.obj("error" -> "No authenticated user"))
    }
  }
}
