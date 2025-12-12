package cache

import javax.inject.Inject
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

/**
 * A simple in-memory, thread-safe local cache with a fixed TTL per entry.
 *
 * Entries are stored in a concurrent map along with an expiration timestamp.
 * Expired entries are lazily evicted on read.
 *
 * Intended as an L1 cache (per JVM instance) to reduce calls to slower
 * downstream systems or remote caches.
 */
class LocalCache[K, V] @Inject() () {

  /**
   * Internal cache entry wrapper storing the value and its expiration time.
   *
   * @param value     The cached value.
   * @param expiresAt Epoch millis when this entry expires (<= 0 means no expiry).
   */
  private case class Entry(value: V, expiresAt: Long)

  /** Underlying concurrent storage for cache entries. */
  private val store = new TrieMap[K, Entry]()

  /**
   * Default time-to-live for each entry.
   *
   * A value of 1 minute means items are considered stale and will be evicted
   * on read after 60 seconds from insertion.
   */
  private val defaultTtl: FiniteDuration = 1.minute

  /**
   * Retrieve a value from the cache if present and not expired.
   *
   * If the entry has expired, it is removed and `None` is returned.
   *
   * @param key Cache key.
   * @return    Some(value) if present and valid, otherwise None.
   */
  def get(key: K): Option[V] = {
    val now = System.currentTimeMillis()
    store.get(key).flatMap { e =>
      if (e.expiresAt <= 0 || e.expiresAt > now) Some(e.value)
      else {
        store.remove(key)
        None
      }
    }
  }

  /**
   * Insert or update an entry in the cache using the default TTL.
   *
   * The entry will expire after [[defaultTtl]] from the time of insertion.
   *
   * @param key   Cache key.
   * @param value Value to cache.
   */
  def put(key: K, value: V): Unit = {
    val expiresAt =
      if (defaultTtl.length == 0) 0L
      else System.currentTimeMillis() + defaultTtl.toMillis
    store.put(key, Entry(value, expiresAt))
    ()
  }
}
