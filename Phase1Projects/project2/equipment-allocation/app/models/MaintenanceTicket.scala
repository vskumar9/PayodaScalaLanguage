package models

import java.time.Instant

/**
 * Represents a maintenance ticket raised for an equipment item.
 *
 * A maintenance ticket tracks issues reported by employees, assignment to a
 * maintenance team member, severity of the problem, resolution status, and the
 * entire lifecycle of the maintenance workflow. Tickets may be linked to an
 * equipment allocation if the issue occurred during usage.
 *
 * @param ticketId Unique identifier for the maintenance ticket
 * @param equipmentId ID of the equipment for which maintenance is required
 * @param allocationId Optional allocation ID if the issue occurred while the equipment was allocated
 *
 * @param issueReportedAt Timestamp when the issue was initially reported
 * @param issueNotes Description of the problem or symptoms reported
 *
 * @param status Current status of the ticket:
 *               - `"OPEN"`         → issue reported, not yet assigned
 *               - `"IN_PROGRESS"`  → technician working on it
 *               - `"RESOLVED"`     → issue fixed, pending closure
 *               - `"CLOSED"`       → fully resolved and closed
 *
 * @param severity Severity of the issue:
 *                 - `"LOW"`
 *                 - `"MEDIUM"`
 *                 - `"HIGH"`
 *                 - `"CRITICAL"`
 *
 * @param createdByEmployeeId ID of the employee who reported or created the ticket
 * @param assignedToEmployeeId Optional ID of the employee assigned to handle the ticket
 *
 * @param isResolved Whether the issue has been resolved
 * @param resolvedAt Optional timestamp when the issue was resolved
 *
 * @param createdAt Timestamp when the ticket record was created
 * @param updatedAt Timestamp of last modification to the ticket
 * @param isDeleted Soft-delete flag indicating logical deletion
 * @param deletedAt Optional timestamp when soft deletion occurred
 */
case class MaintenanceTicket(
                              ticketId: Int,
                              equipmentId: Int,
                              allocationId: Option[Int],

                              issueReportedAt: Instant,
                              issueNotes: String,

                              status: String,              // OPEN, IN_PROGRESS, RESOLVED, CLOSED
                              severity: String,            // LOW, MEDIUM, HIGH, CRITICAL

                              createdByEmployeeId: Int,
                              assignedToEmployeeId: Option[Int],

                              isResolved: Boolean,
                              resolvedAt: Option[Instant],

                              createdAt: Instant,
                              updatedAt: Instant,
                              isDeleted: Boolean,
                              deletedAt: Option[Instant]
                            )
