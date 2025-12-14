package services

import javax.inject.Inject
import repositories.S3Repository
import cache.{LocalCache, RedisCache}
import play.api.libs.json._
import play.api.Logger

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Failure, Success}
import java.time.LocalDate
import scala.util.control.NonFatal

/**
 * Behavioral events service implementing multi-layer caching with in-flight
 * deduplication for S3 Parquet scans.
 *
 * Cache hierarchy (fastest → slowest):
 * 1. **L1**: `LocalCache` (in-memory Caffeine, JVM-local)
 * 2. **L2**: `RedisCache` (distributed RedisClientPool, 1hr TTL)
 * 3. **L3**: `S3Repository` (Parquet scans across date-partitioned lakehouse)
 *
 * Key features:
 * - **Date-range aware**: Automatically expands `[from,to]` into daily S3 partitions
 * - **In-flight deduplication**: Prevents duplicate S3 scans for identical parameters
 * - **Granular cache keys**: `events:<customerId>:<from>:<to>:<limit>`
 * - **Graceful degradation**: L1/L2 failures don't block S3 access
 * - **Best-effort cache warming**: Async L2 population after S3 success
 */
class EventService @Inject()(
                              s3Repo: S3Repository,
                              l1: LocalCache[String, JsValue],
                              l2: RedisCache
                            )(implicit ec: ExecutionContext) {

  /** Structured logger for cache operations and S3 scan errors. */
  private val logger = Logger(this.getClass)

  /** Redis L2 TTL: 1 hour for event query results. */
  private val redisTtlSeconds = 3600

  /**
   * Thread-safe map for in-flight S3 scan deduplication.
   * Maps `events:<customerId>:<from>:<to>:<limit>` → shared `Future[JsValue]`.
   * Prevents thundering herd of concurrent identical S3 Parquet scans.
   */
  private val inflight =
    new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  /**
   * Public API: Retrieves behavioral events for customer within date range.
   *
   * Execution flow:
   * 1. **L1 hit** → immediate return (fastest path)
   * 2. **Join in-flight** → share existing S3 scan
   * 3. **L2 hit** → populate L1, return
   * 4. **S3 scan** → scan date-partitioned Parquet, populate L1+L2 (async), return
   * 5. **Any error** → standardized error JSON
   *
   * @param customerId Customer identifier for event filtering
   * @param from       Start date (YYYY-MM-DD, inclusive)
   * @param to         End date (YYYY-MM-DD, inclusive)
   * @param limit      Maximum events to return
   * @return Future containing JSON array of events or error object
   */
  def getEvents(customerId: Long, from: String, to: String, limit: Int): Future[JsValue] = {
    val key = s"events:$customerId:$from:$to:$limit"

    // ========== L1 CACHE (SYNCHRONOUS FAST PATH) ==========
    l1.get(key) match {
      case Some(json) =>
        logger.debug(s"L1 events cache hit for $key")
        Future.successful(json)

      case None =>
        // ========== IN-FLIGHT DEDUPLICATION ==========
        inflight.get(key) match {
          case Some(existingFuture) =>
            logger.debug(s"Joining in-flight getEvents request for $key")
            existingFuture.recover { case ex =>
              logger.warn(s"In-flight events request failed for $key: ${ex.getMessage}")
              Json.obj("error" -> "service_error")
            }

          case None =>
            // ========== CREATE SHARED PROMISE ==========
            val promise = Promise[JsValue]()
            inflight.put(key, promise.future)

            // ========== EXECUTE CACHE PIPELINE ==========
            val pipelineF = fetchWithCacheLayers(key, customerId, from, to, limit)

            // Cleanup inflight map on completion
            pipelineF.onComplete { result =>
              inflight.remove(key)
              promise.complete(result)
            }

            promise.future
        }
    }
  }

  /**
   * Core cache pipeline implementation: L2 Redis → S3 Parquet scan → Cache warming.
   *
   * 1. **Redis L2 lookup** (distributed cache hit)
   * 2. **S3 scan** across daily partitions (expensive operation)
   * 3. **Best-effort L1/L2 warming** after successful S3 scan
   *
   * @param key        Cache key for L1/L2
   * @param customerId Filter for customer events
   * @param from       Start date for partition scanning
   * @param to         End date for partition scanning
   * @param limit      Maximum events to scan/return
   * @return Future containing events JSON or error object
   */
  private def fetchWithCacheLayers(
                                    key: String,
                                    customerId: Long,
                                    from: String,
                                    to: String,
                                    limit: Int
                                  ): Future[JsValue] = {

    logger.debug(s"L2 Redis lookup for events key: $key")

    l2.getJson(key).flatMap {
      case Some(json) =>
        // ========== L2 CACHE HIT ==========
        logger.debug(s"L2 events cache hit for $key")

        // Best-effort L1 population
        try l1.put(key, json)
        catch { case ex if NonFatal(ex) =>
          logger.warn(s"L1.put failed for events $key: ${ex.getMessage}")
        }

        Future.successful(json)

      case None =>
        // ========== L2 MISS → S3 PARQUET SCAN ==========
        logger.debug(s"L2 miss for $key; scanning S3 partitions")

        // Build date range for partition scanning: lakeevents/eventdate=YYYY-MM-DD/
        val dates = buildDateRange(from, to)

        s3Repo.getEvents(dates, customerId, limit).map { events =>
          // Convert events → JSON array wrapper
          val json = Json.obj("events" -> Json.toJson(events))

          // ========== POPULATE L1 CACHE ==========
          try l1.put(key, json)
          catch { case ex if NonFatal(ex) =>
            logger.warn(s"L1.put failed for events $key: ${ex.getMessage}")
          }

          // ========== ASYNC POPULATE L2 CACHE ==========
          l2.setJson(key, json, redisTtlSeconds).onComplete {
            case Success(true) =>
              logger.debug(s"Redis SET success for events $key")
            case Success(false) =>
              logger.warn(s"Redis SET returned false for events $key")
            case Failure(ex) =>
              logger.warn(s"Redis SET failed for events $key: ${ex.getMessage}")
          }

          json  // Return immediately to client

        }.recover { case ex =>
          // ========== S3 SCAN ERROR ==========
          logger.error(
            s"S3 getEvents failed for customer=$customerId from=$from to=$to limit=$limit: ${ex.getMessage}",
            ex
          )
          Json.obj("error" -> "service_error", "msg" -> ex.getMessage)
        }
    }
  }


/**
 * Expands date range `[from, to]` into inclusive sequence of daily partitions.
 *
 * Example: `from="2025-12-10", to="2025-12-12"` → `["2025-12-10", "2025-12-11", "2025-12-12"]`
 *
 * Used to construct S3 paths: `lakeevents/eventdate=YYYY-MM-DD/***.parquet`
 *
 * @param from Start date (YYYY-MM-DD)
 * @param to   End date (YYYY-MM-DD)
 * @return Sequence of partition values for S3Repository scanning
 */
 */
  private def buildDateRange(from: String, to: String): Seq[String] = {
    val start = LocalDate.parse(from)
    val end   = LocalDate.parse(to)

    Iterator
      .iterate(start)(_.plusDays(1))
      .takeWhile(!_.isAfter(end))
      .map(_.toString)
      .toSeq
  }
}



