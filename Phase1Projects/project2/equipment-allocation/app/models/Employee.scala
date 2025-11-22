package models

import java.time.Instant

/**
 * Represents an employee within the organization.
 *
 * This model links a user account to employee-specific details such as
 * department, designation, and employment status. It is used throughout
 * the system for access control, audit tracking, equipment allocation,
 * maintenance ticketing, and other employee-related operations.
 *
 * @param employeeId Unique identifier for the employee
 * @param userId Reference to the associated User account (foreign key)
 * @param department Name of the department the employee belongs to
 * @param designation Optional job title or position of the employee
 * @param isActive Indicates whether the employee is currently active
 * @param createdAt Timestamp when the employee record was created
 * @param updatedAt Timestamp when the employee record was last updated
 * @param isDeleted Soft-delete flag (true if the record is deleted)
 * @param deletedAt Optional timestamp representing when the record was soft-deleted
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
