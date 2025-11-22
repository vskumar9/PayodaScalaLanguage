package utils

import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, Conflict, InternalServerError}

import java.sql.SQLException

/**
 * Utility methods for producing standardized error responses
 * for user-related operations such as validation errors
 * and SQL constraint violations.
 *
 * This helper centralizes error formatting so controllers remain clean
 * and consistent in how they report failures.
 */
object UserErrorUtil {

  /**
   * Represents a validation error for a single field in a request.
   *
   * @param field the name of the field that failed validation
   * @param message a human-readable explanation of the validation failure
   */
  case class FieldError(field: String, message: String)

  /**
   * Builds a standardized JSON HTTP 400 error response
   * for multiple field-level validation errors.
   *
   * Example output:
   * {{{
   * {
   *   "error": "Validation failed",
   *   "details": [
   *     { "field": "email", "message": "Invalid email format" },
   *     { "field": "username", "message": "Username already taken" }
   *   ]
   * }
   * }}}
   *
   * @param fieldErrors sequence of field validation errors
   * @return HTTP 400 BadRequest containing structured JSON error details
   */
  def validationErrorResult(fieldErrors: Seq[FieldError]): Result = {
    val details: Seq[JsValue] = fieldErrors.map { fe =>
      Json.obj(
        "field"   -> fe.field,
        "message" -> fe.message
      )
    }

    BadRequest(
      Json.obj(
        "error"   -> "Validation failed",
        "details" -> details
      )
    )
  }

  /**
   * Converts SQL exceptions into clean, user-friendly HTTP error responses.
   *
   * This method detects common constraints such as:
   *   - Duplicate username
   *   - Duplicate email
   *
   * and converts them into a 409 Conflict response. All other errors
   * default to a 500 Internal Server Error with diagnostic details.
   *
   * @param e SQLException thrown during DB write operations
   * @return HTTP Result such as Conflict or InternalServerError
   */
  def handleSqlException(e: SQLException): Result = {
    val msg = Option(e.getMessage).getOrElse("")

    if (msg.contains("Duplicate entry") && msg.contains("users.username"))
      Conflict(Json.obj("error" -> "Username already exists"))
    else if (msg.contains("Duplicate entry") && msg.contains("users.email"))
      Conflict(Json.obj("error" -> "Email already exists"))
    else
      InternalServerError(Json.obj("error" -> "Database error", "details" -> msg))
  }
}
