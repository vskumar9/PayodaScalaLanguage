package models

import java.time.Instant

/**
 * Represents a single checklist item belonging to a task.
 *
 * Checklist items allow tasks to be broken down into smaller actionable steps.
 * Each item tracks completion state, audit information, and soft-delete metadata.
 *
 * @param checkId     optional unique identifier for the checklist item
 * @param taskId      the task to which this checklist item belongs
 * @param description textual description of the action or requirement
 * @param isDone      whether the item has been completed
 * @param doneBy      optional user ID of the person who completed the item
 * @param doneAt      optional timestamp when the item was marked completed
 *
 * @param createdAt   timestamp when the checklist item was created
 * @param isDeleted   soft-delete flag indicating whether the item is deleted
 * @param deletedAt   optional timestamp when the item was soft-deleted
 */
case class TaskChecklistItem(
                              checkId: Option[Int],
                              taskId: Int,
                              description: String,
                              isDone: Boolean,
                              doneBy: Option[Int],
                              doneAt: Option[Instant],
                              createdAt: Instant,
                              isDeleted: Boolean,
                              deletedAt: Option[Instant]
                            )
