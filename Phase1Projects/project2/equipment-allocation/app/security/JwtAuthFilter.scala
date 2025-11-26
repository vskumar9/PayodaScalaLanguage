package security

import org.apache.pekko.stream.Materializer
import play.api.http.HttpFilters
import play.api.mvc._
import play.api.libs.typedmap.TypedKey

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * A Play Framework HTTP filter that enforces JWT-based authentication.
 *
 * Responsibilities:
 *   - Intercepts every HTTP request before it hits controllers.
 *   - Skips authentication for OPTIONS requests and explicitly allowed public routes.
 *   - Extracts a Bearer token from the `Authorization` header.
 *   - Validates the token using [[JwtUtil]].
 *   - On success, injects the `userId` into request attributes for downstream use.
 *   - On failure, returns HTTP 401 Unauthorized.
 *
 * Error Handling:
 *   - All unexpected exceptions are captured using `.recover`.
 *   - Ensures no unhandled runtime errors break the request chain.
 */
class JwtAuthFilter @Inject()(
                               jwtUtil: JwtUtil,
                               implicit val mat: Materializer,
                               ec: ExecutionContext
                             ) extends Filter {
  implicit val executionContext: ExecutionContext = ec

  /**
   * Main filter pipeline.
   *
   * @param nextFilter the next filter or controller in the chain
   * @param request incoming HTTP request
   * @return a future Result with JWT validation applied
   */
  override def apply(
                      nextFilter: RequestHeader => Future[Result]
                    )(request: RequestHeader): Future[Result] = {

    val publicRoutes = Seq(
      "/api/login"
    )

    try {

      // Skip auth for preflight + public endpoints
      if (request.method == "OPTIONS" || publicRoutes.exists(request.path.startsWith)) {
        nextFilter(request).recover {
          case NonFatal(ex) =>
            Results.InternalServerError("Unexpected server error")
        }
      }
      else {
        // Extract Bearer token
        val tokenOpt = request.headers.get("Authorization").collect {
          case header if header.startsWith("Bearer ") =>
            header.substring("Bearer ".length)
        }

        tokenOpt match {
          case Some(token) =>
            jwtUtil.validateToken(token) match {
              case Some(userId) =>
                val enrichedRequest =
                  request.addAttr(JwtAuthFilter.Attrs.UserId, userId)

                nextFilter(enrichedRequest).recover {
                  case NonFatal(ex) =>
                    Results.InternalServerError("Unexpected server error")
                }

              case None =>
                Future.successful(
                  Results.Unauthorized("Invalid or expired token")
                )
            }

          case None =>
            Future.successful(
              Results.Unauthorized("Missing Authorization header")
            )
        }
      }

    } catch {
      case NonFatal(ex) =>
        // Catch synchronous filter errors
        Future.successful(
          Results.InternalServerError("Internal filter error")
        )
    }
  }
}

object JwtAuthFilter {

  /**
   * Request attribute keys that store authenticated metadata.
   *
   * Injected into the request by the filter and accessed later in controllers.
   */
  object Attrs {

    /**
     * Authenticated user's ID extracted from JWT.
     */
    val UserId: TypedKey[String] = TypedKey[String]("userId")
  }
}

/**
 * Play Framework filter configuration.
 *
 * Registers all application-level filters such as the JWT authentication filter.
 *
 * @param jwtAuthFilter authentication filter instance
 */
class Filters @Inject()(jwtAuthFilter: JwtAuthFilter) extends HttpFilters {

  /**
   * Sequence of enabled filters.
   */
  override def filters: Seq[EssentialFilter] = Seq(jwtAuthFilter)
}
