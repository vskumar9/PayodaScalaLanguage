package models

import java.time.Instant

/**
 * Represents a notification generated within the system.
 *
 * Notifications may be associated with events or tasks, and can be sent to either
 * individual users or entire teams. This model also tracks scheduling, delivery
 * attempts, status, and soft-delete metadata.
 *
 * @param notificationId   optional unique identifier of the notification
 * @param eventId          optional event ID this notification relates to (if applicable)
 * @param taskId           optional task ID this notification relates to (if applicable)
 * @param recipientUserId  optional user ID that should receive the notification
 * @param recipientTeamId  optional team ID that should receive the notification
 * @param notificationType the type/category of notification (e.g., Reminder, Assignment, StatusUpdate)
 * @param message          optional message body or content of the notification
 * @param scheduledFor     optional timestamp when the notification is scheduled to be sent
 * @param sentAt           optional timestamp when the notification was actually sent
 * @param status           current status (e.g., PENDING, SENT, FAILED)
 * @param attempts         number of attempts made to send this notification
 * @param createdAt        timestamp when the notification record was created
 * @param isDeleted        soft-delete flag indicating whether the notification is deleted
 * @param deletedAt        timestamp marking when the notification was soft-deleted, if applicable
 */
case class Notification(
                         notificationId: Option[Int],
                         eventId: Option[Int],
                         taskId: Option[Int],
                         recipientUserId: Option[Int],
                         recipientTeamId: Option[Int],
                         notificationType: String,
                         message: Option[String],
                         scheduledFor: Option[Instant],
                         sentAt: Option[Instant],
                         status: String,
                         attempts: Int,
                         createdAt: Instant,
                         isDeleted: Boolean,
                         deletedAt: Option[Instant]
                       )
