package services

import javax.inject.Inject
import repositories.CassandraRepository
import cache.{LocalCache, RedisCache}
import play.api.libs.json._
import play.api.Logger

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/**
 * Customer profile service implementing a robust 3-tier caching strategy with
 * in-flight request deduplication to prevent cache stampedes and duplicate
 * backend calls.
 *
 * Cache hierarchy (fastest → slowest):
 * 1. **L1**: `LocalCache` (in-memory Caffeine, JVM-local, ~1min TTL)
 * 2. **L2**: `RedisCache` (distributed RedisClientPool, 1hr TTL)
 * 3. **L3**: `CassandraRepository` (Amazon Keyspaces, source of truth)
 *
 * Key features:
 * - **In-flight deduplication**: Concurrent requests share the same backend call
 * - **Graceful degradation**: L1/L2 failures don't block Cassandra access
 * - **Best-effort cache warming**: Async L2 updates after L3 success
 * - **Error resilience**: Always returns valid JSON (success or error object)
 */
class CustomerProfileService @Inject()(
                                        cassRepo: CassandraRepository,
                                        l1: LocalCache[String, JsValue],
                                        l2: RedisCache
                                      )(implicit ec: ExecutionContext) {

  /** Structured logger for cache hits, misses, and backend errors. */
  private val logger = Logger(this.getClass)

  /** Redis L2 TTL: 1 hour (3600 seconds) for customer profile data. */
  private val redisTtlSeconds = 3600

  /**
   * Thread-safe map for in-flight request deduplication.
   * Maps `cacheKey → shared Future[JsValue]` for concurrent requests.
   * Prevents thundering herd / cache stampede on cache misses.
   */
  private val inflight =
    new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  /**
   * Public API: Retrieves complete customer profile as JSON.
   *
   * Execution flow:
   * 1. **L1 hit** → immediate synchronous return
   * 2. **Join in-flight** → share existing backend call
   * 3. **L2 hit** → populate L1, return
   * 4. **Cassandra hit** → populate L1+L2 (async), return
   * 5. **Cassandra miss** → cache "not-found" in L1 (short TTL)
   * 6. **Any error** → return standardized error JSON
   *
   * @param customerId Numeric customer identifier
   * @return Future containing profile JSON or standardized error object
   */
  def getCustomerProfile(customerId: Long): Future[JsValue] = {
    val key = s"profile:$customerId"

    // ========== L1 CACHE (SYNCHRONOUS FAST PATH) ==========
    l1.get(key) match {
      case Some(json) =>
        logger.debug(s"L1 cache hit for $key")
        Future.successful(json)

      case None =>
        // ========== IN-FLIGHT DEDUPLICATION ==========
        inflight.get(key) match {
          case Some(existingFuture) =>
            logger.debug(s"Joining in-flight request for $key")
            existingFuture.recover { case ex =>
              logger.warn(s"In-flight request failed for $key: ${ex.getMessage}")
              Json.obj("error" -> "service_error")
            }

          case None =>
            // ========== CREATE SHARED PROMISE ==========
            val promise = Promise[JsValue]()

            inflight.putIfAbsent(key, promise.future) match {
              case Some(existing) =>
                logger.debug(s"Race condition: using existing in-flight for $key")
                existing

              case None =>
                // ========== WE OWN THE PIPELINE ==========
                logger.debug(s"Cache miss for $key; checking Redis (L2)")

                val redisFuture: Future[Option[JsValue]] =
                  l2.getJson(key).recover { case ex =>
                    logger.warn(s"Redis GET failed for $key: ${ex.getMessage}")
                    None
                  }

                /**
                 * Main cache pipeline: L2 → Cassandra → Cache warming.
                 * All L1/L2 operations are best-effort (fire-and-forget).
                 */
                val pipelineF: Future[JsValue] = redisFuture.flatMap {
                  case Some(jsonFromRedis) =>
                    // ========== L2 HIT ==========
                    logger.debug(s"L2 cache hit for $key")

                    // Populate L1 (best-effort)
                    try l1.put(key, jsonFromRedis)
                    catch { case ex if NonFatal(ex) =>
                      logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                    }

                    Future.successful(jsonFromRedis)

                  case None =>
                    // ========== L2 MISS → CASSANDRA ==========
                    logger.debug(s"L2 miss for $key; querying Cassandra")

                    cassRepo.getProfile(customerId).map {
                      case Some(row) =>
                        // Convert row → JSON (prefer repo method, fallback to safe converter)
                        val json = try {
                          cassRepo.rowToJson(row)
                        } catch {
                          case _: Throwable =>
                            safeRowToJson(row)
                        }

                        // ========== WARM L1 CACHE ==========
                        try l1.put(key, json)
                        catch { case ex if NonFatal(ex) =>
                          logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                        }

                        // ========== ASYNC WARM L2 CACHE ==========
                        l2.setJson(key, json, redisTtlSeconds).onComplete {
                          case Success(true) =>
                            logger.debug(s"Redis update OK for $key")
                          case Success(false) =>
                            logger.warn(s"Redis SET returned false for $key")
                          case Failure(ex) =>
                            logger.warn(s"Redis SET failed for $key: ${ex.getMessage}")
                        }

                        json  // Return immediately

                      case None =>
                        // ========== NOT FOUND ==========
                        val notFound = Json.obj("error" -> "not-found")

                        // Cache not-found (short TTL) to avoid repeated Cassandra hits
                        try l1.put(key, notFound)
                        catch { case ex if NonFatal(ex) =>
                          logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                        }

                        notFound
                    }.recover { case ex =>
                      // ========== CASSANDRA ERROR ==========
                      logger.error(
                        s"Cassandra query failed for customerId=$customerId: ${ex.getMessage}",
                        ex
                      )
                      Json.obj("error" -> "service_error")
                    }
                }

                // ========== CLEANUP IN-FLIGHT MAP ==========
                pipelineF.onComplete { result =>
                  inflight.remove(key)
                  promise.complete(result)
                }

                promise.future
            }
        }
    }
  }

  /**
   * Fallback Cassandra Row → JSON converter for safe serialization.
   *
   * Handles all common Cassandra data types with defensive null/type checking:
   * - Primitives (Boolean, Number, String, Instant)
   * - Collections (Java List → JsArray, Java Map → JsObject)
   * - Unknown types → stringified fallback
   *
   * @param row Cassandra Row from query result
   * @return JsObject representation of all row columns
   */
  private def safeRowToJson(row: com.datastax.oss.driver.api.core.cql.Row): JsObject = {
    import scala.jdk.CollectionConverters._

    val defs = row.getColumnDefinitions.asScala
    val fields = defs.map { cd =>
      val colName = cd.getName.asInternal()
      val value   = row.getObject(colName)

      val jsValue: JsValue = value match {
        case null                     => JsNull
        case b: java.lang.Boolean     => JsBoolean(b)
        case n: java.lang.Number      => JsNumber(BigDecimal(n.toString))
        case s: String                => JsString(s)
        case inst: java.time.Instant  => JsString(inst.toString)

        case list: java.util.List[_] =>
          JsArray(list.asScala.map {
            case null        => JsNull
            case n: Number   => JsNumber(BigDecimal(n.toString))
            case b: Boolean  => JsBoolean(b)
            case x           => JsString(x.toString)
          }.toSeq)

        case map: java.util.Map[_, _] =>
          JsObject(map.asScala.map { case (k, v) =>
            val jsV = v match {
              case null        => JsNull
              case n: Number   => JsNumber(BigDecimal(n.toString))
              case b: Boolean  => JsBoolean(b)
              case x           => JsString(x.toString)
            }
            k.toString -> jsV
          }.toMap)

        case other => JsString(other.toString)
      }

      colName -> jsValue
    }

    JsObject(fields.toSeq)
  }
}
