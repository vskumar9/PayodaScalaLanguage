package util

import org.slf4j.LoggerFactory

/**
 * Mixin trait providing SLF4J logger instance for pipeline components.
 * Automatically creates class-specific logger using `LoggerFactory.getLogger(this.getClass)`.
 *
 * Usage: `extends Logging` or `with Logging` in classes/objects.
 *
 * Provides consistent logging across all pipeline modules (config, extractor, transformer, loader, jobs).
 */
trait Logging {
  /**
   * Protected SLF4J logger scoped to concrete implementing class.
   * Uses `this.getClass` for automatic class name resolution.
   */
  protected val logger = LoggerFactory.getLogger(this.getClass)
}
