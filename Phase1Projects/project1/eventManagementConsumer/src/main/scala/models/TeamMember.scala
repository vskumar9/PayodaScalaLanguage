package models

import java.time.Instant

/**
 * Represents the association between a user and a team, including the user’s
 * functional role within the team and metadata for tracking membership status.
 *
 * Team members may act as primary contacts, perform assigned tasks, receive
 * notifications, and participate in workflow operations depending on their role.
 *
 * @param teamMemberId    Optional unique identifier for the team membership record
 *                        (assigned when persisted).
 *
 * @param teamId          Identifier of the team the user belongs to.
 *
 * @param userId          Identifier of the user who is a member of the team.
 *
 * @param roleInTeam      Optional title or functional role within the team
 *                        (e.g., "Lead", "Technician", "Coordinator").
 *
 * @param isPrimaryContact Indicates whether this member is the primary point of contact
 *                         for the team.
 *
 * @param addedAt         Timestamp representing when the user was added to the team.
 *
 * @param isDeleted       Soft-deletion flag indicating whether the membership
 *                        record has been removed.
 *
 * @param deletedAt       Optional timestamp marking when the membership was soft-deleted.
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
