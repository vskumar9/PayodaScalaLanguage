package services

import javax.inject._
import repositories.NotificationsRepository
import models.Notification
import scala.concurrent.{ExecutionContext, Future}
import java.time.{Instant, Duration}
import play.api.Logging

/**
 * Service responsible for creating, scheduling, processing and retrying notifications.
 *
 * This service provides higher-level notification logic on top of [[repositories.NotificationsRepository]]:
 *  - creating assignment and reminder notifications for tasks
 *  - scheduling arbitrary notifications
 *  - processing due notifications (marking them sent)
 *  - marking notifications as sent or failed with retry / backoff semantics
 *  - promoting persistent failures into issue alerts when retries are exhausted
 *
 * Retries use exponential backoff and a configurable maximum attempts constant.
 *
 * @param notificationsRepo repository used for persisting notification records
 * @param ec                execution context for async operations
 */
@Singleton
class NotificationService @Inject()(notificationsRepo: NotificationsRepository)(implicit ec: ExecutionContext) extends Logging {

  /** Maximum number of attempts before a notification is considered permanently failed. */
  private val MaxAttempts = 5

  /** Helper to insert a notification into the repository. */
  private def insertNotification(n: Notification): Future[Int] = notificationsRepo.schedule(n)

  /**
   * Replaces an existing notification by soft-deleting the original and scheduling a new one.
   *
   * The original's soft-delete return value is intentionally ignored; the replacement
   * is scheduled after attempting the soft-delete.
   *
   * @param originalId     id of the original notification to soft-delete
   * @param newNotification the new notification to schedule
   * @return Future containing the generated id of the newly scheduled notification
   */
  private def replaceNotification(originalId: Int, newNotification: Notification): Future[Int] = {
    // soft-delete original (we ignore return value)
    notificationsRepo.softDelete(originalId).flatMap { _ =>
      notificationsRepo.schedule(newNotification)
    }
  }

  /**
   * Creates and immediately schedules a task assignment notification for the given
   * event/task and recipient (user or team).
   *
   * @param eventId          event identifier the task belongs to
   * @param taskId           task identifier
   * @param assignedTeamId   optional team id assigned
   * @param assignedToUserId optional user id assigned
   * @param taskTitle        title of the task
   * @param estimatedStart   optional estimated start timestamp for the task
   * @return Future[Int] containing the scheduled notification id
   */
  def createAssignmentNotification(eventId: Int, taskId: Int, assignedTeamId: Option[Int], assignedToUserId: Option[Int], taskTitle: String, estimatedStart: Option[Instant]): Future[Int] = {
    val now = Instant.now()
    val message = s"Task assigned: $taskTitle. Start: ${estimatedStart.map(_.toString).getOrElse("TBD")}"
    val n = Notification(
      notificationId = None,
      eventId = Some(eventId),
      taskId = Some(taskId),
      recipientUserId = assignedToUserId,
      recipientTeamId = assignedTeamId,
      notificationType = "task_assignment",
      message = Some(message),
      scheduledFor = Some(now),
      sentAt = None,
      status = "pending",
      attempts = 0,
      createdAt = now,
      isDeleted = false,
      deletedAt = None
    )
    insertNotification(n)
  }

  /**
   * Schedules reminder notifications relative to the task's estimated start time.
   *
   * Currently schedules reminders at 24 hours and 3 hours before the estimated start,
   * if those times are still in the future. If no estimated start is provided, does nothing.
   *
   * @param eventId          event identifier
   * @param taskId           task identifier
   * @param assignedTeamId   optional assigned team id
   * @param assignedToUserId optional assigned user id
   * @param taskTitle        task title for message generation
   * @param estimatedStart   optional estimated start time for the task
   * @return Future[Unit] completed when scheduling attempts are finished
   */
  def scheduleReminderNotifications(eventId: Int, taskId: Int, assignedTeamId: Option[Int], assignedToUserId: Option[Int], taskTitle: String, estimatedStart: Option[Instant]): Future[Unit] = {
    val now = Instant.now()
    estimatedStart match {
      case Some(start) =>
        val candidates = Seq(start.minus(Duration.ofHours(24)), start.minus(Duration.ofHours(3))).filter(_.isAfter(now)).distinct
        val futures = candidates.map { when =>
          val msg = s"Reminder: Task '$taskTitle' scheduled at $start"
          val n = Notification(
            notificationId = None,
            eventId = Some(eventId),
            taskId = Some(taskId),
            recipientUserId = assignedToUserId,
            recipientTeamId = assignedTeamId,
            notificationType = "reminder",
            message = Some(msg),
            scheduledFor = Some(when),
            sentAt = None,
            status = "pending",
            attempts = 0,
            createdAt = now,
            isDeleted = false,
            deletedAt = None
          )
          insertNotification(n).map(_ => ())
        }
        Future.sequence(futures).map(_ => ())
      case None =>
        Future.successful(())
    }
  }

  /**
   * Processes due pending notifications up to the provided limit.
   *
   * Fetches pending notifications from the repository, filters those that are due
   * (scheduledFor <= now or no schedule) and replaces each with a sent copy (marking sentAt, status).
   *
   * The repository replacement strategy soft-deletes the original and schedules a copy that is marked sent.
   *
   * @param limit maximum number of pending notifications to fetch from the repo
   * @return Future[Unit] completed when processing finishes
   */
  def processDueNotifications(limit: Int = 50): Future[Unit] = {
    val now = Instant.now()
    notificationsRepo.pending(limit).flatMap { pendingList =>
      val due = pendingList.filter(n => n.scheduledFor.forall(!_.isAfter(now)))
      Future.sequence(due.map { n =>
        n.notificationId match {
          case Some(origId) =>
            val sentNotification = n.copy(
              notificationId = None,
              sentAt = Some(Instant.now()),
              status = "sent",
              attempts = n.attempts
            )
            replaceNotification(origId, sentNotification).map(_ => ())
          case None =>
            Future.successful(())
        }
      }).map(_ => ())
    }
  }

  /**
   * Marks a notification as sent by finding it among pending notifications and
   * replacing it with a sent copy.
   *
   * If the notification is not currently pending, the method is a no-op.
   *
   * @param notificationId id of the notification to mark sent
   * @return Future[Unit]
   */
  def markSent(notificationId: Int): Future[Unit] = {
    val searchLimit = 500
    notificationsRepo.pending(searchLimit).flatMap { pendingList =>
      pendingList.find(_.notificationId.contains(notificationId)) match {
        case Some(n) =>
          val sent = n.copy(notificationId = None, sentAt = Some(Instant.now()), status = "sent")
          replaceNotification(notificationId, sent).map(_ => ())
        case None =>
          Future.successful(())
      }
    }
  }

  /**
   * Marks a notification as failed and applies retry/backoff logic.
   *
   * Behavior:
   *  - If attempts after increment reach or exceed [[MaxAttempts]], an "issue_alert"
   *    notification is scheduled and the original is soft-deleted.
   *  - Otherwise the notification is rescheduled using exponential backoff:
   *    next delay is 2^(attempts-1) minutes (1, 2, 4, 8, ...).
         *
   * If the notification is not found among pending notifications, this is a no-op.
   *
   * @param notificationId id of the failed notification
   * @param reasonOpt      optional failure reason to include in issue alerts
   * @return Future[Unit]
   */
  def markFailed(notificationId: Int, reasonOpt: Option[String]): Future[Unit] = {
    val searchLimit = 500
    notificationsRepo.pending(searchLimit).flatMap { pendingList =>
      pendingList.find(_.notificationId.contains(notificationId)) match {
        case Some(n) =>
          val attemptsNow = n.attempts + 1
          if (attemptsNow >= MaxAttempts) {
            val issueMsg = s"Issue Alert: Notification for task ${n.taskId.getOrElse(-1)} failed after $attemptsNow attempts. ${reasonOpt.getOrElse("")}".trim
            val now = Instant.now()
            val issueNotif = Notification(
              notificationId = None,
              eventId = n.eventId,
              taskId = n.taskId,
              recipientUserId = None,
              recipientTeamId = None,
              notificationType = "issue_alert",
              message = Some(issueMsg),
              scheduledFor = Some(now),
              sentAt = None,
              status = "pending",
              attempts = 0,
              createdAt = now,
              isDeleted = false,
              deletedAt = None
            )
            notificationsRepo.softDelete(notificationId).flatMap { _ =>
              notificationsRepo.schedule(issueNotif).map(_ => ())
            }
          } else {
            val backoffMinutes = math.pow(2, (attemptsNow - 1).toDouble).toLong // 1,2,4,8...
            val newScheduledFor = Instant.now().plusSeconds(backoffMinutes * 60L)
            val newPending = n.copy(notificationId = None, scheduledFor = Some(newScheduledFor), status = "pending", attempts = attemptsNow, createdAt = Instant.now())
            replaceNotification(notificationId, newPending).map(_ => ())
          }
        case None =>
          Future.successful(())
      }
    }
  }

  /**
   * Convenience helper to create a generic notification.
   *
   * @param eventId          optional event id related to the notification
   * @param taskId           optional task id related to the notification
   * @param recipientUserId  optional recipient user id
   * @param recipientTeamId  optional recipient team id
   * @param notificationType type/category of notification
   * @param message          optional message body
   * @param scheduledFor     optional scheduled time to send
   * @return Future[Int] with generated notification id
   */
  def createNotification(eventId: Option[Int],
                         taskId: Option[Int],
                         recipientUserId: Option[Int],
                         recipientTeamId: Option[Int],
                         notificationType: String,
                         message: Option[String],
                         scheduledFor: Option[Instant]): Future[Int] = {
    val now = Instant.now()
    val n = Notification(None, eventId, taskId, recipientUserId, recipientTeamId, notificationType, message, scheduledFor, sentAt = None, status = "pending", attempts = 0, createdAt = now, isDeleted = false, deletedAt = None)
    insertNotification(n)
  }

  /** Returns a list of pending notifications (pass-through to repository). */
  def listPending(limit: Int = 50): Future[Seq[Notification]] = notificationsRepo.pending(limit)
}
