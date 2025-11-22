package models

import java.time.Instant

/**
 * Represents a maintenance or repair ticket raised for an equipment item.
 *
 * This model is used in the maintenance workflow to track:
 *   - Issues reported by employees
 *   - Assignment of tickets to maintenance staff
 *   - Ticket lifecycle transitions (OPEN → IN_PROGRESS → RESOLVED → CLOSED)
 *   - Severity of issues and escalation handling
 *   - Links to the associated equipment and (optionally) the allocation during which the issue occurred
 *
 * ### Ticket Lifecycle
 * - **OPEN**: Ticket created and awaiting review.
 * - **IN_PROGRESS**: Maintenance staff working on the issue.
 * - **RESOLVED**: Issue fixed but not yet formally closed.
 * - **CLOSED**: Ticket finalized and archived.
 *
 * ### Severity Levels
 * - **LOW**, **MEDIUM**, **HIGH**, **CRITICAL**
 *   Used for prioritization and alerting.
 *   Critical tickets may trigger additional notifications by maintenance actors.
 *
 * ### Relationships
 * - **equipmentId** links to the equipment item reporting the issue.
 * - **allocationId** (optional) identifies the allocation session during which the issue was found.
 * - **createdByEmployeeId** identifies the employee who reported the issue.
 * - **assignedToEmployeeId** identifies maintenance personnel responsible for handling it.
 *
 * Supports **soft deletion** through `isDeleted` and `deletedAt`.
 *
 * @param ticketId                Unique identifier for this maintenance ticket.
 * @param equipmentId             ID of the equipment associated with the issue.
 * @param allocationId            Optional allocation ID if the issue occurred during an active allocation.
 * @param issueReportedAt         Timestamp when the issue was reported.
 * @param issueNotes              Detailed description of the problem or symptoms.
 * @param status                  Current ticket status (OPEN, IN_PROGRESS, RESOLVED, CLOSED).
 * @param severity                Severity level of the issue (LOW, MEDIUM, HIGH, CRITICAL).
 * @param createdByEmployeeId     Employee who created/reported the ticket.
 * @param assignedToEmployeeId    Optional employee assigned to resolve the issue.
 * @param isResolved              Indicates whether the issue has been resolved.
 * @param resolvedAt              Timestamp when the issue was resolved, if applicable.
 * @param createdAt               Timestamp when this ticket record was created.
 * @param updatedAt               Timestamp when this record was last modified.
 * @param isDeleted               Soft-delete flag indicating logical removal.
 * @param deletedAt               Optional timestamp when the ticket was soft-deleted.
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
