package models

import java.time.Instant

/**
 * Represents a task associated with an event, including assignment details,
 * scheduling estimates, execution timestamps, and soft-deletion metadata.
 *
 * Tasks are typically used for workflow management, delegation, scheduling,
 * and tracking the lifecycle of operational activities within an event.
 *
 * @param taskId              Optional unique identifier for the task (set upon persistence).
 *
 * @param eventId             Identifier of the event this task is associated with.
 *
 * @param createdBy           ID of the user who created the task.
 *
 * @param title               Short title or summary describing the task.
 *
 * @param description         Optional detailed explanation of the task.
 *
 * @param assignedTeamId      Optional ID of the team responsible for handling the task.
 *
 * @param assignedToUserId    Optional ID of the individual user assigned to execute the task.
 *
 * @param priority            Priority level of the task (e.g., "High", "Medium", "Low").
 *
 * @param status              Current status of the task (e.g., "Pending", "InProgress", "Completed", "Cancelled").
 *
 * @param estimatedStart      Optional estimated start time for the task.
 *
 * @param estimatedEnd        Optional estimated end/completion time.
 *
 * @param actualStart         Optional timestamp marking when work on the task actually began.
 *
 * @param actualEnd           Optional timestamp marking real completion time.
 *
 * @param specialRequirements Optional notes indicating special needs (equipment, approvals, constraints, etc.).
 *
 * @param createdAt           Timestamp indicating when the task record was created.
 *
 * @param updatedAt           Timestamp marking the most recent update to the task.
 *
 * @param isDeleted           Indicates whether the task has been soft-deleted.
 *
 * @param deletedAt           Optional timestamp indicating when the task was soft-deleted.
 */
case class Task(
                 taskId: Option[Int],
                 eventId: Int,
                 createdBy: Int,
                 title: String,
                 description: Option[String],
                 assignedTeamId: Option[Int],
                 assignedToUserId: Option[Int],
                 priority: String,
                 status: String,
                 estimatedStart: Option[Instant],
                 estimatedEnd: Option[Instant],
                 actualStart: Option[Instant],
                 actualEnd: Option[Instant],
                 specialRequirements: Option[String],
                 createdAt: Instant,
                 updatedAt: Instant,
                 isDeleted: Boolean,
                 deletedAt: Option[Instant]
               )
