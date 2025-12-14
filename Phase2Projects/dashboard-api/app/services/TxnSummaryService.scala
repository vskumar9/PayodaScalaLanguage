package services

import javax.inject.Inject
import repositories.S3Repository
import cache.{LocalCache, RedisCache}
import play.api.libs.json._
import play.api.Logger

import java.time.LocalDate
import java.time.format.DateTimeParseException
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/**
 * Transaction summary service supporting both single-customer and all-customers
 * daily summaries from S3 lakehouse with multi-layer caching.
 *
 * Cache hierarchy:
 * 1. **L1**: `LocalCache` (in-memory Caffeine, JVM-local)
 * 2. **L2**: `RedisCache` (distributed RedisClientPool, 1hr TTL)
 * 3. **L3**: `S3Repository` (Parquet scans on `laketxnsummary/date=YYYY-MM-DD/`)
 *
 * Key features:
 * - **Dual APIs**: Single customer (`GET /summary/{date}/{customerId}`) and bulk (`GET /summaries/{date}`)
 * - **In-flight deduplication**: Prevents duplicate S3 partition scans
 * - **Granular cache keys**: `summary:<date>:<customerId>` and `summary_all:<date>:<limit>`
 * - **Date validation**: Rejects invalid YYYY-MM-DD formats early
 * - **Graceful degradation**: Cache failures don't block S3 access
 */
class TxnSummaryService @Inject()(
                                   s3Repo: S3Repository,
                                   l1: LocalCache[String, JsValue],
                                   l2: RedisCache
                                 )(implicit ec: ExecutionContext) {

  /** Structured logger for cache hits, S3 scan errors, and validation failures. */
  private val logger = Logger(this.getClass)

  /** Redis L2 TTL: 1 hour for daily transaction summaries. */
  private val redisTtlSeconds = 3600

  /**
   * Thread-safe map for in-flight S3 scan deduplication across both single and bulk APIs.
   * Keys: `summary:<date>:<customerId>` or `summary_all:<date>:<limit>`
   */
  private val inflight =
    new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

/**
 * Retrieves daily transaction summary for a single customer from S3 lakehouse.
 *
 * Cache key: `summary:<date>:<customerId>`
 * S3 path: `laketxnsummary/date=YYYY-MM-DD/customerId=<id>/*.parquet`
 *
 * Execution flow:
 * 1. L1 hit → immediate return
 * 2. Join in-flight → share existing S3 scan
 * 3. L2 hit → populate L1, return
 * 4. S3 scan → populate L1+L2 (async), return
 *
 * @param date      YYYY-MM-DD date partition to query
 * @param customerId Customer identifier
 * @return Future containing summary JSON or standardized error object
*/*/
  def getSummary(date: String, customerId: Long): Future[JsValue] = {
    val key = s"summary:$date:$customerId"

    // ---- 1) Check L1
    l1.get(key) match {
      case Some(json) =>
        logger.debug(s"L1 summary cache hit for $key")
        return Future.successful(json)
      case None => ()
    }

    // ---- 2) Join in-flight if exists
    inflight.get(key) match {
      case Some(existing) =>
        logger.debug(s"Joining in-flight summary request for $key")
        return existing.recover { case _ =>
          Json.obj("error" -> "service_error")
        }

      case None => ()
    }

    // ---- 3) Create promise, register as in-flight
    val promise = Promise[JsValue]()
    inflight.put(key, promise.future)

    val pipeline = fetchWithCacheLayers(key, date, customerId)

    pipeline.onComplete { result =>
      inflight.remove(key)
      promise.complete(result)
    }

    promise.future
  }

  /**
   * Single-customer cache pipeline: L2 Redis → S3 partition scan → Cache warming.
   *
   * @param key        Cache key `summary:<date>:<customerId>`
   * @param date       S3 partition `date=YYYY-MM-DD`
   * @param customerId Customer filter for Parquet scan
   * @return Future containing summary JSON wrapper `{"summary": {...}}` or error
   */
  private def fetchWithCacheLayers(
                                    key: String,
                                    date: String,
                                    customerId: Long
                                  ): Future[JsValue] = {

    logger.debug(s"L2 Redis lookup for summary $key")

    l2.getJson(key).flatMap {
      case Some(json) =>
        logger.debug(s"L2 summary cache hit for $key")
        try l1.put(key, json)
        catch { case ex if NonFatal(ex) => logger.warn(s"L1.put failed for $key: ${ex.getMessage}") }
        Future.successful(json)

      case None =>
        logger.debug(s"L2 miss for $key; querying S3")

        s3Repo.getSummary(date, customerId)
          .map { maybeRow =>
            val json =
              maybeRow match {
                case Some(j: JsValue) => Json.obj("summary" -> j)
                case None             => Json.obj("error" -> "not-found")
              }

            // Update L1
            try l1.put(key, json)
            catch { case ex if NonFatal(ex) => logger.warn(s"L1.put failed for $key: ${ex.getMessage}") }

            // Async Redis update
            l2.setJson(key, json, redisTtlSeconds).onComplete {
              case Success(true)  => logger.debug(s"Redis SET success for $key")
              case Success(false) => logger.warn(s"Redis SET returned false for $key")
              case Failure(ex)    => logger.warn(s"Redis SET failed for $key: ${ex.getMessage}")
            }

            json
          }
          .recover { case ex =>
            logger.error(
              s"S3 getSummary failed for date=$date customerId=$customerId: ${ex.getMessage}",
              ex
            )
            Json.obj("error" -> "service_error", "msg" -> ex.getMessage)
          }
    }
  }

/**
 * Retrieves daily transaction summaries for all customers (top N by limit).
 *
 * Cache key: `summary_all:<date>:<limit>`
 * S3 path: `laketxnsummary/date=YYYY-MM-DD/*.parquet` (full partition scan)
 *
 * Early validation rejects invalid date formats before cache/S3 operations.
 *
 * @param date  YYYY-MM-DD date partition to scan
 * @param limit Maximum number of customer summaries to return
 * @return Future containing JSON array `{"summaries": [...]}` or error object
 */*/
  def getDailySummaries(date: String, limit: Int): Future[JsValue] = {
    val key = s"summary_all:$date:$limit"

    // ---- 1) L1 lookup
    l1.get(key) match {
      case Some(json) => return Future.successful(json)
      case None       => ()
    }

    // ---- 2) Join in-flight
    inflight.get(key) match {
      case Some(existingFuture) =>
        logger.debug(s"Joining in-flight summary_all request for $key")
        return existingFuture

      case None => ()
    }

    // ---- 3) Become owner
    val promise = Promise[JsValue]()
    inflight.put(key, promise.future)

    val pipelineF =
      if (!isValidDate(date)) {
        Future.successful(Json.obj("error" -> "invalid_date_format"))
      } else {
        l2.getJson(key).flatMap {
          case Some(json) =>
            logger.debug(s"L2 summary_all cache hit for $key")
            try l1.put(key, json)
            catch { case ex if NonFatal(ex) => logger.warn(s"L1.put failed for $key: ${ex.getMessage}") }
            Future.successful(json)

          case None =>
            logger.debug(s"L2 miss for $key; scanning S3")

            s3Repo.getDailySummaries(date, limit)
              .map { rows =>
                val json = Json.obj("summaries" -> JsArray(rows))

                // Update L1
                try l1.put(key, json)
                catch { case ex if NonFatal(ex) => logger.warn(s"L1.put failed for $key: ${ex.getMessage}") }

                // Update L2
                l2.setJson(key, json, redisTtlSeconds)

                json
              }
              .recover { case ex =>
                logger.error(s"S3 getDailySummaries failed for date=$date: ${ex.getMessage}", ex)
                Json.obj("error" -> "service_error", "msg" -> ex.getMessage)
              }
        }
      }

    pipelineF.onComplete { result =>
      inflight.remove(key)
      promise.complete(result)
    }

    promise.future
  }

  /**
   * Validates YYYY-MM-DD date format using Java `LocalDate.parse()`.
   *
   * Used for early validation in bulk summary API to avoid unnecessary cache/S3 operations.
   *
   * @param date YYYY-MM-DD string to validate
   * @return true if valid ISO date format
   */
  private def isValidDate(date: String): Boolean =
    try {
      LocalDate.parse(date)
      true
    } catch {
      case _: DateTimeParseException => false
    }
}
