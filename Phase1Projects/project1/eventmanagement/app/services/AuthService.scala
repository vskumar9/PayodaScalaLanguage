package services

import models.User
import org.mindrot.jbcrypt.BCrypt
import repositories.UsersRepository

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service responsible for handling user authentication.
 *
 * This service performs:
 *   - username lookup
 *   - account validity checks
 *   - password verification using BCrypt
 *
 * A user is considered valid for login only if:
 *   - the account exists
 *   - the account is active (`isActive = true`)
 *   - the account is not soft-deleted (`isDeleted = false`)
 *   - the provided password matches the stored hash
 *
 * Password comparisons are done using BCrypt's constant-time verification
 * to avoid timing attacks.
 *
 * @param usersRepo repository for accessing and retrieving user records
 * @param ec        execution context for asynchronous operations
 */
@Singleton
class AuthService @Inject()(
                             usersRepo: UsersRepository
                           )(implicit ec: ExecutionContext) {

  /**
   * Attempts to authenticate a user using the provided credentials.
   *
   * @param username username provided during login
   * @param password plaintext password provided during login
   * @return Some(User) if authentication succeeds, None otherwise
   */
  def login(username: String, password: String): Future[Option[User]] = {
    usersRepo.findByUsername(username).map {
      case Some(user)
        if user.isActive &&
          !user.isDeleted &&
          safePasswordCheck(password, user.passwordHash) =>
        Some(user)

      case _ =>
        None
    }
  }

  /**
   * Safely verifies a plaintext password against a stored BCrypt hash.
   *
   * Wrapped in a try/catch to avoid BCrypt errors (e.g., malformed hashes)
   * from causing authentication failures through exceptions.
   *
   * @param plain  plaintext password
   * @param hashed BCrypt hashed password stored in the database
   * @return true if password matches, false otherwise
   */
  private def safePasswordCheck(plain: String, hashed: String): Boolean = {
    try {
      BCrypt.checkpw(plain, hashed)
    } catch {
      case _: Throwable => false
    }
  }
}
