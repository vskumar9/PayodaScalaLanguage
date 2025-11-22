package repositories

import models.Team
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository responsible for CRUD and lifecycle management of [[Team]] entities.
 *
 * Teams represent organizational groups that can receive notifications,
 * be assigned tasks, and serve as functional units within workflows.
 * This repository provides operations for creating teams, listing active teams,
 * updating team information, and performing soft deletion and restoration.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context used for async DB operations.
 */
@Singleton
class TeamsRepository @Inject()(
                                 dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                               )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit Slick column mapping for Java [[Instant]] timestamps. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `teams` table.
   *
   * Stores team information including name, description, contact details,
   * activation status, and soft deletion metadata.
   *
   * @param tag Slick table tag.
   */
  private class TeamsTable(tag: Tag) extends Table[Team](tag, "teams") {
    def teamId       = column[Int]("team_id", O.PrimaryKey, O.AutoInc)
    def teamName     = column[String]("team_name")
    def description  = column[Option[String]]("description")
    def contactEmail = column[Option[String]]("contact_email")
    def contactPhone = column[Option[String]]("contact_phone")
    def isActive     = column[Boolean]("is_active")
    def createdAt    = column[Instant]("created_at")
    def isDeleted    = column[Boolean]("is_deleted")
    def deletedAt    = column[Option[Instant]]("deleted_at")

    /** Projection mapping SQL columns to the [[Team]] case class. */
    def * =
      (
        teamId.?, teamName, description, contactEmail,
        contactPhone, isActive, createdAt, isDeleted, deletedAt
      ) <> (Team.tupled, Team.unapply)
  }

  /** Slick query interface for the `teams` table. */
  private val teams = TableQuery[TeamsTable]

  /**
   * Creates a new team record in the database.
   *
   * @param team Team instance to insert.
   * @return     Future containing the generated team ID.
   */
  def create(team: Team): Future[Int] =
    db.run(teams returning teams.map(_.teamId) += team)

  /**
   * Retrieves a team by ID, but only if the team is not soft-deleted.
   *
   * @param id Team ID.
   * @return   Future optional with the team if found.
   */
  def findByIdActive(id: Int): Future[Option[Team]] =
    db.run(teams.filter(t => t.teamId === id && t.isDeleted === false).result.headOption)

  /**
   * Lists all active (non-deleted) teams.
   *
   * @return Future sequence of active teams.
   */
  def listActive(): Future[Seq[Team]] =
    db.run(teams.filter(_.isDeleted === false).result)

  /**
   * Updates an existing team, provided it is not soft-deleted.
   *
   * @param id   Team ID to update.
   * @param team Updated team data.
   * @return     Future number of affected rows.
   */
  def update(id: Int, team: Team): Future[Int] =
    db.run(
      teams
        .filter(t => t.teamId === id && t.isDeleted === false)
        .update(team.copy(teamId = Some(id)))
    )

  /**
   * Soft deletes a team by marking it as deleted and setting a deletion timestamp.
   *
   * @param id Team ID to delete.
   * @return   Future number of affected rows.
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      teams
        .filter(t => t.teamId === id && t.isDeleted === false)
        .map(t => (t.isDeleted, t.deletedAt))
        .update((true, Some(now)))
    )
  }

  /**
   * Restores a previously soft-deleted team.
   *
   * @param id Team ID to restore.
   * @return   Future number of affected rows.
   */
  def restore(id: Int): Future[Int] =
    db.run(
      teams
        .filter(t => t.teamId === id && t.isDeleted === true)
        .map(t => (t.isDeleted, t.deletedAt))
        .update((false, None))
    )
}
