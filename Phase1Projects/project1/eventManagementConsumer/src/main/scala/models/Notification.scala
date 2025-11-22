package models

import java.time.Instant

/**
 * Represents a system-generated notification related to events, tasks,
 * or team/user actions. Tracks delivery attempts, status, and soft deletion metadata.
 *
 * @param notificationId   Optional unique identifier for the notification (assigned when stored).
 * @param eventId          Optional ID of the associated event, if the notification is event-related.
 * @param taskId           Optional ID of the associated task, if applicable.
 * @param recipientUserId  Optional user ID to whom the notification is addressed.
 * @param recipientTeamId  Optional team ID to whom the notification is addressed (team-based notifications).
 * @param notificationType Type of notification (e.g., "EMAIL", "REMINDER", "ALERT").
 * @param message          Optional textual message or content of the notification.
 * @param scheduledFor     Optional timestamp specifying when the notification should be sent.
 * @param sentAt           Optional timestamp indicating when the notification was actually sent.
 * @param status           Current status of the notification (e.g., "Pending", "Sent", "Failed", "Cancelled").
 * @param attempts         Number of delivery attempts made so far.
 * @param createdAt        Timestamp marking when the notification record was created.
 * @param isDeleted        Indicates whether the notification has been soft-deleted.
 * @param deletedAt        Optional timestamp indicating when the notification was soft-deleted.
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
