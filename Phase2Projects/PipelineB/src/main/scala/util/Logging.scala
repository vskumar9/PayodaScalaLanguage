package util

import org.slf4j.LoggerFactory

/**
 * Mixin trait providing SLF4J logging functionality to classes and objects.
 *
 * This trait automatically creates a logger instance named after the concrete class
 * implementing or extending it. It centralizes logging configuration across the
 * pipeline components and follows standard Scala/SLF4J best practices.
 *
 * Usage: `class MyClass extends Logging { ... }` or `object MyObject extends Logging`
 *
 * The logger supports all standard SLF4J levels: trace, debug, info, warn, error.
 */
trait Logging {

  /**
   * Thread-safe SLF4J logger instance automatically named after the implementing class.
   *
   * The logger name is derived from `this.getClass.getName`, providing clear log
   * attribution in multi-class applications (e.g., `[pipelines.jobs.PipelineBJob] INFO ...`).
   *
   * Protected to prevent reassignment while allowing subclass access.
   */
  protected val logger = LoggerFactory.getLogger(this.getClass)
}
