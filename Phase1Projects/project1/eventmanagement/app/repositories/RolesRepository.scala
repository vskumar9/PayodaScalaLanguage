package repositories

import javax.inject._
import models.Role
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}

/**
 * Repository for managing user roles stored in the `roles` table.
 *
 * A [[models.Role]] represents a permission group used in the application's
 * RBAC (Role-Based Access Control) system. This repository provides basic CRUD
 * operations for roles, including:
 *
 *   - creating roles
 *   - retrieving a role by ID
 *   - listing all roles
 *   - updating an existing role
 *   - deleting a role
 *
 * @param dbConfigProvider Slick database configuration provider
 * @param ec               execution context for asynchronous DB operations
 */
@Singleton
class RolesRepository @Inject()(
                                 dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                               )(implicit ec: ExecutionContext) extends SlickMapping {

  /** Slick database configuration (MySQL). */
  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /**
   * Slick table mapping for the `roles` table.
   */
  private class RolesTable(tag: Tag) extends Table[Role](tag, "roles") {
    def roleId   = column[Int]("role_id", O.PrimaryKey, O.AutoInc)
    def roleName = column[String]("role_name")

    /** Mapping to/from Role case class. */
    def * = (roleId.?, roleName) <> (Role.tupled, Role.unapply)
  }

  /** Table query for performing queries on the roles table. */
  private val roles = TableQuery[RolesTable]

  /**
   * Inserts a new role into the database.
   *
   * @param r role to insert
   * @return the generated roleId
   */
  def create(r: Role): Future[Int] =
    db.run(roles returning roles.map(_.roleId) += r)

  /**
   * Retrieves a role by its ID.
   *
   * @param id role identifier
   * @return Some(Role) if found, None otherwise
   */
  def findById(id: Int): Future[Option[Role]] =
    db.run(roles.filter(_.roleId === id).result.headOption)

  /**
   * Retrieves all roles.
   *
   * @return sequence of all roles
   */
  def list(): Future[Seq[Role]] =
    db.run(roles.result)

  /**
   * Updates an existing role.
   *
   * @param id role identifier to update
   * @param r  updated role data
   * @return number of affected rows
   */
  def update(id: Int, r: Role): Future[Int] =
    db.run(roles.filter(_.roleId === id).update(r.copy(roleId = Some(id))))

  /**
   * Deletes a role permanently from the database.
   *
   * @param id role identifier
   * @return number of affected rows
   */
  def delete(id: Int): Future[Int] =
    db.run(roles.filter(_.roleId === id).delete)
}
