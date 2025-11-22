package services

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}
import repositories.UserRepository
import org.mindrot.jbcrypt.BCrypt
import models.User

/**
 * Authentication service responsible for:
 *
 *   - Validating user login credentials
 *   - Hashing passwords securely using BCrypt
 *   - Creating users with hashed passwords
 *
 * This service does **not** issue JWT tokens. Token generation
 * is handled separately by `JwtUtil`, while this service only
 * authenticates credentials against stored user data.
 *
 * Password security:
 *   - BCrypt is used for hashing and verifying passwords.
 *   - Stored hashes are compared using `BCrypt.checkpw`.
 *
 * Error Handling:
 *   - Invalid inputs return `None` safely.
 *   - BCrypt exceptions (rare) are caught and treated as failed login attempts.
 *
 * @param userRepository Repository for loading and creating users
 * @param ec             ExecutionContext for async operations
 */
class AuthService @Inject()(
                             userRepository: UserRepository
                           )(implicit ec: ExecutionContext) {

  /**
   * Attempts to authenticate a user by verifying username and password.
   *
   * Steps:
   *   1. Validate non-empty username/password
   *   2. Look up user in the database
   *   3. Check account is active
   *   4. Verify the BCrypt password hash
   *
   * @param username Username provided by user during login
   * @param password Raw password input during login
   * @return
   *         - `Some(user)` if authentication succeeds
   *         - `None` if invalid username, invalid password,
   *           user not found, user inactive, or hash mismatch
   */
  def login(username: String, password: String): Future[Option[User]] = {
    if (username == null || username.trim.isEmpty || password == null || password.isEmpty) {
      Future.successful(None)
    } else {
      userRepository.findByUsername(username).map {
        case Some(user) if user.isActive =>
          val storedHash = Option(user.passwordHash).map(_.trim).getOrElse("")
          if (storedHash.isEmpty) {
            None
          } else {
            Try(BCrypt.checkpw(password, storedHash)) match {
              case Success(true)  => Some(user)
              case Success(false) => None
              case Failure(_)     => None
            }
          }

        case _ => None
      }
    }
  }

  /**
   * Hashes a raw password using BCrypt with a generated salt.
   *
   * @param password Raw plaintext password
   * @return A secure BCrypt hash suitable for storage
   *
   * BCrypt automatically:
   *   - Generates a salt
   *   - Applies multiple rounds of hashing
   *   - Produces a hash including salt & cost factor
   */
  def hashPassword(password: String): String =
    BCrypt.hashpw(password, BCrypt.gensalt())

  /**
   * Creates a new user with a securely hashed password.
   *
   * This method replaces the raw password in the User model with
   * a BCrypt hash before saving to the database.
   *
   * @param user A User model whose passwordHash currently holds the raw password
   * @return The newly created user's ID
   */
  def createUser(user: User): Future[Int] = {
    val hashed = user.copy(passwordHash = hashPassword(user.passwordHash))
    userRepository.create(hashed)
  }
}
