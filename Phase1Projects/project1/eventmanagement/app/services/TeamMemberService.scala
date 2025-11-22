package services

import javax.inject._
import repositories.TeamMembersRepository
import models.TeamMember
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/**
 * Service layer responsible for managing team membership operations,
 * including creation, retrieval, soft deletion, and restoration of team members.
 *
 * This service provides the business logic on top of [[TeamMembersRepository]],
 * ensuring correct timestamp initialization and enforcing the application's
 * soft-delete semantics.
 *
 * Typical responsibilities:
 *   - Adding users to teams
 *   - Listing active (non-deleted) team members for a team
 *   - Soft-removing members instead of permanently deleting them
 *   - Restoring previously removed members
 *
 * @param membersRepo repository handling persistence of team membership data
 * @param ec          execution context for asynchronous operations
 */
@Singleton
class TeamMemberService @Inject()(
                                   membersRepo: TeamMembersRepository
                                 )(implicit ec: ExecutionContext) {

  /**
   * Adds a user to a team by creating a new [[TeamMember]] record.
   *
   * Automatically sets:
   *   - `addedAt` to the current timestamp
   *   - `isDeleted = false`
   *
   * @param teamId           team to which the user is being added
   * @param userId           user being added
   * @param roleInTeam       optional role description within the team
   * @param isPrimaryContact indicates if the user is the main contact for the team
   * @return Future[Int] newly created teamMemberId
   */
  def addMember(teamId: Int,
                userId: Int,
                roleInTeam: Option[String],
                isPrimaryContact: Boolean): Future[Int] = {

    val now = Instant.now()
    val member = TeamMember(
      teamMemberId = None,
      teamId = teamId,
      userId = userId,
      roleInTeam = roleInTeam,
      isPrimaryContact = isPrimaryContact,
      addedAt = now,
      isDeleted = false,
      deletedAt = None
    )

    membersRepo.addMember(member)
  }

  /**
   * Retrieves all active (non-deleted) members of the specified team.
   *
   * @param teamId ID of the team whose members are requested
   * @return Future sequence of active team members
   */
  def getMembersByTeam(teamId: Int): Future[Seq[TeamMember]] =
    membersRepo.findByTeamActive(teamId)

  /**
   * Soft-deletes a team member by marking them as deleted instead of
   * permanently removing the record.
   *
   * @param id team member ID to remove
   * @return number of rows affected (1 if success, 0 if not found)
   */
  def softDeleteMember(id: Int): Future[Int] =
    membersRepo.softDelete(id)

  /**
   * Restores a previously soft-deleted team member.
   *
   * @param id team member ID to restore
   * @return number of rows affected (1 if success, 0 if not found)
   */
  def restoreMember(id: Int): Future[Int] =
    membersRepo.restore(id)
}
