package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging
import models.NotificationEvent
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile
import play.api.libs.json.{JsValue, Json}

import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for managing notification events used to publish messages
 * (e.g., to Kafka) and to record their delivery status.
 *
 * Responsibilities:
 *  - create notification_event rows
 *  - find pending events for processing
 *  - mark events as published or failed
 *
 * Uses Slick `MySQLProfile` and Play's DatabaseConfigProvider.
 * All public methods attach `.recover` handlers to log unexpected database
 * errors and re-throw them so higher layers (services/controllers) can
 * convert failures into HTTP responses or retries.
 *
 * @param dbConfigProvider Play DB config provider
 * @param ec ExecutionContext for DB operations
 */
@Singleton
class NotificationEventRepository @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile]
    with Logging {

  import profile.api._

  /** Slick mapping for java.time.Instant <-> java.sql.Timestamp. */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /** Slick mapping for JsValue <-> String (store as JSON text). */
  implicit val jsonColumnType: BaseColumnType[JsValue] =
    MappedColumnType.base[JsValue, String](
      js  => Json.stringify(js),
      str => Json.parse(str)
    )

  /**
   * Internal row representation for the `notification_events` table.
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
   * Slick table mapping for notification_events.
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

  /** Convert DB row to domain model NotificationEvent. */
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
   * Insert a new NotificationEvent row and return the generated eventId.
   *
   * @param event NotificationEvent (eventId is ignored)
   * @return Future[Long] generated eventId
   */
  def create(event: NotificationEvent): Future[Long] = {
    val insertProjection =
      events.map(e => (e.eventType, e.allocationId, e.ticketId, e.payload, e.status))

    val action =
      (insertProjection returning events.map(_.eventId)) +=
        (event.eventType, event.allocationId, event.ticketId, event.payload, event.status)

    db.run(action).recover { case NonFatal(ex) =>
      logger.error(s"Error creating notification event type=${event.eventType}", ex)
      throw ex
    }
  }

  /**
   * Find pending events (status = "PENDING") up to the provided limit,
   * ordered by creation time (oldest first).
   *
   * @param limit maximum number of events to return (default 50)
   * @return Future[Seq[NotificationEvent]]
   */
  def findPending(limit: Int = 50): Future[Seq[NotificationEvent]] =
    db.run(
      events
        .filter(_.status === "PENDING")
        .take(limit)
        .result
    ).map { rows =>
      rows.map(toModel).sortBy(_.createdAt.toEpochMilli)
    }.recover { case NonFatal(ex) =>
      logger.error(s"Error fetching pending notification events (limit=$limit)", ex)
      throw ex
    }

  /**
   * Mark the event as published (status = "PUBLISHED") and set publishedAt.
   *
   * @param eventId event id to mark published
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error marking event published (eventId=$eventId)", ex)
      throw ex
    }
  }

  /**
   * Mark the event as failed (status = "FAILED") and record lastError.
   *
   * @param eventId event id to mark failed
   * @param error human-readable error message
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error marking event failed (eventId=$eventId, error=${error.take(200)})", ex)
      throw ex
    }
  }
}
