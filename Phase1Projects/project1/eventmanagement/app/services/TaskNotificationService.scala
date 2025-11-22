package services

import javax.inject._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}
import java.time.{Instant, Duration}

import repositories.{TasksRepository, NotificationsRepository}
import models.{Task, Notification}

/**
 * Service responsible for scheduling and delivering task-related notifications.
 *
 * This service coordinates between task data, notification persistence, and an
 * optional Kafka producer to:
 *   - schedule reminders and assignment notifications for tasks
 *   - process due notifications and attempt delivery
 *   - publish overdue task notifications
 *
 * Behavior and configuration:
 *  - If `notifications.useKafka = true` (application.conf), notifications are
 *    published to Kafka via [[KafkaProducerService]]; otherwise the service logs deliveries.
 *  - `notifications.maxAttempts` controls how many attempts are allowed before
 *    marking retries as exhausted (default: 3).
 *
 * Notification types used by this service:
 *  - `REMINDER_1DAY`, `REMINDER_HOURS`, `OVERDUE`, `TASK_ASSIGNED`
 *
 * @param config         application configuration (reads notification-related flags)
 * @param tasksRepo      repository for task queries (scheduled/overdue)
 * @param notificationsRepo repository for persisting and querying notifications
 * @param kafkaProducer  Kafka producer used when `notifications.useKafka = true`
 * @param ec             execution context for async operations
 */
@Singleton
class TaskNotificationService @Inject()(
                                         config: Configuration,
                                         tasksRepo: TasksRepository,
                                         notificationsRepo: NotificationsRepository,
                                         kafkaProducer: KafkaProducerService
                                       )(implicit ec: ExecutionContext) extends Logging {

  private val useKafka: Boolean = config.getOptional[Boolean]("notifications.useKafka").getOrElse(false)
  private val maxAttempts: Int = config.getOptional[Int]("notifications.maxAttempts").getOrElse(3)

  val ReminderTypeOneDay = "REMINDER_1DAY"
  val ReminderTypeHours   = "REMINDER_HOURS"
  val OverdueType         = "OVERDUE"
  val TaskAssignedType    = "TASK_ASSIGNED"

  private def maybeProducer: Option[KafkaProducerService] = if (useKafka) Some(kafkaProducer) else None

  /**
   * Schedule reminder and assignment notifications for a given task.
   *
   * Schedules:
   *  - a reminder 1 day before the estimated start (if in the future)
   *  - a reminder 3 hours before the estimated start (if in the future)
   *  - an immediate task-assigned notification
   *
   * Duplicate notifications are avoided by checking for existing pending notifications
   * of the same type for the task via [[NotificationsRepository.existsPendingForTask]].
   *
   * If the task has no `estimatedStart`, reminder scheduling is skipped (assignment still scheduled).
   *
   * @param task the task to schedule reminders for
   * @return Future[Unit] completed when scheduling work is done
   */
  def scheduleRemindersForTask(task: Task): Future[Unit] = {
    val now = Instant.now()

    task.estimatedStart match {
      case None =>
        Future.successful(logger.debug(s"Task ${task.taskId.getOrElse(-1)} has no estimatedStart; skipping scheduled reminders"))

      case Some(estStart) =>
        val oneDayBefore = estStart.minus(Duration.ofDays(1))
        val hoursBefore  = estStart.minus(Duration.ofHours(3))

        def scheduleIfNotExists(taskId: Int, nType: String, scheduledFor: Instant, message: String): Future[Unit] = {
          notificationsRepo.existsPendingForTask(taskId, nType).flatMap {
            case true =>
              logger.debug(s"Notification already pending for task=$taskId type=$nType")
              Future.unit
            case false =>
              val n = Notification(
                notificationId = None,
                eventId = Some(task.eventId),
                taskId = Some(task.taskId.getOrElse(-1)),
                recipientUserId = task.assignedToUserId,
                recipientTeamId = task.assignedTeamId,
                notificationType = nType,
                message = Some(message),
                scheduledFor = Some(scheduledFor),
                sentAt = None,
                status = "pending",
                attempts = 0,
                createdAt = now,
                isDeleted = false,
                deletedAt = None
              )
              notificationsRepo.schedule(n).map(_ => logger.info(s"Scheduled notification for task=${task.taskId.getOrElse(-1)} type=$nType at $scheduledFor"))
          }
        }

        val f1 = if (oneDayBefore.isAfter(now)) scheduleIfNotExists(task.taskId.getOrElse(-1), ReminderTypeOneDay, oneDayBefore, s"Reminder: Task '${task.title}' starts in 1 day") else Future.unit
        val f2 = if (hoursBefore.isAfter(now)) scheduleIfNotExists(task.taskId.getOrElse(-1), ReminderTypeHours, hoursBefore, s"Reminder: Task '${task.title}' starts in a few hours") else Future.unit
        val f3 = {
          val assignedMsg = s"You have been assigned task '${task.title}' for event ${task.eventId}"
          scheduleIfNotExists(task.taskId.getOrElse(-1), TaskAssignedType, Instant.now(), assignedMsg)
        }

        Future.sequence(Seq(f1, f2, f3)).map(_ => ())
    }
  }

  /**
   * Process due notifications (those whose scheduledFor <= now or have no schedule).
   *
   * Workflow:
   *  - fetch due notifications via [[NotificationsRepository.findDue]]
   *  - attempt to send each notification via `sendNotification`
   *  - if send succeeds → markSent
   *  - if send fails → incrementAttempts (and potentially mark failed via repo logic)
   *
   * Any unexpected exceptions during send are logged and trigger an attempts increment.
   *
   * @return Future[Unit] completed when processing finishes
   */
  def processDueNotifications(): Future[Unit] = {
    val now = Instant.now()
    notificationsRepo.findDue(now, 200).flatMap { dueNotifications =>
      val futures: Seq[Future[Unit]] = dueNotifications.map { n =>
        sendNotification(n).flatMap {
          case true  => n.notificationId match {
            case Some(id) => notificationsRepo.markSent(id).map(_ => ())
            case None     => Future.successful(logger.warn(s"Notification has no id, cannot markSent: $n"))
          }
          case false => n.notificationId match {
            case Some(id) => notificationsRepo.incrementAttempts(id, maxAttempts).map(_ => ())
            case None     => Future.successful(logger.warn(s"Notification has no id, cannot increment attempts: $n"))
          }
        }.recoverWith { case ex =>
          logger.error(s"Error sending notification id=${n.notificationId}", ex)
          n.notificationId match {
            case Some(id) => notificationsRepo.incrementAttempts(id, maxAttempts).map(_ => ())
            case None     => Future.successful(())
          }
        }
      }

      Future.sequence(futures).map(_ => ())
    }
  }

  /**
   * Attempt to deliver a notification.
   *
   * If Kafka is enabled (via config), publishes the notification payload to Kafka
   * and returns true on success. Otherwise logs the delivery and returns true.
   *
   * On Kafka publish failure this returns false (and logs the error) so the caller
   * may trigger retry/backoff behavior.
   *
   * @param n notification to send
   * @return Future[Boolean] indicating success (true) or transient failure (false)
   */
  private def sendNotification(n: Notification): Future[Boolean] = {
    val payload = Json.obj(
      "eventType"       -> "REMINDER",
      "notificationType"-> n.notificationType,
      "notificationId"  -> n.notificationId,
      "eventId"         -> n.eventId,
      "taskId"          -> n.taskId,
      "recipientUserId" -> n.recipientUserId,
      "recipientTeamId" -> n.recipientTeamId,
      "message"         -> n.message,
      "scheduledFor"    -> n.scheduledFor.map(_.toString)
    )

    maybeProducer match {
      case Some(producer) =>
        producer.publishEquipment("NOTIFICATION", payload).map { _ =>
          logger.info(s"Published notification ${n.notificationId} to Kafka")
          true
        }.recover { case ex =>
          logger.error(s"Failed to publish notification ${n.notificationId} to Kafka", ex)
          false
        }

      case None =>
        logger.info(s"Delivering notification ${n.notificationId} (no-kafka mode): ${n.message.getOrElse("")}")
        Future.successful(true)
    }
  }

  /**
   * Find overdue tasks and create overdue notifications for them if not already pending.
   *
   * For each overdue task:
   *  - check if an `OVERDUE` notification is already pending for the task
   *  - if not, schedule an immediate `OVERDUE` notification
   *
   * @return Future[Unit] completed when scheduling is finished
   */
  def checkAndPublishOverdueTasks(): Future[Unit] = {
    val now = Instant.now()
    tasksRepo.findOverdue(now).flatMap { overdueTasks =>
      val fs = overdueTasks.map { task =>
        notificationsRepo.existsPendingForTask(task.taskId.getOrElse(-1), OverdueType).flatMap {
          case true => Future.successful(())
          case false =>
            val n = Notification(
              notificationId = None,
              eventId = Some(task.eventId),
              taskId = task.taskId,
              recipientUserId = task.assignedToUserId,
              recipientTeamId = task.assignedTeamId,
              notificationType = OverdueType,
              message = Some(s"Overdue: Task '${task.title}' is overdue (expected end ${task.estimatedEnd.map(_.toString).getOrElse("unknown")})"),
              scheduledFor = Some(now),
              sentAt = None,
              status = "pending",
              attempts = 0,
              createdAt = now,
              isDeleted = false,
              deletedAt = None
            )
            notificationsRepo.schedule(n).map(_ => logger.info(s"Scheduled overdue notification for task ${task.taskId.getOrElse(-1)}"))
        }
      }
      Future.sequence(fs).map(_ => ())
    }
  }
}
