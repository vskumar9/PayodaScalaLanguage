package controllers

import play.api.mvc._
import javax.inject._

/**
 * Application-wide utility controller.
 *
 * This controller provides endpoints used across the application,
 * including a generic CORS preflight handler for cross-origin requests.
 *
 * @param cc The Play framework controller components.
 */
@Singleton
class Application @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  /**
   * Handles CORS preflight (OPTIONS) requests for any path.
   *
   * This method returns a 204 No Content response with the required
   * CORS headers to allow cross-origin requests from browsers. It
   * enables standard HTTP methods and common request headers.
   *
   * @param path The path for which the CORS preflight request is made.
   *             This value is not used directly, but allows the route
   *             to match OPTIONS requests on any endpoint.
   * @return An Action that produces a CORS-compliant response.
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
