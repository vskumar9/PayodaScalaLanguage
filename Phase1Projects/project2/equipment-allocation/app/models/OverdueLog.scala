package models

import java.time.Instant

/**
 * Represents a record of an overdue reminder sent for an equipment allocation.
 *
 * An overdue reminder is triggered when an allocated equipment item is not
 * returned by the expected return date. Each reminder attempt is logged with
 * metadata about the type of reminder, recipient, and delivery channel.
 *
 * This log supports auditing, reporting, and throttling policies (e.g., not
 * sending multiple reminders too frequently).
 *
 * @param overdueId Unique identifier for the overdue reminder log entry
 * @param allocationId The equipment allocation for which the reminder was sent
 * @param notifiedAt Timestamp when the reminder was sent
 *
 * @param reminderType The escalation level of the reminder:
 *                     - `"FIRST"`   → first reminder
 *                     - `"SECOND"`  → second reminder
 *                     - `"FINAL"`   → final reminder before escalation
 *
 * @param recipientEmployeeId The employee who received the reminder
 *
 * @param channel Delivery channel used to send the reminder:
 *                - `"EMAIL"`
 *                - `"SMS"`
 *                - `"SLACK"`
 */
case class OverdueLog(
                       overdueId: Int,
                       allocationId: Int,
                       notifiedAt: Instant,
                       reminderType: String,        // FIRST, SECOND, FINAL
                       recipientEmployeeId: Int,
                       channel: String              // EMAIL, SMS, SLACK
                     )
