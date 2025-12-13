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
 * Transaction summary service with multi-layer caching:
 *  - L1 LocalCache (fast)
 *  - L2 RedisCache (now backed by RedisClientPool)
 *  - L3 S3 lakehouse (expensive)
 *
 * In-flight deduplication avoids redundant S3 scans.
 */
class TxnSummaryService @Inject()(
                                   s3Repo: S3Repository,
                                   l1: LocalCache[String, JsValue],
                                   l2: RedisCache
                                 )(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /** Redis TTL for daily summaries (1 hour). */
  private val redisTtlSeconds = 3600

  /** In-flight map for deduplicating S3 calls. */
  private val inflight =
    new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  // ---------------------------------------------------------------------------------------
  //   SINGLE CUSTOMER DAILY SUMMARY
  // ---------------------------------------------------------------------------------------

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
   * Cache chain for single summary: L2 → S3 fallback
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

  // ---------------------------------------------------------------------------------------
  //   ALL CUSTOMERS DAILY SUMMARY
  // ---------------------------------------------------------------------------------------

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

  private def isValidDate(date: String): Boolean =
    try {
      LocalDate.parse(date)
      true
    } catch {
      case _: DateTimeParseException => false
    }
}
