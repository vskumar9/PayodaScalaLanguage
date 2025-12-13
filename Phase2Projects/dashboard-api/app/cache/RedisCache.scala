package cache

import javax.inject.{Inject, Singleton}
import com.redis.RedisClientPool
import play.api.{Configuration, Logger}
import play.api.libs.json._
import scala.util.{Try, Success => TrySuccess, Failure => TryFailure}
import scala.concurrent.{ExecutionContext, Future, blocking}

/**
 * Redis-based distributed cache layer (L2 cache) for JSON-serialized data,
 * now using RedisClientPool (thread-safe).
 */
@Singleton
class RedisCache @Inject() (
                             config: Configuration,
                             pool: RedisClientPool      // <-- replaced RedisClient with pool
                           )(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /** Redis enabled flag */
  private val redisEnabled: Boolean =
    config.getOptional[Boolean]("app.redis.enabled").getOrElse(false)

  private val maxAttempts      = 3
  private val retryDelayMillis = 100L

  // ----------
  // Helpers
  // ----------

  private def sleep(ms: Long): Unit = Thread.sleep(ms)

  /** Retry logic for Redis transient errors */
  private def withRetries[T](op: => T): T = {
    def loop(attempt: Int): T = {
      try op
      catch {
        case ex: Throwable if attempt < maxAttempts =>
          logger.warn(s"Redis transient error (attempt $attempt/$maxAttempts): ${ex.getMessage}")
          sleep(retryDelayMillis * attempt)
          loop(attempt + 1)

        case ex: Throwable =>
          logger.error(s"Redis operation failed after $maxAttempts attempts: ${ex.getMessage}", ex)
          throw ex
      }
    }
    loop(1)
  }

  // -------------------
  // Get JSON
  // -------------------

  def getJson(key: String): Future[Option[JsValue]] =
    if (!redisEnabled) Future.successful(None)
    else Future {
      Try {
        pool.withClient { client =>
          blocking { client.get(key) }  // Option[String]
        }
      } match {
        case TrySuccess(Some(s)) =>
          Try(Json.parse(s)) match {
            case TrySuccess(js) => Some(js)
            case TryFailure(ex) =>
              logger.warn(s"JSON parse failed for Redis key '$key': ${ex.getMessage}")
              None
          }

        case TrySuccess(None) =>
          None

        case TryFailure(ex) =>
          logger.warn(s"Redis GET failed for key '$key': ${ex.getMessage}")
          None
      }
    }

  // -------------------
  // Set JSON (SETEX or SET)
  // -------------------

  def setJson(key: String, value: JsValue, ttlSec: Int): Future[Boolean] =
    if (!redisEnabled) Future.successful(true)
    else Future {
      val jsonStr = Json.stringify(value)

      Try {
        withRetries {
          pool.withClient { client =>
            blocking {
              if (ttlSec > 0) client.setex(key, ttlSec, jsonStr)
              else client.set(key, jsonStr)
            }
          }
        }
      } match {
        case TrySuccess(ok) =>
          if (!ok)
            logger.warn(s"Redis SET returned false for key '$key' (ttl=$ttlSec)")
          ok

        case TryFailure(ex) =>
          logger.error(s"Redis SET failed for key '$key' (ttl=$ttlSec): ${ex.getMessage}", ex)
          false
      }
    }

  // -------------------
  // Delete key
  // -------------------

  def del(key: String): Future[Boolean] =
    if (!redisEnabled) Future.successful(false)
    else Future {
      Try {
        pool.withClient { client =>
          blocking { client.del(key) } // Option[Long]
        }
      } match {
        case TrySuccess(Some(_)) => true
        case TrySuccess(None)    => false

        case TryFailure(ex) =>
          logger.warn(s"Redis DEL failed for key '$key': ${ex.getMessage}")
          false
      }
    }

  // -------------------
  // Set TTL
  // -------------------

  def expire(key: String, ttlSec: Int): Future[Boolean] =
    if (!redisEnabled) Future.successful(false)
    else Future {
      Try {
        pool.withClient { client =>
          blocking { client.expire(key, ttlSec) } // Boolean
        }
      } match {
        case TrySuccess(b) => b

        case TryFailure(ex) =>
          logger.warn(s"Redis EXPIRE failed for key '$key' (ttl=$ttlSec): ${ex.getMessage}")
          false
      }
    }
}
