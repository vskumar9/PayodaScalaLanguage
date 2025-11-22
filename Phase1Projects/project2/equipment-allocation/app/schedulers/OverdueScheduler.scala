package schedulers

import javax.inject._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Cancellable
import play.api.{Configuration, Logging}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import java.time.Instant

import repositories.{EquipmentAllocationRepository, NotificationEventRepository}
import services.KafkaProducerService
import models.NotificationEvent

/**
 * Background scheduler that periodically checks for overdue equipment allocations
 * and publishes reminder notification events.
 *
 * This component is eagerly started by the DI container (via Module) and:
 *  - schedules a repeating task (initialDelay, interval) driven by configuration
 *  - queries the EquipmentAllocationRepository for overdue allocations
 *  - creates NotificationEvent rows and publishes messages via KafkaProducerService
 *  - marks events as PUBLISHED or FAILED in the NotificationEventRepository
 *
 * Error handling:
 *  - DB / network errors during queries, persistence or publishing are logged.
 *  - Each async step has `.recover` / `.recoverWith` handlers so the scheduler loop
 *    continues even on partial failures.
 *
 * Configuration keys (example):
 *   overdue.scheduler.initialDelay  = 30s
 *   overdue.scheduler.interval      = 1h
 *
 * @param systemProvider provider for ActorSystem used to schedule tasks
 * @param lifecycle application lifecycle to hook scheduler shutdown
 * @param config application configuration
 * @param allocationRepo repository to query overdue allocations
 * @param notificationEventRepo repository to persist notification events
 * @param kafkaProducer service used to publish messages to Kafka
 * @param ec execution context for futures
 */
@Singleton
class OverdueScheduler @Inject()(
                                  systemProvider: javax.inject.Provider[ActorSystem],
                                  lifecycle: ApplicationLifecycle,
                                  config: Configuration,
                                  allocationRepo: EquipmentAllocationRepository,
                                  notificationEventRepo: NotificationEventRepository,
                                  kafkaProducer: KafkaProducerService
                                )(implicit ec: ExecutionContext) extends Logging {

  private lazy val system: ActorSystem = systemProvider.get()

  private val initialDelay: FiniteDuration =
    config.getOptional[FiniteDuration]("overdue.scheduler.initialDelay").getOrElse(30.seconds)

  private val interval: FiniteDuration =
    config.getOptional[FiniteDuration]("overdue.scheduler.interval").getOrElse(1.hour)

  logger.info(s"OverdueScheduler initializing (initialDelay=$initialDelay, interval=$interval)")

  // Schedule a repeating job. Protect run() with try/catch so uncaught exceptions don't kill the scheduler thread.
  private val cancellable: Cancellable =
    system.scheduler.scheduleAtFixedRate(initialDelay, interval)(
      new Runnable {
        override def run(): Unit = {
          try {
            checkAndPublishOverdues()
          } catch {
            case NonFatal(ex) =>
              logger.error("Uncaught exception in OverdueScheduler run loop", ex)
          }
        }
      }
    )(ec)

  // Ensure we cancel the scheduled job on application stop.
  lifecycle.addStopHook { () =>
    Future {
      logger.info("Stopping OverdueScheduler")
      try cancellable.cancel() catch { case NonFatal(ex) => logger.warn("Error cancelling OverdueScheduler", ex) }
    }
  }

  /**
   * Query the allocation repository for overdue allocations and publish reminders.
   *
   * The core flow:
   *  - find overdue allocations as of now
   *  - for each allocation create a NotificationEvent row and attempt to publish to Kafka
   *  - on successful publish mark the event PUBLISHED; on failure mark FAILED
   *
   * All failures are logged; individual failures do not stop processing other allocations.
   */
  private def checkAndPublishOverdues(): Unit = {
    val now = Instant.now()
    logger.info(s"OverdueScheduler: checking overdue allocations at $now")

    allocationRepo.findOverdue(now).map { overdueSeq =>
      if (overdueSeq.isEmpty) {
        logger.debug("OverdueScheduler: no overdue allocations found")
      } else {
        logger.info(s"OverdueScheduler: found ${overdueSeq.size} overdue allocation(s) — publishing reminders")
        overdueSeq.foreach { alloc =>
          try {
            publishReminderForAllocation(alloc.allocationId, alloc)
          } catch {
            case NonFatal(ex) =>
              // Defensive: publishReminderForAllocation uses async ops and returns Unit, but wrap anyway
              logger.error(s"Error while scheduling publishReminderForAllocation for allocation ${alloc.allocationId}", ex)
          }
        }
      }
    }.recover { case NonFatal(ex) =>
      logger.error("OverdueScheduler: error fetching overdue allocations", ex)
    }
  }

  /**
   * Persist a NotificationEvent for the overdue allocation and publish a reminder message.
   *
   * The function handles:
   *  - persisting a NotificationEvent row
   *  - publishing the payload to Kafka
   *  - marking the event as PUBLISHED on success
   *  - marking the event as FAILED on publish error
   *
   * All operations are performed asynchronously. Failures at any step are logged and
   * do not throw out of this method.
   *
   * @param allocationId id of the overdue allocation
   * @param allocRow the allocation model (used to build payload)
   */
  private def publishReminderForAllocation(allocationId: Int, allocRow: models.EquipmentAllocation): Unit = {
    val now = Instant.now()

    val payload = Json.obj(
      "eventType"       -> "OVERDUE_REMINDER",
      "allocationId"    -> allocationId,
      "equipmentId"     -> allocRow.equipmentId,
      "employeeId"      -> allocRow.employeeId,
      "expectedReturnAt"-> allocRow.expectedReturnAt.toString,
      "notifiedAt"      -> now.toString
    )

    val ev = NotificationEvent(
      eventId     = 0L,
      eventType   = "OVERDUE_REMINDER",
      allocationId = Some(allocationId),
      ticketId    = None,
      payload     = payload,
      status      = "PENDING",
      createdAt   = now,
      publishedAt = None,
      lastError   = None
    )

    // Persist the event, then attempt to publish; handle failures at each step.
    notificationEventRepo.create(ev).flatMap { persistedId =>
      // Attempt publish
      kafkaProducer.publishEquipment("REMINDER", payload).map { _meta =>
        // On successful publish, mark published in DB; log but don't fail on DB mark failure.
        notificationEventRepo.markPublished(persistedId).recover {
          case NonFatal(dbEx) =>
            logger.warn(s"Published event $persistedId but failed to markPublished in DB", dbEx)
        }
      }.recoverWith { case pubEx =>
        // Publish failed — mark the event FAILED and log errors (and handle DB failures).
        logger.error(s"Failed to publish reminder for allocation $allocationId", pubEx)
        notificationEventRepo.markFailed(persistedId, pubEx.getMessage).recover {
          case dbEx => logger.warn(s"Failed to mark notification event $persistedId as FAILED", dbEx)
        }.map(_ => ())
      }
    }.recover { case NonFatal(createEx) =>
      logger.error(s"Failed to persist notification event for allocation $allocationId", createEx)
    }
    ()
  }
}
