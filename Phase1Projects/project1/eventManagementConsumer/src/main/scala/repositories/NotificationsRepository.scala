package repositories

import models.Notification
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository providing database operations for [[Notification]] entities.
 *
 * This class manages CRUD and lifecycle operations for notifications used in
 * scheduled messaging, reminders, alerts, and other asynchronous event-driven
 * workflows. It supports scheduling, retry attempts, soft deletion, and status
 * updates such as marking notifications as sent or failed.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Implicit execution context for async operations.
 */
@Singleton
class NotificationsRepository @Inject()(
                                         dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                       )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit Slick mapping for Java [[Instant]] fields. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `notifications` database table.
   *
   * Defines all columns and how they map to the [[Notification]] case class.
   *
   * @param tag Slick table tag.
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

    /** Projection mapping columns to the [[Notification]] case class. */
    def * =
      (
        notificationId.?, eventId, taskId, recipientUserId, recipientTeamId,
        notificationType, message, scheduledFor, sentAt,
        status, attempts, createdAt, isDeleted, deletedAt
      ) <> (Notification.tupled, Notification.unapply)
  }

  /** Table query handle for `notifications`. */
  private val notifications = TableQuery[NotificationsTable]

  /**
   * Updates an existing notification record by ID.
   *
   * @param id      ID of the notification to update.
   * @param updated New notification data.
   * @return        Future with number of affected rows (0 or 1).
   */
  def update(id: Int, updated: Notification): Future[Int] =
    db.run(
      notifications
        .filter(_.notificationId === id)
        .update(updated.copy(notificationId = Some(id)))
    )

  /**
   * Finds a notification by its ID.
   *
   * @param id Notification ID.
   * @return   Future containing an optional [[Notification]].
   */
  def findById(id: Int): Future[Option[Notification]] =
    db.run(notifications.filter(_.notificationId === id).result.headOption)

  /**
   * Finds notifications that are due to be processed.
   *
   * A notification is considered due when:
   *   - it is not soft-deleted
   *   - its status is `"pending"`
   *   - its scheduled time is either missing or earlier than the provided `now` timestamp
   *
   * @param now   Current timestamp used to evaluate schedule conditions.
   * @param limit Maximum number of notifications to return.
   * @return      Future with a sequence of due notifications.
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
   * Marks a notification as sent and sets its `sentAt` timestamp.
   *
   * @param id Notification ID.
   * @return   Future with number of updated rows (0 or 1).
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
   * Increments the attempt count for a notification and optionally marks it as failed.
   *
   * If the number of attempts reaches `maxAttempts`, the status is updated to `"failed"`.
   *
   * @param id          Notification ID.
   * @param maxAttempts Maximum allowed attempts before failure.
   * @return            Future with number of affected rows.
   */
  def incrementAttempts(id: Int, maxAttempts: Int = 3): Future[Int] = {
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
}
