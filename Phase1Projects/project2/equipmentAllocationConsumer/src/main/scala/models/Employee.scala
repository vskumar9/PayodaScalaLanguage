package models

import java.time.Instant

/**
 * Represents an employee record in the system.
 *
 * This model links an employee to a corresponding user account (`userId`) and
 * captures departmental and job-related metadata as well as lifecycle and audit fields.
 *
 * Typical usage:
 *  - Used by allocation, maintenance, and inventory modules to resolve the user associated
 *    with an action (creator, assignee, allocator, etc.).
 *  - Supports soft-delete semantics via `isDeleted` and `deletedAt`.
 *
 * @param employeeId Unique identifier for the employee (primary key).
 * @param userId     Foreign key linking this employee to a user account in the Users table.
 * @param department Department name or team the employee belongs to.
 * @param designation Optional job designation/title (e.g., "Senior Engineer").
 * @param isActive   Indicates whether the employee is currently active in the organization.
 * @param createdAt  Timestamp indicating when the employee record was created.
 * @param updatedAt  Timestamp indicating when the employee record was last updated.
 * @param isDeleted  Soft-delete flag. True means the record is logically deleted.
 * @param deletedAt  Optional timestamp indicating when the soft-delete occurred.
 */
case class Employee(
                     employeeId: Int,
                     userId: Int,
                     department: String,
                     designation: Option[String],
                     isActive: Boolean,
                     createdAt: Instant,
                     updatedAt: Instant,
                     isDeleted: Boolean = false,
                     deletedAt: Option[Instant] = None
                   )
