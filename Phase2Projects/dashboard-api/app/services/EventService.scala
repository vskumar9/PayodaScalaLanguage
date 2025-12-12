package services

import javax.inject.Inject
import repositories.S3Repository
import cache.{LocalCache, RedisCache}
import play.api.libs.json._
import play.api.Logger

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Failure, Success}
import java.time.LocalDate

/**
 * Behavioral events service with multi-layer caching.
 *
 * Implements 3-tier caching + in-flight deduplication for customer events:
 *  1. L1: LocalCache (in-memory, JVM-local)
 *  2. L2: RedisCache (distributed, 5s TTL for frequently changing events)
 *  3. L3: S3Repository (lakehouse Parquet files)
 *
 * Prevents cache stampedes by sharing S3 scans across concurrent identical requests.
 */
class EventService @Inject() (
                    s3Repo: S3Repository,
                    l1: LocalCache[String, JsValue],
                    l2: RedisCache,
                  )(implicit ec: ExecutionContext) {

  /** Logger for cache operations and S3 query performance. */
  private val logger = Logger(this.getClass)

  /** Short Redis TTL for events (1 Day) due to high-velocity data. */
  private val redisTtlSeconds: Int = 86400 // 24 hours = 60*60*24 //5

  /**
   * In-flight deduplication map for expensive S3 partition scans.
   * Concurrent requests for same (customer, date-range, limit) share one scan.
   */
  private val inflight = new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  /**
   * Public API: Retrieves customer behavioral events as JSON.
   *
   * Always returns valid `JsValue` (events array or error object).
   *
   * Cache flow:
   * 1. L1 hit → immediate return
   * 2. Join in-flight S3 scan if active
   * 3. L2 hit → populate L1, return
   * 4. S3 scan → populate L1+L2, return
   *
   * @param customerId Customer identifier to filter
   * @param from       Start date (YYYY-MM-DD)
   * @param to         End date (YYYY-MM-DD)
   * @param limit      Maximum events to return
   * @return Future[JsValue] with `{ "events": [...] }` or error JSON
   */
  def getEvents(customerId: Long, from: String, to: String, limit: Int): Future[JsValue] = {
    val key = s"events:$customerId:$from:$to:$limit"

    // ---- 1) Check L1 cache
    l1.get(key) match {
      case Some(v) =>
        logger.debug(s"L1 events cache hit for $key")
        return Future.successful(v)
      case None => // proceed
    }

    // ---- 2) If a request is already in-flight for this key, join it
    inflight.get(key) match {
      case Some(existingFuture) =>
        logger.debug(s"Joining in-flight getEvents request for $key")
        return existingFuture.recover { case _ =>
          Json.obj("error" -> "service_error")
        }

      case None => // continue
    }

    // ---- 3) Create a promise for this request
    val p = Promise[JsValue]()
    inflight.put(key, p.future)

    // ---- 4) Execute pipeline (Redis → S3)
    val pipelineF = fetchWithCacheLayers(key, customerId, from, to, limit)

    // complete + remove from inflight map
    pipelineF.onComplete { result =>
      inflight.remove(key)
      p.complete(result)
    }

    p.future
  }

  /**
   * Internal cache pipeline: L2 Redis → S3 lakehouse fallback.
   *
   * Composes the full cache miss path with L1/L2 population.
   *
   * @param key        Cache key for logging
   * @param customerId Customer filter
   * @param from       Date range start
   * @param to         Date range end
   * @param limit      Event limit
   * @return Future[JsValue] with events JSON or error
   */
  private def fetchWithCacheLayers(
                                    key: String,
                                    customerId: Long,
                                    from: String,
                                    to: String,
                                    limit: Int
                                  ): Future[JsValue] = {

    // ---- Try L2 (Redis)
    logger.debug(s"L2 cache lookup for $key")

    l2.getJson(key).flatMap {
      case Some(json) =>
        logger.debug(s"L2 events cache hit for $key")
        try l1.put(key, json) catch {
          case ex: Throwable => logger.warn(s"L1.put failed for events $key: ${ex.getMessage}")
        }
        Future.successful(json)

      case None =>
        // ---- L2 miss → fetch from S3
        logger.debug(s"L2 miss for events $key; querying S3 Repository")

        val dates = buildDateRange(from, to)

        s3Repo.getEvents(dates, customerId, limit)
          .map { events =>

            val json = Json.obj("events" -> Json.toJson(events))

            // Update caches
            try l1.put(key, json) catch {
              case ex: Throwable => logger.warn(s"L1.put failed for events $key: ${ex.getMessage}")
            }

            l2.setJson(key, json, ttlSec = redisTtlSeconds).onComplete {
              case Success(true)  => logger.debug(s"Redis SET success for $key")
              case Success(false) => logger.warn(s"Redis SET returned false for $key")
              case Failure(ex)    => logger.warn(s"Redis SET failed for $key: ${ex.getMessage}")
            }

            json
          }
          .recover { case ex =>
            logger.error(
              s"S3 getEvents failed for customer=$customerId from=$from to=$to: ${ex.getMessage}",
              ex
            )
            Json.obj("error" -> "service_error", "msg" -> ex.getMessage)
          }
    }
  }

  /**
   * Generates inclusive date range from `from` to `to` as YYYY-MM-DD strings.
   *
   * @param from Start date (YYYY-MM-DD)
   * @param to   End date (YYYY-MM-DD)
   * @return Seq of all dates in range
   */
  private def buildDateRange(from: String, to: String): Seq[String] = {
    val start = LocalDate.parse(from)
    val end = LocalDate.parse(to)

    Iterator
      .iterate(start)(_.plusDays(1))
      .takeWhile(!_.isAfter(end))
      .map(_.toString)
      .toSeq
  }
}
