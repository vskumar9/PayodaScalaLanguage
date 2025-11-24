package services

import javax.inject._
import repositories.EventsRepository
import models.{Event, User}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/**
 * Service layer responsible for managing events, providing
 * higher-level business logic on top of the data access layer.
 *
 * This service abstracts interactions with the [[EventsRepository]]
 * and applies additional rules such as setting timestamps, default status,
 * and ensuring soft-delete semantics.
 *
 * Supported operations include:
 *   - Creating new events
 *   - Fetching events (active only)
 *   - Updating event details
 *   - Soft-deleting and restoring events
 *
 * @param eventsRepo repository for event persistence
 * @param ec         execution context for async operations
 */
@Singleton
class EventService @Inject()(eventsRepo: EventsRepository)(implicit ec: ExecutionContext) {

  /**
   * Creates a new event with an initial `"planned"` status and timestamps.
   *
   * @param createdBy           ID of the user creating the event
   * @param title               event title
   * @param eventType           type/category of the event
   * @param description         optional event description
   * @param eventDate           scheduled date and time of the event
   * @param expectedGuestCount  optional expected audience/guest count
   * @param location            optional event location
   * @return a [[Future]] containing the newly generated event ID
   */
  def createEvent(createdBy: Int,
                  title: String,
                  eventType: String,
                  description: Option[String],
                  eventDate: Instant,
                  expectedGuestCount: Option[Int],
                  location: Option[String]): Future[Int] = {
    val now = Instant.now()
    val ev = Event(
      eventId = None,
      createdBy = createdBy,
      title = title,
      eventType = eventType,
      description = description,
      eventDate = eventDate,
      expectedGuestCount = expectedGuestCount,
      location = location,
      status = "planned",
      createdAt = now,
      updatedAt = now,
      isDeleted = false,
      deletedAt = None
    )
    eventsRepo.create(ev)
  }

  /**
   * Retrieves an active (non-deleted) event by ID.
   *
   * @param id event ID
   * @return Some(Event) if found and active, otherwise None
   */
  def getEvent(id: Int): Future[Option[Event]] =
    eventsRepo.findByIdActive(id)

  /**
   * Lists all active (non-deleted) events.
   *
   * @return a sequence of active events
   */
  def listActive(): Future[Seq[Event]] =
    eventsRepo.listActive()

  /**
   * Updates an existing event. The `updatedAt` timestamp is refreshed automatically.
   *
   * @param id      ID of the event to update
   * @param updated event object containing new data
   * @return number of affected rows (1 if successful, 0 otherwise)
   */
  def updateEvent(id: Int, updated: Event): Future[Int] = {
    val toSave = updated.copy(eventId = Some(id), updatedAt = Instant.now())
    eventsRepo.update(id, toSave)
  }

  /**
   * Soft-deletes an event by marking it as deleted and storing the deletion timestamp.
   *
   * @param id event ID
   * @return number of affected rows
   */
  def softDeleteEvent(id: Int): Future[Int] =
    eventsRepo.softDelete(id)

  /**
   * Restores a previously soft-deleted event.
   *
   * @param id event ID
   * @return number of affected rows
   */
  def restoreEvent(id: Int): Future[Int] =
    eventsRepo.restore(id)

  def listEventsBetweenWithUserPaged(start: Instant, end: Instant, page: Int, pageSize: Int): Future[(Seq[(Event, User)], Int)] =
    eventsRepo.listBetweenWithUserPaged(start, end, page, pageSize)
}
