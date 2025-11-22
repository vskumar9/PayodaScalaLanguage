package repositories

import javax.inject._
import models.Team
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * Repository for managing teams stored in the `teams` table.
 *
 * A [[models.Team]] represents an organizational group responsible for handling
 * tasks and event operations within the system. This repository provides methods for:
 *
 *   - creating new teams
 *   - retrieving active (non-deleted) teams
 *   - listing all active teams
 *   - updating team details
 *   - soft-deleting and restoring teams
 *
 * Soft deletes are controlled using the `is_deleted` and `deleted_at` fields.
 *
 * @param dbConfigProvider Slick database configuration provider
 * @param ec               execution context for asynchronous operations
 */
@Singleton
class TeamsRepository @Inject()(
                                 dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                               )(implicit ec: ExecutionContext) extends SlickMapping {

  /** Slick database configuration (MySQL). */
  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit mapper for java.time.Instant. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `teams` table.
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

    /** Maps DB row ↔ Team case class. */
    def * =
      (
        teamId.?, teamName, description, contactEmail, contactPhone,
        isActive, createdAt, isDeleted, deletedAt
      ) <> (Team.tupled, Team.unapply)
  }

  /** Table query for executing operations against the teams table. */
  private val teams = TableQuery[TeamsTable]

  /**
   * Inserts a new team into the database.
   *
   * @param team the team to create
   * @return the generated team ID
   */
  def create(team: Team): Future[Int] =
    db.run(teams returning teams.map(_.teamId) += team)

  /**
   * Fetches an active (non-deleted) team by its identifier.
   *
   * @param id team identifier
   * @return Some(team) if found and active, None otherwise
   */
  def findByIdActive(id: Int): Future[Option[Team]] =
    db.run(
      teams
        .filter(t => t.teamId === id && t.isDeleted === false)
        .result
        .headOption
    )

  /**
   * Retrieves all active (non-deleted) teams.
   *
   * @return sequence of teams
   */
  def listActive(): Future[Seq[Team]] =
    db.run(teams.filter(_.isDeleted === false).result)

  /**
   * Updates an existing active team.
   *
   * @param id   team identifier
   * @param team updated team entity
   * @return number of affected rows
   */
  def update(id: Int, team: Team): Future[Int] =
    db.run(
      teams
        .filter(t => t.teamId === id && t.isDeleted === false)
        .update(team.copy(teamId = Some(id)))
    )

  /**
   * Soft-deletes a team.
   *
   * Sets:
   *   - `is_deleted = true`
   *   - `deleted_at = now`
   *
   * @param id team identifier
   * @return number of affected rows
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
   * Sets:
   *   - `is_deleted = false`
   *   - `deleted_at = None`
   *
   * @param id team identifier
   * @return number of affected rows
   */
  def restore(id: Int): Future[Int] =
    db.run(
      teams
        .filter(t => t.teamId === id && t.isDeleted === true)
        .map(t => (t.isDeleted, t.deletedAt))
        .update((false, None))
    )
}
