package models

import play.api.libs.json.JsValue
import java.time.Instant

/**
 * Represents an outbound notification event that is queued for publishing
 * (typically to Kafka or another event-streaming system).
 *
 * This model tracks the raw payload, publishing status, timestamps, and errors.
 * Notification events are used as part of an asynchronous event-driven workflow
 * for tasks such as reminders, maintenance alerts, or inventory updates.
 *
 * @param eventId      unique identifier generated when the notification event is stored
 * @param eventType    category/type of the notification event
 *                     (e.g., "OVERDUE_REMINDER", "MAINTENANCE_ALERT", "INVENTORY_UPDATE")
 * @param allocationId optional link to an allocation entity, if relevant
 * @param ticketId     optional link to a ticket entity, if relevant
 * @param payload      raw JSON payload containing all data needed by the consumer
 *
 * @param status       publishing status (e.g., "PENDING", "PUBLISHED", "FAILED")
 * @param createdAt    timestamp when the event was first created
 * @param publishedAt  timestamp when the event was successfully published (if applicable)
 * @param lastError    last error message recorded when publishing failed
 */
case class NotificationEvent(
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
