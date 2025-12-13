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
 * Behavioral events service with multi-layer caching.
 *
 * Cache layers:
 *  - L1 LocalCache (in-memory, fast)
 *  - L2 RedisCache (distributed, uses RedisClientPool now)
 *  - L3 S3Repository (expensive)
 *
 * In-flight deduplication prevents multiple S3 scans for identical requests.
 */
class EventService @Inject()(
                              s3Repo: S3Repository,
                              l1: LocalCache[String, JsValue],
                              l2: RedisCache
                            )(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /** Redis TTL for events (1HR) */
  private val redisTtlSeconds = 3600

  /** In-flight dedup map: key → shared Future */
  private val inflight =
    new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  /**
   * Retrieve behavioral events for a customer.
   */
  def getEvents(customerId: Long, from: String, to: String, limit: Int): Future[JsValue] = {
    val key = s"events:$customerId:$from:$to:$limit"

    // ---- 1) L1 lookup
    l1.get(key) match {
      case Some(json) =>
        logger.debug(s"L1 events cache hit for $key")
        return Future.successful(json)

      case None => // continue
    }

    // ---- 2) Join in-flight S3 scan if already running
    inflight.get(key) match {
      case Some(existingFuture) =>
        logger.debug(s"Joining in-flight getEvents request for $key")
        return existingFuture.recover { case _ =>
          Json.obj("error" -> "service_error")
        }

      case None => // continue
    }

    // ---- 3) Become owner of this request
    val promise = Promise[JsValue]()
    inflight.put(key, promise.future)

    // ---- 4) Execute Redis → S3 pipeline
    val pipelineF = fetchWithCacheLayers(key, customerId, from, to, limit)

    pipelineF.onComplete { result =>
      inflight.remove(key)
      promise.complete(result)
    }

    promise.future
  }

  /**
   * Cache pipeline:
   *  - Try Redis (L2)
   *  - Else fetch from S3 (L3) and populate Redis + L1
   */
  private def fetchWithCacheLayers(
                                    key: String,
                                    customerId: Long,
                                    from: String,
                                    to: String,
                                    limit: Int
                                  ): Future[JsValue] = {

    logger.debug(s"L2 Redis lookup for $key")

    l2.getJson(key).flatMap {
      case Some(json) =>
        logger.debug(s"L2 events cache hit for $key")
        try l1.put(key, json)
        catch { case ex if NonFatal(ex) =>
          logger.warn(s"L1.put failed for events $key: ${ex.getMessage}")
        }
        Future.successful(json)

      case None =>
        // ---- Redis miss → fetch from S3
        logger.debug(s"L2 miss for $key; querying S3")

        val dates = buildDateRange(from, to)

        s3Repo.getEvents(dates, customerId, limit)
          .map { events =>

            val json = Json.obj("events" -> Json.toJson(events))

            // Populate L1
            try l1.put(key, json)
            catch { case ex if NonFatal(ex) =>
              logger.warn(s"L1.put failed for events $key: ${ex.getMessage}")
            }

            // Fire-and-forget Redis write
            l2.setJson(key, json, redisTtlSeconds).onComplete {
              case Success(true) =>
                logger.debug(s"Redis SET success for events $key")

              case Success(false) =>
                logger.warn(s"Redis SET returned false for events $key")

              case Failure(ex) =>
                logger.warn(s"Redis SET failed for events $key: ${ex.getMessage}")
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
   * Build inclusive date range [from, to]
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
