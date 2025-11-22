package models

import java.time.Instant

/**
 * Represents a task associated with an event.
 *
 * Tasks capture work items that need to be executed before, during, or after an event.
 * They may include staff assignments, scheduling windows, priority levels, and additional
 * requirements. The model also includes timestamps for auditability and soft-delete support.
 *
 * @param taskId              optional unique identifier of the task (None before creation)
 * @param eventId             identifier of the event this task belongs to
 * @param createdBy           user ID of the creator of the task
 * @param title               title or summary of the task
 * @param description         optional detailed description of the task
 * @param assignedTeamId      optional team assigned to handle this task
 * @param assignedToUserId    optional user assigned to the task
 * @param priority            priority level (e.g., low, normal, high)
 * @param status              current status (e.g., NEW, ASSIGNED, IN_PROGRESS, COMPLETED)
 *
 * @param estimatedStart      optional estimated start time for the task
 * @param estimatedEnd        optional estimated completion time for the task
 * @param actualStart         optional timestamp when the task actually started
 * @param actualEnd           optional timestamp when the task was completed
 *
 * @param specialRequirements optional notes for special handling or constraints
 *
 * @param createdAt           timestamp when the task record was created
 * @param updatedAt           timestamp when the record was last updated
 *
 * @param isDeleted           soft-delete flag indicating whether the task is deleted
 * @param deletedAt           timestamp of deletion, if soft-deleted
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
