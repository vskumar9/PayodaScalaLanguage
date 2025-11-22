package services

import javax.inject._
import repositories.TeamsRepository
import models.Team
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/**
 * Service layer responsible for managing team entities, including creation,
 * retrieval, updates, soft deletion, and restoration.
 *
 * This service adds the business logic on top of [[TeamsRepository]] and
 * ensures that timestamps and soft-delete semantics are consistently handled.
 *
 * Responsibilities include:
 *   - Creating new teams
 *   - Listing active teams
 *   - Updating team details
 *   - Soft-deleting (marking as deleted) teams
 *   - Restoring previously deleted teams
 *
 * @param teamsRepo repository that performs CRUD operations on the `teams` table
 * @param ec        execution context for asynchronous operations
 */
@Singleton
class TeamService @Inject()(teamsRepo: TeamsRepository)(implicit ec: ExecutionContext) {

  /**
   * Creates a new team with the provided details.
   *
   * Automatically sets:
   *   - `isActive = true`
   *   - `isDeleted = false`
   *   - `createdAt = now`
   *
   * @param teamName     name of the team
   * @param description  optional textual description of the team
   * @param contactEmail optional team contact e-mail
   * @param contactPhone optional team contact phone number
   * @return Future[Int] ID of the newly created team
   */
  def createTeam(teamName: String,
                 description: Option[String],
                 contactEmail: Option[String],
                 contactPhone: Option[String]): Future[Int] = {

    val now = Instant.now()
    val t = Team(
      teamId = None,
      teamName = teamName,
      description = description,
      contactEmail = contactEmail,
      contactPhone = contactPhone,
      isActive = true,
      createdAt = now,
      isDeleted = false,
      deletedAt = None
    )

    teamsRepo.create(t)
  }

  /**
   * Retrieves a single active (non-deleted) team by ID.
   *
   * @param id team identifier
   * @return Future[Option[Team]] active team if found, otherwise None
   */
  def getTeam(id: Int): Future[Option[Team]] =
    teamsRepo.findByIdActive(id)

  /**
   * Retrieves all active (non-deleted) teams.
   *
   * @return Future[Seq[Team]] list of active teams
   */
  def listActiveTeams(): Future[Seq[Team]] =
    teamsRepo.listActive()

  /**
   * Updates an existing team. Automatically ensures the proper teamId is retained.
   *
   * @param id      ID of the team being updated
   * @param updated updated team details
   * @return number of rows affected (1 if success, 0 if team not found)
   */
  def updateTeam(id: Int, updated: Team): Future[Int] = {
    val toSave = updated.copy(teamId = Some(id))
    teamsRepo.update(id, toSave)
  }

  /**
   * Soft-deletes a team (marks it as deleted instead of permanently removing it).
   *
   * @param id ID of the team to delete
   * @return number of rows affected
   */
  def softDeleteTeam(id: Int): Future[Int] =
    teamsRepo.softDelete(id)

  /**
   * Restores a previously soft-deleted team.
   *
   * @param id ID of the team to restore
   * @return number of rows affected
   */
  def restoreTeam(id: Int): Future[Int] =
    teamsRepo.restore(id)
}
