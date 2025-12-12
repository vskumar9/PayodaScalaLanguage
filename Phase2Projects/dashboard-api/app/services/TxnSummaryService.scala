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

/**
 * Transaction summary service with multi-layer caching for S3 lakehouse data.
 *
 * Implements 3-tier caching + in-flight deduplication:
 *  1. L1: LocalCache (in-memory, JVM-local)
 *  2. L2: RedisCache (distributed, 10s TTL - daily data changes infrequently)
 *  3. L3: S3Repository (Parquet partition scans)
 *
 * Two endpoints:
 * - Single customer summary by date
 * - All customers summaries for a date (with limit)
 */
class TxnSummaryService @Inject() (
                         s3Repo: S3Repository,
                         l1: LocalCache[String, JsValue],
                         l2: RedisCache,
                       )(implicit ec: ExecutionContext) {

  /** Logger for cache hits/misses and S3 partition scan performance. */
  private val logger = Logger(this.getClass)

  /** Redis TTL for summaries (1 Day) - daily data changes infrequently. */
  private val redisTtlSeconds: Int = 86400 // 24 hours = 60*60*24 //10

  /**
   * In-flight deduplication prevents duplicate S3 partition scans.
   * Concurrent requests share expensive `lake/txn_summary/date=YYYY-MM-DD/` scans.
   */
  private val inflight = new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  /**
   * Public API: Single customer daily transaction summary.
   *
   * Returns: `{ "summary": {...} }` or `{ "error": "not-found" }`
   *
   * Cache flow:
   * 1. L1 hit → immediate
   * 2. Join in-flight S3 scan
   * 3. L2 hit → populate L1
   * 4. S3 scan → populate L1+L2
   *
   * @param date       Summary date (YYYY-MM-DD)
   * @param customerId Customer identifier
   * @return Future[JsValue] with summary JSON or error object
   */
  def getSummary(date: String, customerId: Long): Future[JsValue] = {
    val key = s"summary:$date:$customerId"

    // ---------- 1. L1 Cache ----------
    l1.get(key) match {
      case Some(v) =>
        logger.debug(s"L1 summary cache hit for $key")
        return Future.successful(v)
      case None => // continue
    }

    // ---------- 2. In-flight dedup ----------
    inflight.get(key) match {
      case Some(existingFuture) =>
        logger.debug(s"Joining in-flight summary request for $key")
        return existingFuture.recover { case _ =>
          Json.obj("error" -> "service_error")
        }
      case None => // continue
    }

    // ---------- 3. Create promise and register inflight ----------
    val p = Promise[JsValue]()
    inflight.put(key, p.future)

    // Build async pipeline
    val pipelineF = fetchWithCacheLayers(key, date, customerId)

    pipelineF.onComplete { res =>
      inflight.remove(key)
      p.complete(res)
    }

    p.future
  }

  /**
   * Internal cache pipeline: L2 Redis → S3 lakehouse fallback.
   *
   * Handles single-customer summary with full cache population.
   *
   * @param key        Cache key for deduplication
   * @param date       Summary date
   * @param customerId Customer filter
   * @return Future[JsValue] with `{ "summary": {...} }` or error
   */
  private def fetchWithCacheLayers(
                                    key: String,
                                    date: String,
                                    customerId: Long
                                  ): Future[JsValue] = {

    // -------- L2 Redis --------
    logger.debug(s"L2 summary lookup for $key")

    l2.getJson(key).flatMap {
      case Some(json) =>
        logger.debug(s"L2 summary cache hit for $key")

        try l1.put(key, json)
        catch { case ex: Throwable => logger.warn(s"L1.put failed for summary $key: ${ex.getMessage}") }

        Future.successful(json)

      case None =>
        // -------- L3 S3 Select --------
        logger.debug(s"L2 miss for $key; querying S3 summary partition")

        s3Repo.getSummary(date, customerId)
          .map { maybeRow =>

            val json: JsValue =
              maybeRow match {
                case Some(rowJson: JsValue) => Json.obj("summary" -> rowJson)
                case None                   => Json.obj("error" -> "not-found")
              }

            // --- Update L1 ---
            try l1.put(key, json)
            catch { case ex: Throwable => logger.warn(s"L1.put failed for summary $key: ${ex.getMessage}") }

            // --- Update L2 (fire and forget) ---
            l2.setJson(key, json, ttlSec = redisTtlSeconds).onComplete {
              case Success(true)  => logger.debug(s"Redis summary SET success for $key")
              case Success(false) => logger.warn(s"Redis summary SET returned false for $key")
              case Failure(ex)    => logger.warn(s"Redis summary SET failed for $key: ${ex.getMessage}")
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
   * All customer summaries for a date (limited).
   *
   * Scans entire `lake/txn_summary/date=YYYY-MM-DD/` partition.
   *
   * Returns: `{ "summaries": [ ... ] }`
   *
   * @param date  Summary date (YYYY-MM-DD)
   * @param limit Maximum summaries to return (default/max 500)
   * @return Future[JsValue] with summaries array or error
   */
  def getDailySummaries(date: String, limit: Int): Future[JsValue] = {
    val key = s"summary_all:$date:$limit"

    l1.get(key) match {
      case Some(v) => return Future.successful(v)
      case None    => ()
    }

    val p = Promise[JsValue]()
    inflight.put(key, p.future)

    val f =
      if (!isValidDate(date)) {
        Future.successful(Json.obj("error" -> "invalid_date_format"))
      } else {
        s3Repo.getDailySummaries(date, limit).map { rows =>
          Json.obj("summaries" -> JsArray(rows))
        }
      }

    f.onComplete { res =>
      inflight.remove(key)
      res.foreach { json =>
        try l1.put(key, json)
        catch { case ex: Throwable => logger.warn(s"L1.put failed for $key: ${ex.getMessage}") }
        l2.setJson(key, json, ttlSec = redisTtlSeconds).onComplete(_ => ())
      }
      p.complete(res)
    }

    p.future
  }

  /**
   * Validates date string format (YYYY-MM-DD).
   *
   * @param date Date string to validate
   * @return true if parseable as LocalDate
   */
  private def isValidDate(date: String): Boolean =
    try {
      LocalDate.parse(date)
      true
    } catch {
      case _: DateTimeParseException => false
    }
}
