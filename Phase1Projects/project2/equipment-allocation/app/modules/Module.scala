package modules

import play.api.{Configuration, Environment}
import play.api._
import schedulers.OverdueScheduler

/**
 * Application module responsible for registering custom components
 * and enabling dependency injection bindings.
 *
 * This module eagerly loads the [[OverdueScheduler]], ensuring that
 * the scheduler starts running automatically when the Play application
 * starts.
 *
 * Play Framework's dependency injection system scans this class
 * at startup and applies the configured bindings.
 *
 * Responsibilities:
 *   - Registers the `OverdueScheduler` as an eagerly-loaded singleton.
 *   - Ensures background tasks (like overdue checks) begin immediately
 *     when the application boots.
 *
 * @see OverdueScheduler
 * @see play.api.inject.Module
 */
class Module extends inject.Module {

  /**
   * Returns dependency injection bindings to be installed into the application.
   *
   * @param environment The current Play environment (dev/test/prod)
   * @param configuration The application's configuration
   * @return Sequence of DI bindings for Play to install
   */
  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[OverdueScheduler].toSelf.eagerly()
  )
}
