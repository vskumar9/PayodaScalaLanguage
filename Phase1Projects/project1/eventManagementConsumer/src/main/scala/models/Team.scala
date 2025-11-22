package models

import java.time.Instant

/**
 * Represents a team within the organization. Teams can be assigned tasks,
 * receive notifications, and participate in event-related workflows.
 * Includes metadata for contact information, activation status, and
 * soft-deletion tracking.
 *
 * @param teamId        Optional unique identifier for the team (assigned when persisted).
 *
 * @param teamName      Name of the team (e.g., "Maintenance", "IT Support", "Logistics").
 *
 * @param description   Optional descriptive text about the team and its responsibilities.
 *
 * @param contactEmail  Optional email address used to communicate with the team.
 *
 * @param contactPhone  Optional phone number for team communication.
 *
 * @param isActive      Indicates whether the team is currently active and usable in the system.
 *
 * @param createdAt     Timestamp representing when the team record was created.
 *
 * @param isDeleted     Soft-deletion flag indicating whether the team has been removed.
 *
 * @param deletedAt     Optional timestamp indicating when the team was soft-deleted.
 */
case class Team(
                 teamId: Option[Int],
                 teamName: String,
                 description: Option[String],
                 contactEmail: Option[String],
                 contactPhone: Option[String],
                 isActive: Boolean,
                 createdAt: Instant,
                 isDeleted: Boolean,
                 deletedAt: Option[Instant]
               )
