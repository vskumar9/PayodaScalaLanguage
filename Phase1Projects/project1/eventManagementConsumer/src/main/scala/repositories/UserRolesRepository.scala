package repositories

import models.UserRole
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository responsible for managing user–role mappings ([[UserRole]]).
 *
 * This repository is part of the system's Role-Based Access Control (RBAC)
 * model. It enables assigning roles to users, retrieving role assignments,
 * and removing role associations.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context for async database operations.
 */
@Singleton
class UserRolesRepository @Inject()(
                                     dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                   )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /**
   * Slick table mapping for the `user_roles` table.
   *
   * This table represents the many-to-many relationship between users and roles.
   *
   * @param tag Slick table tag.
   */
  private class UserRolesTable(tag: Tag) extends Table[UserRole](tag, "user_roles") {
    def id     = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def userId = column[Int]("user_id")
    def roleId = column[Int]("role_id")

    /** Maps SQL columns to the [[UserRole]] case class. */
    def * = (id.?, userId, roleId) <> (UserRole.tupled, UserRole.unapply)
  }

  /** Slick query interface for the `user_roles` table. */
  private val userRoles = TableQuery[UserRolesTable]

  /**
   * Assigns a role to a user by inserting a new user–role mapping.
   *
   * @param ur UserRole instance.
   * @return   Future containing the generated mapping ID.
   */
  def assign(ur: UserRole): Future[Int] =
    db.run(userRoles returning userRoles.map(_.id) += ur)

  /**
   * Retrieves all role mappings associated with a specific user.
   *
   * @param userId User ID.
   * @return       Future sequence of [[UserRole]] entries for that user.
   */
  def findByUser(userId: Int): Future[Seq[UserRole]] =
    db.run(userRoles.filter(_.userId === userId).result)

  /**
   * Removes a user–role mapping from the database.
   *
   * @param id Mapping ID to delete.
   * @return   Future number of affected rows (0 or 1).
   */
  def remove(id: Int): Future[Int] =
    db.run(userRoles.filter(_.id === id).delete)
}
