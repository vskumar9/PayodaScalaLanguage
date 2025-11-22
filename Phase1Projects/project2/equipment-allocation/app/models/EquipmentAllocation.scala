package models

import java.time.Instant

/**
 * Represents the allocation of an equipment item to an employee.
 *
 * This model captures the complete lifecycle of an equipment allocation —
 * including allocation details, expected return, actual return information,
 * condition on return, and soft-delete metadata. It is central to tracking
 * equipment usage, maintaining audit logs, and triggering automated workflows
 * such as overdue reminders or maintenance tickets.
 *
 * @param allocationId Unique identifier for the allocation record
 * @param equipmentId ID of the equipment being allocated
 * @param employeeId ID of the employee who receives/uses the equipment
 * @param allocatedByEmployeeId ID of the employee who performed the allocation
 *                              (e.g., receptionist or admin)
 * @param purpose Reason or purpose for which the equipment is issued
 * @param allocatedAt Timestamp when the equipment was allocated
 * @param expectedReturnAt Expected timestamp by which the equipment should be returned
 * @param returnedAt Optional timestamp when the equipment was actually returned
 * @param conditionOnReturn Equipment condition upon return (e.g., "GOOD", "DAMAGED")
 * @param returnNotes Optional additional notes on the return (damage details, comments)
 * @param status Current allocation status:
 *               - `"ACTIVE"`    → allocated and not yet returned
 *               - `"RETURNED"`  → returned on time or early
 *               - `"OVERDUE"`   → return date passed without return
 *               - `"CANCELLED"` → allocation cancelled before use
 * @param createdAt Timestamp when the record was created
 * @param updatedAt Timestamp when the record was last modified
 * @param isDeleted Soft-delete flag (true if the record is logically deleted)
 * @param deletedAt Optional timestamp of deletion
 */
case class EquipmentAllocation(
                                allocationId: Int,
                                equipmentId: Int,
                                employeeId: Int,             // employee using the equipment
                                allocatedByEmployeeId: Int,  // receptionist/admin who allocated

                                purpose: String,

                                allocatedAt: Instant,
                                expectedReturnAt: Instant,

                                returnedAt: Option[Instant],
                                conditionOnReturn: Option[String],   // GOOD, DAMAGED
                                returnNotes: Option[String],

                                status: String,  // ACTIVE, RETURNED, OVERDUE, CANCELLED

                                createdAt: Instant,
                                updatedAt: Instant,
                                isDeleted: Boolean,
                                deletedAt: Option[Instant]
                              )
