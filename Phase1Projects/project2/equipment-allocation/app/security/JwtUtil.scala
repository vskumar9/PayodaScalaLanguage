package security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import play.api.Configuration

import javax.inject.{Inject, Singleton}
import java.util.Date

/**
 * Utility class responsible for creating and validating JWT tokens.
 *
 * This component reads configuration values from `application.conf`:
 * {{{
 * jwt.secret      = "your-secret-key"
 * jwt.issuer      = "your-app-name"
 * jwt.expiration  = 3600   # seconds
 * }}}
 *
 * Features:
 *   - Generates JWT tokens for authenticated users.
 *   - Validates incoming tokens and extracts the `subject` (userId).
 *   - Uses HMAC256 signing algorithm.
 *
 * Error Handling:
 *   - Invalid, expired, or tampered tokens result in `None` during validation.
 *   - Only valid tokens return `Some(userId)`.
 */
@Singleton
class JwtUtil @Inject()(
                         config: Configuration
                       ) {

  /** Secret key used to sign and verify JWT tokens (HMAC256). */
  private val secretKey: String = config.get[String]("jwt.secret")

  /** Token issuer string used to validate authenticity. */
  private val issuer: String = config.get[String]("jwt.issuer")

  /** Token expiration duration (in seconds). */
  private val expirationSeconds: Long =
    config.get[Long]("jwt.expiration")

  /** Reusable HMAC256 signing algorithm instance. */
  private val algorithm: Algorithm = Algorithm.HMAC256(secretKey)

  /**
   * Generates a new JWT token for the given user ID.
   *
   * @param userId Unique identifier for the authenticated user; stored as JWT `subject`.
   * @return A signed JWT token as a string.
   *
   * The token contains:
   *   - issuer (`iss`)
   *   - subject (`sub`)
   *   - issued-at timestamp (`iat`)
   *   - expiration timestamp (`exp`)
   */
  def generateToken(userId: String): String = {
    val now = System.currentTimeMillis()
    val exp = now + expirationSeconds * 1000

    JWT.create()
      .withIssuer(issuer)
      .withSubject(userId)
      .withIssuedAt(new Date(now))
      .withExpiresAt(new Date(exp))
      .sign(algorithm)
  }

  /**
   * Validates the given JWT token and returns the `subject` (userId) if valid.
   *
   * @param token Raw JWT token string (without "Bearer " prefix)
   * @return
   *         - `Some(userId)` if token is valid and not expired.
   *         - `None` if token is invalid, expired, incorrectly signed, or malformed.
   *
   * This method catches:
   *   - `JWTVerificationException`
   */
  def validateToken(token: String): Option[String] = {
    try {
      val verifier   = JWT.require(algorithm).withIssuer(issuer).build()
      val decodedJWT = verifier.verify(token)
      Some(decodedJWT.getSubject)
    } catch {
      case _: JWTVerificationException =>
        None
    }
  }
}
