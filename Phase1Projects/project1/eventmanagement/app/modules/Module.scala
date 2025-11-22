package modules

import play.api._
import schedulers.TaskNotificationScheduler
import services.{TaskNotificationService, KafkaProducerService}

/**
 * Application module for dependency injection bindings.
 *
 * This module registers key services and schedulers that must be initialized
 * eagerly at application startup. By using Play's dependency injection mechanism,
 * these components become available throughout the application wherever they are needed.
 *
 * Bindings included:
 *  - [[services.TaskNotificationService]]: Handles creation and processing of due task notifications.
 *  - [[schedulers.TaskNotificationScheduler]]: Periodic scheduler responsible for triggering
 *    notification processing tasks.
 *  - [[services.KafkaProducerService]]: Kafka producer used for publishing domain events.
 *
 * The `.eagerly()` directive ensures that these components are instantiated immediately when
 * the application starts, which is important for schedulers and background services.
 */
class Module extends play.api.inject.Module {

  /**
   * Defines all dependency injection bindings for the application.
   *
   * @param environment    Play environment descriptor
   * @param configuration  application configuration
   * @return a sequence of bindings for DI
   */
  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[TaskNotificationService].toSelf.eagerly(),
    bind[TaskNotificationScheduler].toSelf.eagerly(),
    bind[KafkaProducerService].toSelf.eagerly()
  )
}
