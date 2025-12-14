package cache

import javax.inject.{Inject, Singleton}
import com.redis.RedisClientPool
import play.api.{Configuration, Logger}
import play.api.libs.json._
import scala.util.{Try, Success => TrySuccess, Failure => TryFailure}
import scala.concurrent.{ExecutionContext, Future, blocking}

/**
 * Redis-based distributed cache layer (L2 cache) for JSON-serialized data.
 *
 * This implementation uses a Redis client connection pool to ensure thread‑safe access
 * from multiple Play request threads while avoiding the overhead of creating clients
 * per operation.
 *
 * Behaviour:
 * - Respects a feature flag `app.redis.enabled` from configuration to allow
 *   graceful disablement in development or testing environments.
 * - Uses JSON serialization (Play JSON) for values, with safe parse / stringify.
 * - Applies simple retry logic for transient network errors on write operations.
 * - Logs all failures and unusual conditions via Play's structured logger.
 *
 * Typical usage:
 *   - as L2 cache behind a JVM‑local L1 cache (e.g. Caffeine)
 *   - key space like `profile:<id>`, `summary:<date>:<id>`, etc.
 */
@Singleton
class RedisCache @Inject() (
                             config: Configuration,
                             pool: RedisClientPool
                           )(implicit ec: ExecutionContext) {

  /** Logger for Redis cache operations (hits, misses, failures). */
  private val logger = Logger(this.getClass)

  /** Global toggle to enable/disable Redis usage via `app.redis.enabled` config flag. */
  private val redisEnabled: Boolean =
    config.getOptional[Boolean]("app.redis.enabled").getOrElse(false)

  /** Maximum number of retry attempts for transient Redis failures. */
  private val maxAttempts      = 3

  /** Base delay in milliseconds used for linear backoff between retries. */
  private val retryDelayMillis = 100L

  // ----------
  // Helpers
  // ----------

  /** Blocking sleep helper used only inside retry logic for simple backoff. */
  private def sleep(ms: Long): Unit = Thread.sleep(ms)

  /**
   * Executes the given operation with retry support for transient failures.
   *
   * The operation is retried up to `maxAttempts` times, with a small backoff
   * between attempts. If all attempts fail, the final exception is logged and
   * re‑thrown to the caller.
   *
   * This helper is intended for short, blocking Redis calls that are already
   * wrapped inside an appropriate blocking context.
   *
   * @param op  Operation to execute (e.g. a Redis command)
   * @tparam T  Result type of the operation
   * @return    Result of the first successful attempt
   * @throws    Throwable when all attempts fail
   */
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

  /**
   * Fetches a JSON value from Redis by key.
   *
   * Behaviour:
   * - Returns `None` immediately when Redis is disabled via config.
   * - Safely wraps network and parsing errors, logging them and returning `None`.
   *
   * @param key  Redis key under which the JSON string is stored
   * @return     Future `Some(JsValue)` on cache hit, `None` on miss, parse failure,
   *             or when Redis is disabled or unreachable.
   */
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

  /**
   * Stores a JSON value in Redis using either `SETEX` (with TTL) or plain `SET`.
   *
   * Behaviour:
   * - When `ttlSec > 0`, uses `SETEX` to atomically set the value and expiry.
   * - When `ttlSec <= 0`, uses `SET` and leaves the key without expiry.
   * - When Redis is disabled, this method returns `true` without performing any I/O,
   *   so upstream services can continue to function without an L2 cache.
   *
   * @param key     Redis key to write
   * @param value   JSON value to be serialized and stored
   * @param ttlSec  Time‑to‑live in seconds (0 or negative for no expiry)
   * @return        Future `true` when write is acknowledged by Redis or when Redis
   *                is disabled, `false` when the command fails or returns false.
   */
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

  /**
   * Deletes a key from Redis if it exists.
   *
   * @param key  Redis key to remove
   * @return     Future `true` when at least one key was deleted,
   *             `false` when no key was removed, Redis is disabled,
   *             or the delete operation fails.
   */
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

  /**
   * Updates the TTL (time‑to‑live) of an existing key.
   *
   * Behaviour:
   * - Delegates to the Redis `EXPIRE` command.
   * - Does nothing and returns `false` when Redis is disabled.
   *
   * @param key     Redis key whose TTL should be changed
   * @param ttlSec  New TTL in seconds
   * @return        Future `true` when Redis acknowledges the TTL change,
   *                `false` when the key does not exist, Redis is disabled,
   *                or the operation fails.
   */
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
