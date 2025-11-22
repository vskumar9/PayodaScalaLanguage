package repositories

import models.TeamMember
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository responsible for CRUD and lifecycle operations on [[TeamMember]] entities.
 *
 * A team member represents the association between a user and a team,
 * optionally including their role and primary-contact status. This repository
 * supports adding members, querying active members, and handling soft deletion
 * and restoration of membership records.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context used for asynchronous DB operations.
 */
@Singleton
class TeamMembersRepository @Inject()(
                                       dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                     )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit Slick column mapping for Java [[Instant]]. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `team_members` table.
   *
   * Maps the team-member relationship, including role-in-team,
   * primary contact flag, and soft deletion metadata.
   *
   * @param tag Slick table tag.
   */
  private class MembersTable(tag: Tag) extends Table[TeamMember](tag, "team_members") {
    def teamMemberId      = column[Int]("team_member_id", O.PrimaryKey, O.AutoInc)
    def teamId            = column[Int]("team_id")
    def userId            = column[Int]("user_id")
    def roleInTeam        = column[Option[String]]("role_in_team")
    def isPrimaryContact  = column[Boolean]("is_primary_contact")
    def addedAt           = column[Instant]("added_at")
    def isDeleted         = column[Boolean]("is_deleted")
    def deletedAt         = column[Option[Instant]]("deleted_at")

    /** Projection mapping columns to the [[TeamMember]] case class. */
    def * =
      (
        teamMemberId.?, teamId, userId, roleInTeam,
        isPrimaryContact, addedAt, isDeleted, deletedAt
      ) <> (TeamMember.tupled, TeamMember.unapply)
  }

  /** Slick query interface for `team_members`. */
  private val members = TableQuery[MembersTable]

  /**
   * Inserts a new team member record into the database.
   *
   * @param m Team member to add.
   * @return  Future containing the generated primary key (teamMemberId).
   */
  def addMember(m: TeamMember): Future[Int] =
    db.run(members returning members.map(_.teamMemberId) += m)

  /**
   * Retrieves all active (non-deleted) members of a given team.
   *
   * @param teamIdVal ID of the team.
   * @return          Future list of active team members.
   */
  def findByTeamActive(teamIdVal: Int): Future[Seq[TeamMember]] =
    db.run(
      members
        .filter(m => m.teamId === teamIdVal && m.isDeleted === false)
        .result
    )

  /**
   * Soft deletes a team member record.
   *
   * The member is marked as deleted and a deletion timestamp is recorded.
   *
   * @param id Team member ID.
   * @return   Future number of affected rows.
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
   * @param id Team member ID.
   * @return   Future number of affected rows.
   */
  def restore(id: Int): Future[Int] =
    db.run(
      members
        .filter(m => m.teamMemberId === id && m.isDeleted === true)
        .map(m => (m.isDeleted, m.deletedAt))
        .update((false, None))
    )
}
