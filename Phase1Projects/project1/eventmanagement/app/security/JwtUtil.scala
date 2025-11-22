package security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import play.api.Configuration

import java.util.Date
import javax.inject.{Inject, Singleton}

/**
 * Utility class for creating and validating JSON Web Tokens (JWT) using HMAC-based signing.
 *
 * This class encapsulates all JWT-related logic, including:
 *
 *   - Generating signed JWT tokens for authenticated users.
 *   - Validating incoming tokens to ensure authenticity and expiration.
 *
 * Configuration keys (application.conf):
 * {{{
 *   jwt.secret      = "your-secret-key"
 *   jwt.issuer      = "your-app-name"
 *   jwt.expiration  = 86400     # expiration in seconds
 * }}}
 *
 * Token structure:
 *  - Issuer (`iss`)
 *  - Subject (`sub`) containing the user ID
 *  - Issued At (`iat`)
 *  - Expiration (`exp`)
 *
 * @param config Play configuration used to read JWT signing and expiration settings
 */
@Singleton
class JwtUtil @Inject()(
                         config: Configuration
                       ) {

  /** Secret key used to sign and verify JWT tokens. */
  private val secretKey: String = config.get[String]("jwt.secret")

  /** Issuer value embedded in the JWT for validation. */
  private val issuer: String = config.get[String]("jwt.issuer")

  /** Token validity duration in seconds. */
  private val expirationSeconds: Long = config.get[Long]("jwt.expiration")

  /** HMAC256 signing algorithm used for token generation and validation. */
  private val algorithm = Algorithm.HMAC256(secretKey)

  /**
   * Generates a signed JWT token for the given user ID.
   *
   * @param userId the authenticated user’s ID
   * @return a signed JWT token string
   */
  def generateToken(userId: String): String = {
    val now = System.currentTimeMillis()
    val exp = now + expirationSeconds * 1000 // Convert seconds to milliseconds

    JWT.create()
      .withIssuer(issuer)
      .withSubject(userId)
      .withIssuedAt(new Date(now))
      .withExpiresAt(new Date(exp))
      .sign(algorithm)
  }

  /**
   * Validates a JWT token and extracts its subject (user ID).
   *
   * @param token the JWT token to validate
   * @return Some(userId) if valid; None if invalid or expired
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
