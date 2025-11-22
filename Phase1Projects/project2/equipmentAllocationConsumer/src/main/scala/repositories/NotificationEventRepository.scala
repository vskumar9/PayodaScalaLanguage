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
 * Repository for persisting and managing [[NotificationEvent]] records.
 *
 * This repository is used for storing *asynchronous event messages* that need to be
 * processed later by a separate worker or actor system (e.g., Kafka publisher, email
 * dispatcher, overdue reminders, maintenance alerts).
 *
 * It acts as a durable queue, allowing the system to:
 *   - Store outbound events that must be published.
 *   - Retry failed events.
 *   - Track the lifecycle of notification events.
 *
 * ### Features
 * - Stores event metadata + raw JSON payload
 * - Supports indexing fields for allocation ID and ticket ID
 * - Tracks event status: **PENDING**, **PUBLISHED**, **FAILED**
 * - Includes timestamps for creation and publishing
 * - Supports error logging through `lastError`
 *
 * ### Typical Workflow
 * 1. System creates a new `NotificationEvent` (status = PENDING)
 * 2. Worker polls `findPending()` to get items to publish.
 * 3. If publish succeeds → `markPublished()`
 * 4. If publish fails → `markFailed(error)`
 *
 * This ensures reliability, retryability, and full audit history.
 *
 * ### Database Table: `notification_events`
 * Columns:
 *   - event_id (PK, auto-increment)
 *   - event_type (e.g., "OVERDUE_REMINDER", "MAINTENANCE_ALERT")
 *   - allocation_id (nullable)
 *   - ticket_id (nullable)
 *   - payload (stored as JSON text)
 *   - status (PENDING / PUBLISHED / FAILED)
 *   - created_at
 *   - published_at (nullable)
 *   - last_error (nullable)
 *
 * ### JSON Handling
 * Uses Slick `MappedColumnType` to map `JsValue` <-> VARCHAR column.
 *
 * @param dbConfigProvider Autowired DB configuration provider
 * @param ec ExecutionContext for async DB operations
 */
@Singleton
class NotificationEventRepository @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile] {

  import profile.api._

  /** Mapping for Instant <-> Timestamp */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /** Mapping for JsValue <-> String */
  implicit val jsonColumnType: BaseColumnType[JsValue] =
    MappedColumnType.base[JsValue, String](
      js  => Json.stringify(js),
      str => Json.parse(str)
    )

  /**
   * Internal row representation corresponding exactly to the DB schema.
   *
   * @note This is mapped into the domain model using [[toModel]].
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
   * Slick table mapping for `notification_events`.
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

  private val events = TableQuery[EventsTable]

  /**
   * Convert a DB row into the domain model [[NotificationEvent]].
   */
  private def toModel(r: EventRow): NotificationEvent =
    NotificationEvent(
      eventId     = r.eventId,
      eventType   = r.eventType,
      allocationId = r.allocationId,
      ticketId    = r.ticketId,
      payload     = r.payload,
      status      = r.status,
      createdAt   = r.createdAt,
      publishedAt = r.publishedAt,
      lastError   = r.lastError
    )

  /**
   * Insert a new notification event.
   *
   * Automatically sets:
   *   - `eventId` (generated)
   *
   * @return Future containing the generated event ID
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
   * Find pending events that need to be published.
   *
   * @param limit Max number of records to fetch (default 50)
   * @return Ordered by creation time (oldest first)
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
   * Mark an event as successfully published.
   *
   * Updates:
   *   - status -> PUBLISHED
   *   - publishedAt -> now
   *   - lastError -> None
   *
   * @return Number of affected rows (0 if not found)
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
   * Mark a notification event as failed.
   *
   * Preserves `publishedAt` timestamp if already present.
   *
   * @param eventId ID of the event to update
   * @param error   Error message
   * @return Number of rows updated
   */
  def markFailed(eventId: Long, error: String): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeRow <- events.filter(_.eventId === eventId).result.headOption
      res <- maybeRow match {
        case Some(r) =>
          val updated = r.copy(
            status = "FAILED",
            lastError = Some(error),
            publishedAt = r.publishedAt
          )
          events.filter(_.eventId === eventId).update(updated)

        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }
}
