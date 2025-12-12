package utils

import play.api.libs.json._

/**
 * Standardized API response envelope used across all endpoints.
 *
 * Ensures consistent JSON structure for both success and error cases:
 * ```
 * {
 *   "status": "success" | "error",
 *   "message": "descriptive message",
 *   "data": { response data or null }
 * }
 * ```
 *
 * Used by all controllers to wrap service responses uniformly.
 */
case class ApiResponse(
                        /**
                         * Response status indicator.
                         * - "success" for successful requests
                         * - "error" for validation failures, not-found, service errors
                         */
                        status: String,

                        /**
                         * Human-readable message describing the result.
                         * - Success: operation description (e.g. "Customer profile retrieved successfully")
                         * - Error: error code (e.g. "invalid_date_format", "not-found", "service_error")
                         */
                        message: String,

                        /**
                         * Response payload.
                         * - Success: actual data (customer profile, summary, events array, etc.)
                         * - Error: JsNull or empty object
                         */
                        data: JsValue
                      )

/**
 * Companion object with JSON serialization and factory methods.
 */
object ApiResponse {

  /** Play JSON Writes for ApiResponse serialization. */
  implicit val writes: Writes[ApiResponse] = Json.writes[ApiResponse]

  /**
   * Creates a success response with optional custom message.
   *
   * @param data    Response payload (customer data, summary, events, etc.)
   * @param message Descriptive success message (defaults to "OK")
   * @return JsValue ready for controller response
   */
  def success(data: JsValue, message: String = "OK"): JsValue =
    Json.toJson(ApiResponse("success", message, data))

  /**
   * Creates an error response with custom message.
   *
   * @param message Error code/message (e.g. "invalid_date_format", "not-found")
   * @param data    Optional error details (defaults to JsNull)
   * @return JsValue ready for controller response
   */
  def error(message: String, data: JsValue = JsNull): JsValue =
    Json.toJson(ApiResponse("error", message, data))
}
