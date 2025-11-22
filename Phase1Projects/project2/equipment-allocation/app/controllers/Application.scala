package controllers

import javax.inject._
import play.api.mvc._

/**
 * Application controller providing a handler for CORS preflight requests.
 *
 * This controller exposes a generic `OPTIONS` endpoint to respond to
 * browser preflight checks with the required CORS headers.
 */
@Singleton
class Application @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  /**
   * Handles CORS preflight (OPTIONS) requests for any given path.
   *
   * Returns a `204 No Content` response with the appropriate CORS headers,
   * allowing cross-origin requests from any domain. Useful when the frontend
   * sends OPTIONS requests before actual API calls.
   *
   * @param path The API path for which the browser is performing a preflight check.
   * @return A Play `Action` returning a `NoContent` result with CORS headers.
   */
  def preflight(path: String): Action[AnyContent] = Action { implicit request =>
    NoContent.withHeaders(
      "Access-Control-Allow-Origin"  -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers" -> "Origin, Accept, Content-Type, Authorization, X-Auth-Token",
      "Access-Control-Max-Age"       -> "86400"
    )
  }
}
