package utils

/**
 * Lightweight, Spark-safe logging trait for pipeline components.
 *
 * Provides SLF4J logger with by-name parameters for performance and transient
 * serialization protection. Designed for Structured Streaming applications.
 *
 * Usage:
 * ```
 * class Foo extends AppLogging { logger.info("hello") }
 * object Bar extends AppLogging { logger.debug("debug") }
 * ```
 *
 * Key features:
 * - `@transient lazy val` prevents Spark serialization issues
 * - By-name parameters (`=> String`) avoid string construction on skipped logs
 * - Convenience methods reduce boilerplate
 * - Thread-safe across streaming micro-batches
 */
trait AppLogging {
  /**
   * Thread-safe, serialization-safe SLF4J logger instance.
   *
   * @transient prevents Spark RDD/Streaming serialization of logger state
   * @lazy def defers instantiation until first log call
   * getClass.getName provides class-specific logger name automatically
   */
  @transient
  protected lazy val logger: org.slf4j.Logger =
  org.slf4j.LoggerFactory.getLogger(this.getClass.getName)

  /**
   * Convenience info wrapper with by-name parameter.
   *
   * String evaluation only occurs if INFO level enabled, preventing
   * unnecessary string construction in hot paths.
   */
  protected def info(msg: => String): Unit = logger.info(msg)

  /**
   * Convenience debug wrapper with by-name parameter.
   *
   * Critical for streaming diagnostics without performance penalty.
   */
  protected def debug(msg: => String): Unit = logger.debug(msg)

  /**
   * Convenience warn wrapper with by-name parameter.
   *
   * Used for malformed events and non-fatal streaming issues.
   */
  protected def warn(msg: => String): Unit = logger.warn(msg)

  /**
   * Convenience error wrapper (message only) with by-name parameter.
   */
  protected def error(msg: => String): Unit = logger.error(msg)

  /**
   * Convenience error wrapper with exception.
   *
   * Logs full stack trace for root cause analysis in foreachBatch handlers.
   *
   * @param msg Lazy-evaluated error message
   * @param t Throwable to include in structured log output
   */
  protected def error(msg: => String, t: Throwable): Unit =
    logger.error(msg, t)
}
