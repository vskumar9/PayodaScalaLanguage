package repositories

import models.NotificationEvent
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsValue, Json}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing notification events stored in the `notification_events` table.
 *
 * Notification events represent messages that must be published asynchronously
 * (typically to Kafka). Each record stores the event payload, type, publishing
 * status, and error information from failed attempts.
 *
 * This repository provides:
 *   - creation of new notification events
 *   - retrieval of pending events
 *   - marking events as published
 *   - marking events as failed (with error message)
 *
 * It uses Slick for database operations and includes custom column mappings for
 * `Instant` and `JsValue`.
 *
 * @param dbConfigProvider database configuration provider for Slick
 * @param ec               execution context for async DB operations
 */
@Singleton
class NotificationEventRepository @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile] {

  import profile.api._

  /** Column mapping for java.time.Instant to SQL TIMESTAMP. */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i  => Timestamp.from(i),
      ts => ts.toInstant
    )

  /** Column mapping for Play JSON values to VARCHAR/TEXT. */
  implicit val jsonColumnType: BaseColumnType[JsValue] =
    MappedColumnType.base[JsValue, String](
      js  => Json.stringify(js),
      str => Json.parse(str)
    )

  /**
   * Internal representation of a notification event row for Slick.
   */
  private case class EventRow(
                               eventId: Long,
                               eventType: String,
                               allocationId: Option[Int],
                               ticketId: Option[Int],
                               payload: JsValue,
                               status: String,
                               createdAt: Instant,
                               publishedAt: Option[Instant],
                               lastError: Option[String]
                             )

  /**
   * Slick table mapping for the `notification_events` table.
   */
  private class EventsTable(tag: Tag) extends Table[EventRow](tag, "notification_events") {
    def eventId      = column[Long]("event_id", O.PrimaryKey, O.AutoInc)
    def eventType    = column[String]("event_type")
    def allocationId = column[Option[Int]]("allocation_id")
    def ticketId     = column[Option[Int]]("ticket_id")
    def payload      = column[JsValue]("payload")
    def status       = column[String]("status")
    def createdAt    = column[Instant]("created_at")
    def publishedAt  = column[Option[Instant]]("published_at")
    def lastError    = column[Option[String]]("last_error")

    def * =
      (eventId, eventType, allocationId, ticketId, payload, status,
        createdAt, publishedAt, lastError).mapTo[EventRow]
  }

  /** Slick table query for `notification_events`. */
  private val events = TableQuery[EventsTable]

  /** Converts internal EventRow to the public NotificationEvent model. */
  private def toModel(r: EventRow): NotificationEvent =
    NotificationEvent(
      eventId      = r.eventId,
      eventType    = r.eventType,
      allocationId = r.allocationId,
      ticketId     = r.ticketId,
      payload      = r.payload,
      status       = r.status,
      createdAt    = r.createdAt,
      publishedAt  = r.publishedAt,
      lastError    = r.lastError
    )

  /**
   * Inserts a new notification event into the database.
   *
   * Only a subset of fields is inserted here; fields such as createdAt are
   * expected to be set by the caller before invocation.
   *
   * @param event notification event to insert
   * @return the generated event ID
   */
  def create(event: NotificationEvent): Future[Long] = {
    val insertProjection =
      events.map(e => (e.eventType, e.allocationId, e.ticketId, e.payload, e.status))

    val action =
      (insertProjection returning events.map(_.eventId)) +=
        (event.eventType, event.allocationId, event.ticketId, event.payload, event.status)

    db.run(action)
  }

  /**
   * Retrieves up to `limit` pending notification events.
   *
   * Pending events are sorted by creation time so the earliest-created events
   * are processed first.
   *
   * @param limit maximum number of pending events to retrieve
   * @return a sequence of pending notification events
   */
  def findPending(limit: Int = 50): Future[Seq[NotificationEvent]] =
    db.run(
      events
        .filter(_.status === "PENDING")
        .take(limit)
        .result
    ).map { rows =>
      rows.map(toModel).sortBy(_.createdAt.toEpochMilli)
    }

  /**
   * Marks a notification event as successfully published.
   *
   * Updates:
   *   - status      → PUBLISHED
   *   - publishedAt → now
   *   - lastError   → None
   *
   * If the event does not exist, returns 0.
   *
   * @param eventId event identifier
   * @return number of affected rows
   */
  def markPublished(eventId: Long): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeRow <- events.filter(_.eventId === eventId).result.headOption
      res <- maybeRow match {
        case Some(r) =>
          val updated = r.copy(status = "PUBLISHED", publishedAt = Some(now), lastError = None)
          events.filter(_.eventId === eventId).update(updated)
        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }

  /**
   * Marks a notification event as failed.
   *
   * Updates:
   *   - status      → FAILED
   *   - lastError   → error message
   *   - publishedAt → unchanged
   *
   * If the event does not exist, returns 0.
   *
   * @param eventId event identifier
   * @param error   error message encountered during publishing
   * @return number of affected rows
   */
  def markFailed(eventId: Long, error: String): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeRow <- events.filter(_.eventId === eventId).result.headOption
      res <- maybeRow match {
        case Some(r) =>
          val updated = r.copy(status = "FAILED", lastError = Some(error), publishedAt = r.publishedAt)
          events.filter(_.eventId === eventId).update(updated)
        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }
}
