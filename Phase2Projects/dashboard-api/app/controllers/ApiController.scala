package controllers

import javax.inject._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import java.time.LocalDate
import java.time.format.DateTimeParseException

import services.{CustomerProfileService, TxnSummaryService, EventService}
import play.api.Logger
import utils.ApiResponse

/**
 * REST API controller for dashboard analytics endpoints.
 *
 * Exposes customer profiles, transaction summaries, and behavioral events
 * from S3 lakehouse data with caching and validation.
 *
 * All responses follow the standard ApiResponse envelope:
 * {
 *   "status": "success" | "error",
 *   "message": "...",
 *   "data": { ... }
 * }
 */
@Singleton
class ApiController @Inject()(
                               cc: ControllerComponents,
                               customerService: CustomerProfileService,
                               summaryService: TxnSummaryService,
                               eventService: EventService
                             )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /** Logger instance for request/response logging. */
  private val logger = Logger(this.getClass)

  // ============================================================
  // GET /customer/:id
  //
  // Returns customer profile data.
  // ========================================================
  /**
   * Customer profile endpoint.
   *
   * @param id Customer identifier.
   * @return Customer profile data or error response.
   */
  def getCustomer(id: Long): Action[AnyContent] = Action.async {
    customerService.getCustomerProfile(id).map { body =>
      Ok(ApiResponse.success(body, "Customer profile retrieved successfully"))
    }.recover { ex =>
      logger.error(s"getCustomer failed for id=$id", ex)
      InternalServerError(ApiResponse.error("service_error"))
    }
  }

  // ============================================================
  // GET /summary/:date/:customerId
  //
  // Returns daily transaction summary for specific customer.
  // ============================================================
  /**
   * Daily transaction summary for a customer.
   *
   * @param date       Summary date (YYYY-MM-DD).
   * @param customerId Customer identifier.
   * @return Summary data or validation/service error.
   */
  def getSummary(date: String, customerId: Long): Action[AnyContent] = Action.async {
    if (!isValidDate(date)) {
      logger.warn(s"Invalid date format for summary: $date")
      Future.successful(BadRequest(ApiResponse.error("invalid_date_format")))
    } else {
      summaryService.getSummary(date, customerId).map { body =>
        Ok(ApiResponse.success(body, "Daily transaction summary retrieved successfully"))
      }.recover { ex =>
        logger.error(s"getSummary failed for date=$date customerId=$customerId", ex)
        InternalServerError(ApiResponse.error("service_error"))
      }
    }
  }

  // ============================================================
  // GET /events/:customerId?fromDate=&toDate=&limit=
  //
  // Returns customer behavioral events with optional date range.
  // ============================================================
  /**
   * Customer behavioral events endpoint.
   *
   * @param customerId Customer identifier.
   * @param fromDate   Start date (optional, defaults to today-10days).
   * @param toDate     End date (optional, defaults to today).
   * @param limit      Max events (optional, default 20, max 500).
   * @return Event list or validation error.
   */
  def getEvents(
                 customerId: Long,
                 fromDate: Option[String],
                 toDate: Option[String],
                 limit: Option[Int]
               ): Action[AnyContent] = Action.async {

    val today       = LocalDate.now()
    val defaultFrom = today.minusDays(10)
    val defaultTo   = today

    val from = fromDate.getOrElse(defaultFrom.toString)
    val to   = toDate.getOrElse(defaultTo.toString)

    if (!isValidDate(from)) {
      logger.warn(s"Invalid fromDate: $from")
      Future.successful(BadRequest(ApiResponse.error("invalid_fromDate_format")))
    } else if (!isValidDate(to)) {
      logger.warn(s"Invalid toDate: $to")
      Future.successful(BadRequest(ApiResponse.error("invalid_toDate_format")))
    } else {
      val finalLimit = limit.getOrElse(20)
      if (finalLimit <= 0 || finalLimit > 500) {
        logger.warn(s"Invalid limit: $finalLimit")
        Future.successful(BadRequest(ApiResponse.error("invalid_limit")))
      } else {
        eventService
          .getEvents(customerId, from, to, finalLimit)
          .map { body =>
            Ok(ApiResponse.success(body, "Customer events retrieved successfully"))
          }.recover { ex =>
            logger.error(
              s"getEvents failed for customer=$customerId from=$from to=$to limit=$finalLimit",
              ex
            )
            InternalServerError(ApiResponse.error("service_error"))
          }
      }
    }
  }

  /**
   * Daily transaction summaries for all customers on a given date.
   *
   * @param date   Summary date (YYYY-MM-DD).
   * @param limit  Max summaries to return (optional, default 500, max 500).
   * @return List of daily summaries or validation error.
   */
  def getDailySummaries(date: String, limit: Option[Int]): Action[AnyContent] = Action.async {
    if (!isValidDate(date)) {
      logger.warn(s"Invalid date format for daily summaries: $date")
      Future.successful(BadRequest(ApiResponse.error("invalid_date_format")))
    } else {
      val finalLimit = limit.getOrElse(100)
      if (finalLimit <= 0 || finalLimit > 500) {
        logger.warn(s"Invalid limit for daily summaries: $finalLimit")
        Future.successful(BadRequest(ApiResponse.error("invalid_limit")))
      } else {
        summaryService.getDailySummaries(date, finalLimit).map { body =>
          Ok(ApiResponse.success(body, "Daily transaction summaries retrieved successfully"))
        }.recover { ex =>
          logger.error(s"getDailySummaries failed for date=$date", ex)
          InternalServerError(ApiResponse.error("service_error"))
        }
      }
    }
  }

  // ============================================================
  // Helpers
  // ============================================================
  /**
   * Validates a date string in YYYY-MM-DD format.
   *
   * @param date Date string to validate.
   * @return true if valid LocalDate, false otherwise.
   */
  private def isValidDate(date: String): Boolean =
    try {
      LocalDate.parse(date)
      true
    } catch {
      case _: DateTimeParseException => false
    }
}
