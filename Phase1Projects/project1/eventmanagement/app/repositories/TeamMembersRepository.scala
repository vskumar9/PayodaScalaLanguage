package repositories

import javax.inject._
import models.TeamMember
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * Repository for managing team member assignments stored in the `team_members` table.
 *
 * A [[models.TeamMember]] represents a user's membership within a team, including
 * optional role information and whether the user is the primary contact for the team.
 * This repository supports:
 *
 *   - adding new team members
 *   - retrieving active (non-deleted) team members for a given team
 *   - soft-deleting team members
 *   - restoring previously deleted team members
 *
 * Soft-delete behavior is implemented using the `is_deleted` and `deleted_at` columns.
 *
 * @param dbConfigProvider Slick database configuration provider
 * @param ec               execution context for asynchronous DB operations
 */
@Singleton
class TeamMembersRepository @Inject()(
                                       dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                     )(implicit ec: ExecutionContext) extends SlickMapping {

  /** Slick database configuration (MySQL). */
  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit column mapping for java.time.Instant. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `team_members` table.
   */
  private class MembersTable(tag: Tag) extends Table[TeamMember](tag, "team_members") {
    def teamMemberId     = column[Int]("team_member_id", O.PrimaryKey, O.AutoInc)
    def teamId           = column[Int]("team_id")
    def userId           = column[Int]("user_id")
    def roleInTeam       = column[Option[String]]("role_in_team")
    def isPrimaryContact = column[Boolean]("is_primary_contact")
    def addedAt          = column[Instant]("added_at")
    def isDeleted        = column[Boolean]("is_deleted")
    def deletedAt        = column[Option[Instant]]("deleted_at")

    /** Mapping to/from TeamMember case class. */
    def * =
      (teamMemberId.?, teamId, userId, roleInTeam, isPrimaryContact, addedAt, isDeleted, deletedAt)
        .<> (TeamMember.tupled, TeamMember.unapply)
  }

  /** Table query for performing operations on team member records. */
  private val members = TableQuery[MembersTable]

  /**
   * Adds a new member to a team.
   *
   * @param m team member record to insert
   * @return generated team_member_id
   */
  def addMember(m: TeamMember): Future[Int] =
    db.run(members returning members.map(_.teamMemberId) += m)

  /**
   * Retrieves all active (non-deleted) members of a given team.
   *
   * @param teamIdVal team identifier
   * @return sequence of active team members
   */
  def findByTeamActive(teamIdVal: Int): Future[Seq[TeamMember]] =
    db.run(
      members
        .filter(m => m.teamId === teamIdVal && m.isDeleted === false)
        .result
    )

  /**
   * Soft-deletes a team member.
   *
   * Sets:
   *   - `is_deleted = true`
   *   - `deleted_at = now`
   *
   * @param id team member identifier
   * @return number of affected rows
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      members
        .filter(m => m.teamMemberId === id && m.isDeleted === false)
        .map(m => (m.isDeleted, m.deletedAt))
        .update((true, Some(now)))
    )
  }

  /**
   * Restores a previously soft-deleted team member.
   *
   * Sets:
   *   - `is_deleted = false`
   *   - `deleted_at = None`
   *
   * @param id team member identifier
   * @return number of affected rows
   */
  def restore(id: Int): Future[Int] =
    db.run(
      members
        .filter(m => m.teamMemberId === id && m.isDeleted === true)
        .map(m => (m.isDeleted, m.deletedAt))
        .update((false, None))
    )
}
