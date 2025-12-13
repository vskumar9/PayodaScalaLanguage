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
 * Customer profile service with multi-layer caching strategy:
 *  - L1 LocalCache
 *  - L2 RedisCache (RedisClientPool)
 *  - L3 Cassandra
 */
class CustomerProfileService @Inject()(
                                        cassRepo: CassandraRepository,
                                        l1: LocalCache[String, JsValue],
                                        l2: RedisCache
                                      )(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /** Redis TTL: 1 hour */
  private val redisTtlSeconds = 3600

  /** In-flight deduplication map */
  private val inflight =
    new scala.collection.concurrent.TrieMap[String, Future[JsValue]]()

  /**
   * Public API: Retrieve customer profile JSON.
   */
  def getCustomerProfile(customerId: Long): Future[JsValue] = {
    val key = s"profile:$customerId"

    // 1) L1 lookup
    l1.get(key) match {
      case Some(json) =>
        logger.debug(s"L1 cache hit for $key")
        Future.successful(json)

      case None =>
        // 2) Join inflight request if exists
        inflight.get(key) match {
          case Some(existingFuture) =>
            logger.debug(s"Joining in-flight request for $key")
            existingFuture.recover { case ex =>
              logger.warn(s"In-flight request failed for $key: ${ex.getMessage}")
              Json.obj("error" -> "service_error")
            }

          case None =>
            // 3) Create new promise
            val promise = Promise[JsValue]()

            inflight.putIfAbsent(key, promise.future) match {
              case Some(existing) =>
                logger.debug(s"Race condition: using existing in-flight for $key")
                existing

              case None =>
                // We own the pipeline — start the cache chain
                logger.debug(s"Cache miss for $key; checking Redis (L2)")

                val redisFuture: Future[Option[JsValue]] =
                  l2.getJson(key).recover { case ex =>
                    logger.warn(s"Redis GET failed for $key: ${ex.getMessage}")
                    None
                  }

                val pipelineF: Future[JsValue] = redisFuture.flatMap {
                  case Some(jsonFromRedis) =>
                    logger.debug(s"L2 cache hit for $key")

                    // Write to L1
                    try l1.put(key, jsonFromRedis)
                    catch { case ex if NonFatal(ex) =>
                      logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                    }

                    Future.successful(jsonFromRedis)

                  case None =>
                    logger.debug(s"L2 miss for $key; querying Cassandra")

                    cassRepo.getProfile(customerId).map {
                      case Some(row) =>
                        val json = try {
                          cassRepo.rowToJson(row)
                        } catch {
                          case _: Throwable =>
                            safeRowToJson(row)
                        }

                        // Update L1
                        try l1.put(key, json)
                        catch { case ex if NonFatal(ex) =>
                          logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                        }

                        // Async update to Redis
                        l2.setJson(key, json, redisTtlSeconds).onComplete {
                          case Success(true) =>
                            logger.debug(s"Redis update OK for $key")
                          case Success(false) =>
                            logger.warn(s"Redis SET returned false for $key")
                          case Failure(ex) =>
                            logger.warn(s"Redis SET failed for $key: ${ex.getMessage}")
                        }

                        json

                      case None =>
                        val notFound = Json.obj("error" -> "not-found")

                        try l1.put(key, notFound)
                        catch { case ex if NonFatal(ex) =>
                          logger.warn(s"L1.put failed for $key: ${ex.getMessage}")
                        }

                        notFound
                    }.recover { case ex =>
                      logger.error(
                        s"Cassandra query failed for customerId=$customerId: ${ex.getMessage}",
                        ex
                      )
                      Json.obj("error" -> "service_error")
                    }
                }

                // Complete and clean in-flight map
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
   * Fallback Row → JSON converter (unchanged)
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
