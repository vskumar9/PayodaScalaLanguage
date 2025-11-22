package models

import java.time.Instant

/**
 * Represents a team within the organization.
 *
 * Teams are used to group users for task assignments, event planning, and
 * responsibility delegation. Each team may contain multiple team members and
 * supports soft-delete behavior for archival purposes.
 *
 * @param teamId       optional unique identifier of the team (None before creation)
 * @param teamName     name of the team
 * @param description  optional text describing the team's purpose or responsibilities
 * @param contactEmail optional contact email for the team
 * @param contactPhone optional contact phone for the team
 * @param isActive     flag indicating whether the team is currently active
 *
 * @param createdAt    timestamp when the team record was created
 * @param isDeleted    soft-delete flag indicating whether the team is deleted
 * @param deletedAt    optional timestamp marking when the team was soft-deleted
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
