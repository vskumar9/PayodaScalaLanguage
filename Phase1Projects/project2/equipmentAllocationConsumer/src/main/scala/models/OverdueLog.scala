package models

import java.time.Instant

/**
 * Represents a recorded notification sent for an overdue equipment allocation.
 *
 * This model is used to keep a historical log of all overdue reminders generated
 * by the system. It helps auditing, reporting, and preventing duplicate reminders.
 *
 * ### Purpose
 * - Track when and how overdue reminders were dispatched.
 * - Record recipient information for audit trails.
 * - Support escalation workflows such as:
 *     - FIRST reminder → SECOND reminder → FINAL reminder
 * - Enable analytics on reminder effectiveness and overdue frequency.
 *
 * ### Typical Workflow
 * When an allocation is overdue:
 * 1. `ReminderActor` computes that a reminder needs to be sent.
 * 2. Email/SMS/Slack notification is delivered.
 * 3. A new `OverdueLog` entry is created to record the event.
 *
 * ### Explanation of Fields
 *
 * @param overdueId            Unique identifier for the overdue log entry.
 * @param allocationId         ID of the related equipment allocation that is overdue.
 * @param notifiedAt           Timestamp when the reminder was sent.
 * @param reminderType         The escalation stage: FIRST, SECOND, or FINAL reminder.
 * @param recipientEmployeeId  Employee who received the reminder.
 * @param channel              Delivery channel used (EMAIL, SMS, SLACK, etc.).
 */
case class OverdueLog(
                       overdueId: Int,
                       allocationId: Int,
                       notifiedAt: Instant,
                       reminderType: String,        // FIRST, SECOND, FINAL
                       recipientEmployeeId: Int,
                       channel: String              // EMAIL, SMS, SLACK
                     )
