package models

import java.time.Instant
import play.api.libs.json.JsValue

/**
 * Represents an internal system notification event that is processed, published, or dispatched
 * by the notification pipeline (Kafka producers, reminder actors, maintenance actors, etc.).
 *
 * A `NotificationEvent` is typically created when the system detects an actionable event such as:
 *   - **OVERDUE_REMINDER**: An equipment allocation has passed its expected return time.
 *   - **MAINTENANCE_ALERT**: A high-severity maintenance event or damaged equipment notice.
 *   - **INVENTORY_UPDATE**: Changes to equipment status or lifecycle (allocated, returned, retired).
 *
 * The event can reference related domain objects:
 *   - `allocationId` → EquipmentAllocation entry
 *   - `ticketId`     → MaintenanceTicket entry
 *
 * Its `payload` carries the raw JSON that triggered the event and may contain additional metadata.
 *
 * ### Event Processing Workflow
 * A notification event typically moves through these states:
 *
 * - **PENDING**: Newly created and awaiting processing.
 * - **PUBLISHED**: Successfully delivered to the output channel (Kafka topic, email, webhook).
 * - **FAILED**: Delivery failed; `lastError` contains diagnostic information.
 *   Failed events may be retried by the scheduler or manually investigated.
 *
 * ### Usage
 * - Provides auditability of notifications sent by the system.
 * - Used by consumers like `ReminderActor`, `MaintenanceActor`, or Kafka publishers.
 * - Stored for reporting and debugging error cases.
 *
 * @param eventId     Unique identifier for this notification event.
 * @param eventType   High-level category of the event (e.g., OVERDUE_REMINDER, MAINTENANCE_ALERT).
 * @param allocationId Optional link to an equipment allocation if relevant.
 * @param ticketId     Optional link to a maintenance ticket if relevant.
 * @param payload      Raw JSON payload describing the triggering condition/event.
 * @param status       Delivery status (PENDING, PUBLISHED, FAILED).
 * @param createdAt    Timestamp when the event was created.
 * @param publishedAt  Timestamp when the event was successfully published, if applicable.
 * @param lastError    Optional diagnostic message describing the last failure occurred during processing.
 */
case class NotificationEvent(
                              eventId: Long,
                              eventType: String,           // OVERDUE_REMINDER, MAINTENANCE_ALERT, INVENTORY_UPDATE
                              allocationId: Option[Int],
                              ticketId: Option[Int],
                              payload: JsValue,            // raw JSON payload for the message

                              status: String,              // PENDING, PUBLISHED, FAILED
                              createdAt: Instant,
                              publishedAt: Option[Instant],
                              lastError: Option[String]
                            )
