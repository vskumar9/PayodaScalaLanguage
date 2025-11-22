package repositories

import models.Event
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for performing CRUD operations on [[Event]] entities using Slick.
 *
 * This class encapsulates all database interactions for the `events` table,
 * including creation, lookup (active and soft-deleted), and updates.
 * Soft deletion is supported via the `is_deleted` and `deleted_at` fields.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context for asynchronous database operations.
 */
@Singleton
class EventsRepository @Inject()(
                                  dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit Slick column mapping for Java [[Instant]] fields. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `events` database table.
   *
   * Defines all columns and how they map to the [[Event]] case class.
   *
   * @param tag Slick table tag provided by Slick's query builder.
   */
  private class EventsTable(tag: Tag) extends Table[Event](tag, "events") {

    def eventId = column[Int]("event_id", O.PrimaryKey, O.AutoInc)
    def createdBy = column[Int]("created_by")
    def title = column[String]("title")
    def eventType = column[String]("event_type")
    def description = column[Option[String]]("description")
    def eventDate = column[Instant]("event_date")
    def expectedGuestCount = column[Option[Int]]("expected_guest_count")
    def location = column[Option[String]]("location")
    def status = column[String]("status")
    def createdAt = column[Instant]("created_at")
    def updatedAt = column[Instant]("updated_at")
    def isDeleted = column[Boolean]("is_deleted")
    def deletedAt = column[Option[Instant]]("deleted_at")

    /** Maps table columns to the [[Event]] case class. */
    def * = (
      eventId.?, createdBy, title, eventType, description, eventDate,
      expectedGuestCount, location, status, createdAt, updatedAt, isDeleted, deletedAt
    ) <> (Event.tupled, Event.unapply)
  }

  /** Slick table query handle for the `events` table. */
  private val events = TableQuery[EventsTable]

  /**
   * Creates a new event record in the database.
   *
   * @param event Event instance to insert.
   * @return Future containing the generated event ID.
   */
  def create(event: Event): Future[Int] =
    db.run(events returning events.map(_.eventId) += event)

  /**
   * Retrieves an event by ID, but only if it is not soft-deleted.
   *
   * @param id Event ID.
   * @return Future containing an optional [[Event]].
   */
  def findByIdActive(id: Int): Future[Option[Event]] =
    db.run(events.filter(e => e.eventId === id && e.isDeleted === false).result.headOption)

  /**
   * Retrieves an event by ID regardless of soft-deletion status.
   *
   * @param id Event ID.
   * @return Future containing an optional [[Event]].
   */
  def findByIdIncludeDeleted(id: Int): Future[Option[Event]] =
    db.run(events.filter(_.eventId === id).result.headOption)

  /**
   * Updates an existing event if it is not soft-deleted.
   *
   * Automatically replaces the eventId of the updated entity to ensure consistency.
   *
   * @param id    Event ID to update.
   * @param event Updated event data.
   * @return Future with the number of rows affected (0 or 1).
   */
  def update(id: Int, event: Event): Future[Int] =
    db.run(
      events
        .filter(t => t.eventId === id && t.isDeleted === false)
        .update(event.copy(eventId = Some(id)))
    )
}
