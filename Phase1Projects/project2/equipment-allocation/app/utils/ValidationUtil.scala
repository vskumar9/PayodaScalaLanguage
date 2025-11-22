package utils

/**
 * Utility object providing common validation methods for user input,
 * including password, email, and phone number validation.
 *
 * Each validator returns:
 *   - `None`     → when the value is valid
 *   - `Some(msg)` → when validation fails, containing the human-readable error message
 *
 * These utilities are typically used by controllers and services to ensure
 * incoming request data meets required business rules before processing.
 */
object ValidationUtil {

  /**
   * Validates a password based on common security rules.
   *
   * Validation Rules:
   *   - Minimum length: 8 characters
   *   - Must contain at least one uppercase letter
   *   - Must contain at least one lowercase letter
   *   - Must contain at least one digit
   *   - Must contain at least one special character
   *
   * @param pw the password string to validate
   * @return `None` if valid, otherwise `Some(errorMessage)`
   */
  def validatePassword(pw: String): Option[String] = {
    val errors = collection.mutable.ListBuffer[String]()

    if (pw.length < 8)
      errors += "Password must be at least 8 characters long"
    if (!pw.exists(_.isUpper))
      errors += "Password must contain at least one uppercase letter"
    if (!pw.exists(_.isLower))
      errors += "Password must contain at least one lowercase letter"
    if (!pw.exists(_.isDigit))
      errors += "Password must contain at least one digit"
    if (!pw.exists(ch => "!@#$%^&*()_-+=[]{}|;:'\",.<>?/`~".contains(ch)))
      errors += "Password must contain at least one special character"

    if (errors.isEmpty) None else Some(errors.mkString("; "))
  }

  /**
   * Validates an email string using a basic regex pattern.
   *
   * Rules:
   *   - Must follow the format "user@domain.tld"
   *   - Allows alphanumeric characters, dots, dashes, and underscores
   *
   * @param email the email string to validate
   * @return `None` if valid, otherwise `Some(errorMessage)`
   */
  def validateEmail(email: String): Option[String] = {
    val EmailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".r
    if (EmailRegex.matches(email)) None
    else Some("Email is not in a valid format")
  }

  /**
   * Validates a phone number, if provided.
   *
   * Rules:
   *   - Must be 10 to 15 digits
   *   - No spaces or special symbols allowed
   *   - `None` means phone number is optional and valid
   *
   * @param phoneOpt optional phone number string
   * @return `None` if valid or empty, otherwise `Some(errorMessage)`
   */
  def validatePhone(phoneOpt: Option[String]): Option[String] = {
    phoneOpt match {
      case None => None
      case Some(p) =>
        val PhoneRegex = "^[0-9]{10,15}$".r
        if (PhoneRegex.matches(p)) None
        else Some("Phone number must be 10–15 digits with no spaces or symbols")
    }
  }
}
