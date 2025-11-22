package security

import org.apache.pekko.stream.Materializer
import play.api.http.HttpFilters
import play.api.libs.typedmap.TypedKey
import play.api.mvc._

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
 * A Play HTTP filter that enforces JWT-based authentication for protected routes.
 *
 * The filter:
 *   - Allows unauthenticated access to public routes (e.g., `/api/login`).
 *   - Extracts a `Bearer <token>` from the `Authorization` header.
 *   - Validates the JWT using [[security.JwtUtil]].
 *   - If valid, injects the authenticated `userId` into the request attributes.
 *   - Rejects requests with missing/invalid/expired tokens.
 *
 * Behavior:
 *   - OPTIONS requests are always allowed (CORS preflight).
 *   - Public routes bypass authentication.
 *   - Private routes require a valid JWT header.
 *
 * Request attribute used:
 *   - [[JwtAuthFilter.Attrs.UserId]]
 *
 * @param jwtUtil   utility for validating and decoding JWTs
 * @param mat       implicit materializer for processing requests
 * @param ec        execution context
 */
class JwtAuthFilter @Inject()(
                               jwtUtil: JwtUtil,
                               implicit val mat: Materializer,
                               ec: ExecutionContext
                             ) extends Filter {

  /**
   * Applies the authentication logic to each incoming request.
   *
   * @param nextFilter the next filter or action in the chain
   * @param request    the incoming HTTP request
   * @return a Future HTTP result, either authenticated or unauthorized
   */
  override def apply(nextFilter: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = {
    val publicRoutes = Seq(
      "/api/login"
    )

    // Allow CORS preflight and public routes
    if (request.method == "OPTIONS" || publicRoutes.exists(request.path.startsWith)) {
      nextFilter(request)
    } else {
      // Extract Bearer token
      val tokenOpt = request.headers.get("Authorization").collect {
        case header if header.startsWith("Bearer ") =>
          header.substring("Bearer ".length)
      }

      tokenOpt match {
        case Some(token) =>
          jwtUtil.validateToken(token) match {
            case Some(userId) =>
              // Inject userId into request attributes
              val enrichedRequest = request.addAttr(JwtAuthFilter.Attrs.UserId, userId)
              nextFilter(enrichedRequest)

            case None =>
              Future.successful(Results.Unauthorized("Invalid or expired token"))
          }

        case None =>
          Future.successful(Results.Unauthorized("Missing Authorization header"))
      }
    }
  }
}

/**
 * Companion object containing attribute keys used by the JWT filter.
 */
object JwtAuthFilter {
  object Attrs {
    /** Typed key used to store the authenticated user's ID in the request. */
    val UserId: TypedKey[String] = TypedKey[String]("userId")
  }
}

/**
 * Configures global HTTP filters for the application.
 *
 * Registers the [[JwtAuthFilter]] as the only global filter,
 * ensuring that all incoming HTTP traffic passes through JWT authentication
 * unless explicitly bypassed by route logic.
 *
 * @param jwtAuthFilter the injected JWT authentication filter
 */
class Filters @Inject()(jwtAuthFilter: JwtAuthFilter) extends HttpFilters {

  /** Sequence of active HTTP filters for the Play application. */
  override def filters: Seq[EssentialFilter] = Seq(jwtAuthFilter)
}
