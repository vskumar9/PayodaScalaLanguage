package models

import java.time.Instant

/**
 * Represents a user's membership within a team.
 *
 * A team member links a user to a team, optionally specifying their role
 * and whether they are the team's primary point of contact. The model also
 * includes audit timestamps and soft-delete support.
 *
 * @param teamMemberId    optional unique identifier for this team-member relationship
 * @param teamId          identifier of the team the user is part of
 * @param userId          identifier of the user assigned to the team
 * @param roleInTeam      optional description of the user's role within the team
 * @param isPrimaryContact flag indicating whether this member is the primary contact
 *
 * @param addedAt         timestamp when the user was added to the team
 * @param isDeleted       soft-delete flag indicating whether the membership is deleted
 * @param deletedAt       optional timestamp marking when the membership was soft-deleted
 */
case class TeamMember(
                       teamMemberId: Option[Int],
                       teamId: Int,
                       userId: Int,
                       roleInTeam: Option[String],
                       isPrimaryContact: Boolean,
                       addedAt: Instant,
                       isDeleted: Boolean,
                       deletedAt: Option[Instant]
                     )
