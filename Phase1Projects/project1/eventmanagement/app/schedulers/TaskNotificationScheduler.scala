package schedulers

import javax.inject._
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import play.api.{Configuration, Logging}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import services.TaskNotificationService

/**
 * Periodic scheduler responsible for triggering task notification processing.
 *
 * This scheduler runs at a configured interval and performs two key operations:
 *
 *   1. `processDueNotifications()` – sends or processes pending scheduled notifications.
 *   2. `checkAndPublishOverdueTasks()` – identifies overdue tasks and publishes notification events.
 *
 * Configuration keys:
 * {{{
 *   task.scheduler.initialDelay = "15 seconds"   # optional
 *   task.scheduler.interval     = "5 minutes"    # optional
 * }}}
 *
 * Defaults:
 *  - `initialDelay`: 15 seconds
 *  - `interval`: 5 minutes
 *
 * The scheduler starts automatically when the application boots and
 * is gracefully cancelled during application shutdown.
 *
 * @param systemProvider provider for Pekko ActorSystem
 * @param lifecycle      Play application lifecycle for stop hooks
 * @param config         application configuration for interval settings
 * @param notifService   service responsible for task-related notifications
 * @param ec             execution context
 */
@Singleton
class TaskNotificationScheduler @Inject()(
                                           systemProvider: javax.inject.Provider[ActorSystem],
                                           lifecycle: ApplicationLifecycle,
                                           config: Configuration,
                                           notifService: TaskNotificationService
                                         )(implicit ec: ExecutionContext) extends Logging {

  /** Lazy getter for ActorSystem to ensure proper injection ordering. */
  private lazy val system: ActorSystem = systemProvider.get()

  /** Execution context used by the scheduler. */
  private val schedulerEc: ExecutionContext = system.dispatcher

  /** Initial delay before the scheduler runs for the first time. */
  private val initialDelay: FiniteDuration =
    config.getOptional[FiniteDuration]("task.scheduler.initialDelay").getOrElse(15.seconds)

  /** Interval between scheduler executions. */
  private val interval: FiniteDuration =
    config.getOptional[FiniteDuration]("task.scheduler.interval").getOrElse(5.minutes)

  logger.info(
    s"TaskNotificationScheduler initializing (initialDelay=$initialDelay, interval=$interval)"
  )

  /**
   * The repeating scheduler instance, based on `scheduleAtFixedRate`.
   * Runs `runScheduledJobs()` at configured intervals.
   */
  private val cancellable: Cancellable =
    system.scheduler.scheduleAtFixedRate(initialDelay, interval)(() => runScheduledJobs())(schedulerEc)

  /**
   * Executes all scheduled job logic:
   *   - processes due notifications
   *   - checks and publishes overdue tasks
   *
   * Logs execution time and errors.
   */
  private def runScheduledJobs(): Unit = {
    val start = System.nanoTime()

    notifService.processDueNotifications()
      .flatMap(_ => notifService.checkAndPublishOverdueTasks())(schedulerEc)
      .onComplete {
        case scala.util.Success(_) =>
          val elapsedMs = (System.nanoTime() - start) / 1000000
          logger.info(s"TaskNotificationScheduler run completed successfully in ${elapsedMs} ms")

        case scala.util.Failure(ex) =>
          val elapsedMs = (System.nanoTime() - start) / 1000000
          logger.error(s"TaskNotificationScheduler run failed after ${elapsedMs} ms", ex)
      }(schedulerEc)
  }

  /**
   * Registers a Play lifecycle stop hook to gracefully cancel the scheduler
   * when the application shuts down.
   */
  lifecycle.addStopHook { () =>
    Future {
      logger.info("Stopping TaskNotificationScheduler")
      try {
        cancellable.cancel()
      } catch {
        case ex: Throwable =>
          logger.warn("Error cancelling TaskNotificationScheduler", ex)
      }
    }(schedulerEc)
  }
}
