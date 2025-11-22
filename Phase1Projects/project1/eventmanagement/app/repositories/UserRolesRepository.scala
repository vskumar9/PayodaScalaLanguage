package repositories

import javax.inject._
import models.UserRole
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}

/**
 * Repository for managing the mapping between users and roles
 * stored in the `user_roles` table.
 *
 * A [[models.UserRole]] links a user to a specific role and is a core part of
 * the application's RBAC (Role-Based Access Control) system.
 *
 * This repository supports:
 *
 *   - assigning roles to users
 *   - retrieving all roles assigned to a user
 *   - removing a role assignment
 *
 * Each record represents a single user → role assignment.
 *
 * @param dbConfigProvider Slick database configuration provider
 * @param ec               execution context for asynchronous DB operations
 */
@Singleton
class UserRolesRepository @Inject()(
                                     dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                   )(implicit ec: ExecutionContext) extends SlickMapping {

  /** Slick database configuration (MySQL). */
  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /**
   * Slick table mapping for the `user_roles` table.
   */
  private class UserRolesTable(tag: Tag) extends Table[UserRole](tag, "user_roles") {
    def id     = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def userId = column[Int]("user_id")
    def roleId = column[Int]("role_id")

    /** Maps row ⇄ UserRole case class. */
    def * = (id.?, userId, roleId) <> (UserRole.tupled, UserRole.unapply)
  }

  /** Table query for user-role mappings. */
  private val userRoles = TableQuery[UserRolesTable]

  /**
   * Assigns a role to a user by inserting a new `user_roles` record.
   *
   * @param ur user-role mapping to insert
   * @return generated ID of the new mapping
   */
  def assign(ur: UserRole): Future[Int] =
    db.run(userRoles returning userRoles.map(_.id) += ur)

  /**
   * Retrieves all role assignments for a given user.
   *
   * @param userId user identifier
   * @return sequence of user-role mappings
   */
  def findByUser(userId: Int): Future[Seq[UserRole]] =
    db.run(userRoles.filter(_.userId === userId).result)

  /**
   * Removes a role assignment by its ID.
   *
   * @param id assignment identifier
   * @return number of affected rows
   */
  def remove(id: Int): Future[Int] =
    db.run(userRoles.filter(_.id === id).delete)
}
