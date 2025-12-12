package cache

import javax.inject.{Inject, Singleton}
import com.redis.RedisClient
import play.api.{Configuration, Logger}
import play.api.libs.json._
import scala.util.{Try, Success => TrySuccess, Failure => TryFailure}
import scala.concurrent.{ExecutionContext, Future, blocking}

/**
 * Redis-based distributed cache layer (L2 cache) for JSON-serialized data.
 *
 * Features:
 * - Graceful fallback when Redis is disabled via config (app.redis.enabled = false).
 * - Uses SETEX when ttlSec > 0 for atomic set+expire.
 * - Simple retry logic for transient network failures.
 * - Play Logger integration for structured logging.
 */
@Singleton
class RedisCache @Inject() (
                             config: Configuration,
                             client: RedisClient
                           )(implicit ec: ExecutionContext) {

  /** Structured logger for Redis operations. */
  private val logger = Logger(this.getClass)

  /** Redis enabled from config (app.redis.enabled). */
  private val redisEnabled: Boolean =
    config.getOptional[Boolean]("app.redis.enabled").getOrElse(false)

  private val maxAttempts      = 3
  private val retryDelayMillis = 100L

  // -----------------------
  // Helpers
  // -----------------------

  /** Blocking sleep for retry backoff. */
  private def sleepMillis(ms: Long): Unit = Thread.sleep(ms)

  /**
   * Retries an operation up to `maxAttempts` with linear backoff.
   *
   * Only used when Redis is enabled; caller must guard with redisEnabled.
   */
  private def withRetries[T](op: => T): T = {
    def loop(attempt: Int): T = {
      try op
      catch {
        case ex: Throwable if attempt < maxAttempts =>
          logger.warn(s"Redis transient error (attempt $attempt/${maxAttempts}): ${ex.getMessage}")
          sleepMillis(retryDelayMillis * attempt)
          loop(attempt + 1)
        case ex: Throwable =>
          logger.error(s"Redis operation failed after ${maxAttempts} attempts: ${ex.getMessage}", ex)
          throw ex
      }
    }
    loop(1)
  }

  // -----------------------
  // Get JSON
  // -----------------------

  /**
   * Retrieve JSON value from Redis by key.
   *
   * @param key Redis key
   * @return Future[Option[JsValue]] - None if key missing, parse fails, or Redis disabled.
   */
  def getJson(key: String): Future[Option[JsValue]] =
    if (!redisEnabled) {
      Future.successful(None)
    } else {
      Future {
        Try {
          blocking { client.get(key) } // Option[String]
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
    }

  // -----------------------
  // Set JSON with TTL
  // -----------------------

  /**
   * Store JSON value with optional TTL.
   *
   * - If ttlSec > 0: uses SETEX (atomic set+expire).
   * - If ttlSec <= 0: uses plain SET (no expiry).
   *
   * @param key     Redis key
   * @param value   JSON value to store
   * @param ttlSec  Time-to-live in seconds (0 = no expiry)
   * @return Future[Boolean] true if SET succeeded, or always true if Redis disabled.
   */
  def setJson(key: String, value: JsValue, ttlSec: Int): Future[Boolean] =
    if (!redisEnabled) {
      // In dev mode without Redis, pretend success so callers don't fail.
      Future.successful(true)
    } else {
      Future {
        val str = Json.stringify(value)

        Try {
          blocking {
            val ok =
              if (ttlSec > 0) {
                withRetries {
                  client.setex(key, ttlSec, str)
                }
              } else {
                withRetries {
                  client.set(key, str)
                }
              }

            if (!ok) {
              logger.warn(s"Redis SET returned false for key '$key' (ttl=${ttlSec}s)")
            }
            ok
          }
        } match {
          case TrySuccess(result) => result
          case TryFailure(ex) =>
            logger.error(s"Redis SET failed for key '$key' (ttl=${ttlSec}s): ${ex.getMessage}", ex)
            false
        }
      }
    }

  // -----------------------
  // Delete / TTL helpers
  // -----------------------

  /**
   * Delete a key from Redis.
   *
   * @param key Redis key
   * @return Future[Boolean] true if key existed and was deleted, false otherwise.
   */
  def del(key: String): Future[Boolean] =
    if (!redisEnabled) {
      Future.successful(false)
    } else {
      Future {
        Try {
          blocking { client.del(key) } // Option[Long]
        } match {
          case TrySuccess(Some(_)) => true
          case TrySuccess(None)    => false
          case TryFailure(ex) =>
            logger.warn(s"Redis DEL failed for key '$key': ${ex.getMessage}")
            false
        }
      }
    }

  /**
   * Set TTL on an existing key.
   *
   * @param key    Redis key
   * @param ttlSec TTL in seconds
   * @return Future[Boolean] true if EXPIRE succeeded.
   */
  def expire(key: String, ttlSec: Int): Future[Boolean] =
    if (!redisEnabled) {
      Future.successful(false)
    } else {
      Future {
        Try {
          blocking { client.expire(key, ttlSec) } // Boolean
        } match {
          case TrySuccess(b) => b
          case TryFailure(ex) =>
            logger.warn(s"Redis EXPIRE failed for key '$key' (ttl=${ttlSec}s): ${ex.getMessage}")
            false
        }
      }
    }
}
