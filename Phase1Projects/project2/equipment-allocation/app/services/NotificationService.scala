package services

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import models.NotificationEvent
import repositories.NotificationEventRepository

/**
 * Service responsible for managing the lifecycle of notification events.
 *
 * This service provides a simple abstraction over `NotificationEventRepository`
 * and is typically used by background workers or schedulers that process
 * pending notification events (email, SMS, Slack, Kafka messages, etc.).
 *
 * Supported operations include:
 *   - Fetching pending events that require processing
 *   - Updating event state after they are successfully published
 *   - Marking events as failed with an error message
 *
 * @param eventRepo the repository handling persistence of notification events
 * @param ec        the asynchronous execution context
 */
@Singleton
class NotificationService @Inject()(
                                     eventRepo: NotificationEventRepository
                                   )(implicit ec: ExecutionContext) {

  /**
   * Fetches pending notification events from the database.
   *
   * Used by schedulers or Kafka workers to pick up events
   * that have not yet been published.
   *
   * @param limit the maximum number of events to fetch (default: 50)
   * @return a Future sequence of pending NotificationEvent objects
   */
  def fetchPendingEvents(limit: Int = 50): Future[Seq[NotificationEvent]] =
    eventRepo.findPending(limit)

  /**
   * Marks a notification event as successfully published.
   *
   * This updates:
   *   - The status to `"PUBLISHED"`
   *   - The `publishedAt` timestamp
   *   - Clears any previous error message
   *
   * @param eventId the ID of the event to update
   * @return a Future containing the number of rows updated
   */
  def markPublished(eventId: Long): Future[Int] =
    eventRepo.markPublished(eventId)

  /**
   * Marks a notification event as failed.
   *
   * This sets:
   *   - The status to `"FAILED"`
   *   - The lastError field
   *   - Leaves publishedAt unchanged (if it was never successfully published)
   *
   * @param eventId the ID of the event to mark as failed
   * @param error   the error message describing why publishing failed
   * @return a Future containing the number of rows updated
   */
  def markFailed(eventId: Long, error: String): Future[Int] =
    eventRepo.markFailed(eventId, error)
}
