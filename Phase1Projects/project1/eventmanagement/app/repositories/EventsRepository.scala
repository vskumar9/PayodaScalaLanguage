package repositories

import javax.inject._
import models.Event
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * Repository for performing CRUD operations on the `events` table.
 *
 * This class provides database access for [[models.Event]] using Slick and supports:
 *   - creating events
 *   - retrieving events (active only or including deleted)
 *   - listing active events
 *   - updating events
 *   - soft-deleting and restoring events
 *
 * Soft-delete behavior is implemented using the `is_deleted` and `deleted_at` fields.
 *
 * @param dbConfigProvider provides the Slick database configuration
 * @param ec               execution context for async DB operations
 */
@Singleton
class EventsRepository @Inject()(
                                  dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                )(implicit ec: ExecutionContext) extends SlickMapping {

  /** Slick database configuration (MySQL). */
  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit column mapping for java.time.Instant for MySQL via Slick. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `events` table.
   */
  private class EventsTable(tag: Tag) extends Table[Event](tag, "events") {

    def eventId            = column[Int]("event_id", O.PrimaryKey, O.AutoInc)
    def createdBy          = column[Int]("created_by")
    def title              = column[String]("title")
    def eventType          = column[String]("event_type")
    def description        = column[Option[String]]("description")
    def eventDate          = column[Instant]("event_date")
    def expectedGuestCount = column[Option[Int]]("expected_guest_count")
    def location           = column[Option[String]]("location")
    def status             = column[String]("status")
    def createdAt          = column[Instant]("created_at")
    def updatedAt          = column[Instant]("updated_at")
    def isDeleted          = column[Boolean]("is_deleted")
    def deletedAt          = column[Option[Instant]]("deleted_at")

    /** Projection mapping between table columns and the Event case class. */
    def * =
      (
        eventId.?, createdBy, title, eventType, description, eventDate,
        expectedGuestCount, location, status, createdAt, updatedAt, isDeleted, deletedAt
      ) <> (Event.tupled, Event.unapply)
  }

  /** Slick table query object for performing operations on events. */
  private val events = TableQuery[EventsTable]

  /**
   * Inserts a new event into the database.
   *
   * @param event event data to insert
   * @return the generated eventId
   */
  def create(event: Event): Future[Int] =
    db.run(events returning events.map(_.eventId) += event)

  /**
   * Retrieves a non-deleted event by ID.
   *
   * @param id event identifier
   * @return Some(Event) if found and active, otherwise None
   */
  def findByIdActive(id: Int): Future[Option[Event]] =
    db.run(events.filter(e => e.eventId === id && e.isDeleted === false).result.headOption)

  /**
   * Retrieves an event by ID, including soft-deleted records.
   *
   * @param id event identifier
   * @return Some(Event) if found, even if deleted; otherwise None
   */
  def findByIdIncludeDeleted(id: Int): Future[Option[Event]] =
    db.run(events.filter(_.eventId === id).result.headOption)

  /**
   * Lists all non-deleted (active) events, sorted by most recent.
   *
   * @return sequence of active events
   */
  def listActive(): Future[Seq[Event]] =
    db.run(events.filter(_.isDeleted === false).sortBy(_.eventId.desc).result)

  /**
   * Updates an active event.
   *
   * @param id    identifier of the event to update
   * @param event updated event data
   * @return number of affected rows (1 if successful)
   */
  def update(id: Int, event: Event): Future[Int] =
    db.run(
      events
        .filter(t => t.eventId === id && t.isDeleted === false)
        .update(event.copy(eventId = Some(id)))
    )

  /**
   * Soft-deletes an event.
   *
   * Marks the event as deleted and sets deletion timestamp.
   *
   * @param id identifier of the event to delete
   * @return number of affected rows
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    val q = events
      .filter(e => e.eventId === id && e.isDeleted === false)
      .map(e => (e.isDeleted, e.deletedAt, e.updatedAt))
      .update((true, Some(now), now))
    db.run(q)
  }

  /**
   * Restores a previously soft-deleted event.
   *
   * @param id identifier of the event to restore
   * @return number of affected rows
   */
  def restore(id: Int): Future[Int] = {
    val now = Instant.now()
    val q = events
      .filter(e => e.eventId === id && e.isDeleted === true)
      .map(e => (e.isDeleted, e.deletedAt, e.updatedAt))
      .update((false, None, now))
    db.run(q)
  }
}
