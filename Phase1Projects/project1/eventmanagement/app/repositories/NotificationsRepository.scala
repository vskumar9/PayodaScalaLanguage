package repositories

import javax.inject._
import models.Notification
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * Repository for managing task and event notifications stored in the `notifications` table.
 *
 * A [[models.Notification]] represents messages that may be scheduled, sent, or retried.
 * This repository supports:
 *
 *   - scheduling notifications
 *   - retrieving pending or due notifications
 *   - updating status (sent/failed)
 *   - incrementing retry attempts
 *   - soft-delete and restore functionality
 *
 * Soft-delete behavior is implemented via the `is_deleted` and `deleted_at` columns.
 *
 * @param dbConfigProvider provides Slick database configuration
 * @param ec               execution context for asynchronous DB IO operations
 */
@Singleton
class NotificationsRepository @Inject()(
                                         dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                       )(implicit ec: ExecutionContext) extends SlickMapping {

  /** Slick database configuration (MySQL). */
  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit mapping for java.time.Instant <-> SQL TIMESTAMP. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `notifications` table.
   */
  private class NotificationsTable(tag: Tag) extends Table[Notification](tag, "notifications") {
    def notificationId   = column[Int]("notification_id", O.PrimaryKey, O.AutoInc)
    def eventId          = column[Option[Int]]("event_id")
    def taskId           = column[Option[Int]]("task_id")
    def recipientUserId  = column[Option[Int]]("recipient_user_id")
    def recipientTeamId  = column[Option[Int]]("recipient_team_id")
    def notificationType = column[String]("notification_type")
    def message          = column[Option[String]]("message")
    def scheduledFor     = column[Option[Instant]]("scheduled_for")
    def sentAt           = column[Option[Instant]]("sent_at")
    def status           = column[String]("status")
    def attempts         = column[Int]("attempts")
    def createdAt        = column[Instant]("created_at")
    def isDeleted        = column[Boolean]("is_deleted")
    def deletedAt        = column[Option[Instant]]("deleted_at")

    /** Projection mapping to/from Notification case class. */
    def * =
      (
        notificationId.?, eventId, taskId, recipientUserId, recipientTeamId,
        notificationType, message, scheduledFor, sentAt, status, attempts,
        createdAt, isDeleted, deletedAt
      ) <> (Notification.tupled, Notification.unapply)
  }

  /** Slick table query interface. */
  private val notifications = TableQuery[NotificationsTable]

  /**
   * Inserts a new scheduled notification.
   *
   * @param n notification to schedule
   * @return generated notification ID
   */
  def schedule(n: Notification): Future[Int] =
    db.run(notifications returning notifications.map(_.notificationId) += n)

  /**
   * Retrieves pending notifications.
   *
   * Pending notifications are those:
   *   - not deleted
   *   - with status "pending"
   *
   * @param limit maximum number of notifications to retrieve
   * @return sequence of notifications
   */
  def pending(limit: Int = 50): Future[Seq[Notification]] =
    db.run(
      notifications
        .filter(n => n.status === "pending" && n.isDeleted === false)
        .take(limit)
        .result
    )

  /**
   * Soft-deletes a notification by marking `is_deleted = true` and updating `deleted_at`.
   *
   * @param id notification ID
   * @return number of affected rows
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      notifications
        .filter(n => n.notificationId === id && n.isDeleted === false)
        .map(n => (n.isDeleted, n.deletedAt))
        .update((true, Some(now)))
    )
  }

  /**
   * Restores a previously soft-deleted notification.
   *
   * @param id notification ID
   * @return number of affected rows
   */
  def restore(id: Int): Future[Int] =
    db.run(
      notifications
        .filter(n => n.notificationId === id && n.isDeleted === true)
        .map(n => (n.isDeleted, n.deletedAt))
        .update((false, None))
    )

  /**
   * Updates a notification entirely (overwrites existing row).
   *
   * @param id      notification ID
   * @param updated new notification data
   * @return number of affected rows
   */
  def update(id: Int, updated: Notification): Future[Int] =
    db.run(
      notifications
        .filter(_.notificationId === id)
        .update(updated.copy(notificationId = Some(id)))
    )

  /**
   * Finds due notifications.
   *
   * A notification is considered due if:
   *   - it is not deleted
   *   - its status is "pending"
   *   - its scheduled time is before now, or has no schedule
   *
   * @param now   current timestamp
   * @param limit maximum number of notifications
   * @return sequence of due notifications
   */
  def findDue(now: Instant, limit: Int = 50): Future[Seq[Notification]] = {
    val nowLit = LiteralColumn(now)
    val q = notifications.filter { n =>
      (n.isDeleted === false) &&
        (n.status === "pending") &&
        (n.scheduledFor.isEmpty || n.scheduledFor <= nowLit)
    }.take(limit)
    db.run(q.result)
  }

  /**
   * Checks whether a pending notification of a given type already exists for a task.
   *
   * Useful for avoiding duplicate reminder scheduling.
   *
   * @param taskId task ID
   * @param nType  notification type
   * @return true if such a pending notification exists
   */
  def existsPendingForTask(taskId: Int, nType: String): Future[Boolean] =
    db.run(
      notifications
        .filter(n =>
          n.taskId === taskId &&
            n.notificationType === nType &&
            n.status === "pending" &&
            n.isDeleted === false
        )
        .exists
        .result
    )

  /**
   * Marks a notification as successfully sent.
   *
   * @param id notification ID
   * @return number of affected rows
   */
  def markSent(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      notifications
        .filter(_.notificationId === id)
        .map(n => (n.status, n.sentAt))
        .update(("sent", Some(now)))
    )
  }

  /**
   * Increments the attempts counter for a notification.
   *
   * If attempts reach `maxAttempts`, status becomes "failed".
   *
   * @param id          notification ID
   * @param maxAttempts maximum retry attempts before failing
   * @return number of affected rows
   */
  def incrementAttempts(id: Int, maxAttempts: Int = 3): Future[Int] = {
    import profile.api._
    val action = for {
      maybe <- notifications.filter(_.notificationId === id).result.headOption
      res <- maybe match {
        case Some(curr) =>
          val nextAttempts = curr.attempts + 1
          val nextStatus   = if (nextAttempts >= maxAttempts) "failed" else curr.status
          notifications
            .filter(_.notificationId === id)
            .map(n => (n.attempts, n.status))
            .update((nextAttempts, nextStatus))
        case None =>
          DBIO.successful(0)
      }
    } yield res
    db.run(action.transactionally)
  }

  /**
   * Alias for findDue() with a different default limit.
   *
   * @param now   current timestamp
   * @param limit max notifications to return
   * @return due notifications
   */
  def findDueNotifications(now: Instant, limit: Int = 100): Future[Seq[Notification]] =
    findDue(now, limit)
}
