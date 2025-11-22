package models

import java.time.Instant

/**
 * Represents an allocation of an equipment item to an employee.
 *
 * This model is central to the equipment lifecycle workflow and is used for:
 *   - Tracking which employee currently holds an item.
 *   - Determining overdue allocations and generating reminders.
 *   - Recording allocation, return, and cancellation history.
 *   - Supporting audits, ticketing, and inventory monitoring systems.
 *
 * The allocation lifecycle typically follows:
 *   1. **ACTIVE**     – Equipment allocated and in use.
 *   2. **OVERDUE**    – Expected return date has passed.
 *   3. **RETURNED**   – Employee has returned the equipment.
 *   4. **CANCELLED**  – Allocation voided or cancelled administratively.
 *
 * The model supports both normal and soft-delete behavior using `isDeleted` and `deletedAt`.
 *
 * @param allocationId            Unique identifier for this allocation record.
 * @param equipmentId             ID of the equipment that has been allocated.
 * @param employeeId              ID of the employee currently using the equipment.
 * @param allocatedByEmployeeId   ID of the employee who performed the allocation (e.g., receptionist/inventory staff).
 * @param purpose                 Short description of why the equipment is being allocated (e.g., "Development work").
 * @param allocatedAt             Timestamp when the equipment was allocated.
 * @param expectedReturnAt        Expected return timestamp; used for overdue detection.
 * @param returnedAt              Optional timestamp when the item was returned.
 * @param conditionOnReturn       Optional condition status at return ("GOOD", "DAMAGED", etc.).
 * @param returnNotes             Additional notes provided at the time of return.
 * @param status                  Lifecycle status (e.g., ACTIVE, RETURNED, OVERDUE, CANCELLED).
 * @param createdAt               Timestamp when this allocation record was created.
 * @param updatedAt               Timestamp when this allocation record was last updated.
 * @param isDeleted               Soft-delete flag; true indicates the record is logically removed.
 * @param deletedAt               Optional timestamp recording when the soft-delete occurred.
 */
case class EquipmentAllocation(
                                allocationId: Int,
                                equipmentId: Int,
                                employeeId: Int,             // employee who uses the equipment
                                allocatedByEmployeeId: Int,  // receptionist who allocated

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
