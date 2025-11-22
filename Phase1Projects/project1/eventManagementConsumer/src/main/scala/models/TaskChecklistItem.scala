package models

import java.time.Instant

/**
 * Represents an individual checklist item associated with a task. Checklist items
 * are used to break tasks into smaller actionable steps, track completion, and
 * manage progress at a granular level.
 *
 * @param checkId     Optional unique identifier for the checklist item (assigned when persisted).
 *
 * @param taskId      ID of the task this checklist item belongs to.
 *
 * @param description Description of the checklist step or requirement.
 *
 * @param isDone      Indicates whether this checklist item has been completed.
 *
 * @param doneBy      Optional ID of the user who completed the checklist item.
 *
 * @param doneAt      Optional timestamp indicating when the item was completed.
 *
 * @param createdAt   Timestamp marking when this checklist item was created.
 *
 * @param isDeleted   Soft-deletion flag indicating whether the item has been removed.
 *
 * @param deletedAt   Optional timestamp marking when the item was soft-deleted.
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
