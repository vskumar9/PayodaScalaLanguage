package services

import javax.inject.Inject
import repositories.CassandraRepository
import cache.{LocalCache, RedisCache}
import play.api.libs.json._
import play.api.Logger

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Failure, Success}

/**
 * Customer profile service with multi-layer caching strategy.
 *
 * Implements 3-tier caching with in-flight request deduplication:
 *  1. L1: LocalCache (in-memory, JVM-local, 1min TTL)
 *  2. L2: RedisCache (distributed, 60s TTL)
 *  3. L3: CassandraRepository (source of truth)
 *
 * Concurrent requests for the same customerId share the same backend call,
 * preventing cache stampedes and duplicate Cassandra queries.
 */
class CustomerProfileService @Inject() (
                              cassRepo: CassandraRepository,
                              l1: LocalCache[String, JsValue],
                              l2: RedisCache,
                            )(implicit ec: ExecutionContext) {

  /** Logger for cache hits/misses and backend errors. */
  private val logger = Logger(this.getClass)

  /** Redis TTL for profile data (1 Day). */
  private val redisTtlSeconds: Int = 86400 // 24 hours = 60*60*24 //60

  /**
   * In-flight deduplication map prevents duplicate backend calls.
   * Maps cache key → shared Future[JsValue] for concurrent requests.
   */
  private val inflight = new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  /**
   * Public API: Retrieves customer profile as JSON.
   *
   * Always returns a valid `JsValue` (success or error object).
   *
   * Cache hierarchy traversal:
   * 1. L1 hit → immediate return
   * 2. L2 hit → populate L1, return
   * 3. Cassandra hit/miss → populate L1+L2, return
   * 4. Any error → return error JSON
   *
   * @param customerId Customer identifier
   * @return Future[JsValue] containing profile JSON or error object
   */
  def getCustomerProfile(customerId: Long): Future[JsValue] = {
    val key = s"profile:$customerId"

    // 1) Try L1 (synchronous, fastest)
    l1.get(key) match {
      case Some(json) =>
        logger.debug(s"L1 cache hit for $key")
        Future.successful(json)

      case None =>
        // 2) If a backend call is already in-flight for this key, return that future
        inflight.get(key) match {
          case Some(fut) =>
            logger.debug(s"Joining in-flight request for $key")
            fut.recover { case ex =>
              logger.warn(s"In-flight future failed for $key: ${ex.getMessage}")
              Json.obj("error" -> "service_error")
            }

          case None =>
            // create a promise and put into inflight before starting external calls
            val promise = Promise[JsValue]()
            inflight.putIfAbsent(key, promise.future) match {
              case Some(existing) =>
                // another thread raced and inserted; use existing
                logger.debug(s"Race detected; using existing in-flight for $key")
                existing

              case None =>
                // We are the owner of this in-flight request; perform the full pipeline
                logger.debug(s"Cache miss for $key; checking L2 (Redis)")
                // Start Redis lookup
                val redisF = l2.getJson(key).recover {
                  case ex =>
                    logger.warn(s"Redis GET failed for $key: ${ex.getMessage}")
                    None
                }

                // Compose pipeline
                val pipelineF: Future[JsValue] = redisF.flatMap {
                  case Some(json) =>
                    // L2 hit: populate L1 and complete
                    logger.debug(s"L2 cache hit for $key")
                    try l1.put(key, json) catch {
                      case ex: Throwable => logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                    }
                    Future.successful(json)

                  case None =>
                    // L2 miss -> fetch from Cassandra
                    logger.debug(s"L2 miss for $key; querying Cassandra")
                    cassRepo.getProfile(customerId).map {
                      case Some(row) =>
                        // Prefer repository conversion util if available
                        val json = try {
                          // cassRepo provides rowToJson in the updated repo; use it
                          cassRepo.rowToJson(row)
                        } catch {
                          case _: Throwable =>
                            // fallback safe conversion
                            safeRowToJson(row)
                        }

                        // Update caches (best-effort)
                        try l1.put(key, json) catch {
                          case ex: Throwable => logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                        }

                        // Fire-and-forget update to Redis (log result)
                        l2.setJson(key, json, ttlSec = redisTtlSeconds).onComplete {
                          case Success(true) => logger.debug(s"Redis set success for $key")
                          case Success(false) => logger.warn(s"Redis set returned false for $key")
                          case Failure(ex) => logger.warn(s"Redis SET failed for $key: ${ex.getMessage}")
                        }

                        json

                      case None =>
                        val notFound = Json.obj("error" -> "not-found")
                        // Cache not-found for a short duration in L1 to avoid repeated hits
                        try l1.put(key, notFound) catch {
                          case ex: Throwable => logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                        }
                        notFound
                    }.recover { case ex =>
                      logger.error(s"Cassandra query failed for customerId=$customerId: ${ex.getMessage}", ex)
                      Json.obj("error" -> "service_error", "msg" -> ex.getMessage)
                    }
                }

                // complete the promise and clean inflight entry
                pipelineF.onComplete { result =>
                  inflight.remove(key)
                  promise.complete(result)
                }

                promise.future
            } // end putIfAbsent match
        } // end inflight.get match
    } // end L1 match
  }

  /**
   * Fallback Row-to-JSON converter for Cassandra data.
   *
   * Handles all common Cassandra types safely with string fallback.
   * Supports primitives, collections (List/Map), timestamps, decimals.
   *
   * @param row Cassandra Row from query result
   * @return JsObject representation of row columns
   */
  private def safeRowToJson(row: com.datastax.oss.driver.api.core.cql.Row): JsObject = {
    import scala.jdk.CollectionConverters._

    val defs = row.getColumnDefinitions.asScala
    val fields = defs.flatMap { cd =>
      val colName = cd.getName.asInternal()
      val value   = row.getObject(colName)

      val jsValue: JsValue = value match {
        case null                      => JsNull
        case b: java.lang.Boolean      => JsBoolean(b.booleanValue())
        case i: java.lang.Integer      => JsNumber(BigDecimal(i.intValue()))
        case l: java.lang.Long         => JsNumber(BigDecimal(l.longValue()))
        case d: java.lang.Double       => JsNumber(BigDecimal(d.doubleValue()))
        case f: java.lang.Float        => JsNumber(BigDecimal.decimal(f.floatValue()))
        case bd: java.math.BigDecimal  => JsNumber(BigDecimal(bd))
        case bi: java.math.BigInteger  => JsNumber(BigDecimal(bi))
        case s: String                 => JsString(s)
        case inst: java.time.Instant   => JsString(inst.toString)

        // Java List -> JsArray
        case list: java.util.List[_] =>
          val elems: Seq[JsValue] = list.asScala.toSeq.map {
            case null        => JsNull
            case n: Number   => JsNumber(BigDecimal(n.toString))
            case b: Boolean  => JsBoolean(b)
            case x           => JsString(x.toString)
          }
          JsArray(elems)

        // Java Map -> JsObject[String, JsValue]
        case map: java.util.Map[_, _] =>
          val scalaMap: Map[String, JsValue] = map.asScala.map {
            case (k, v) =>
              val jsV: JsValue = v match {
                case null        => JsNull
                case n: Number   => JsNumber(BigDecimal(n.toString))
                case b: Boolean  => JsBoolean(b)
                case x           => JsString(x.toString)
              }
              k.toString -> jsV
          }.toMap
          JsObject(scalaMap)

        case other => JsString(other.toString)
      }

      Some(colName -> jsValue)
    }

    JsObject(fields.toSeq)
  }


}
