package repositories

import models.Role
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing [[Role]] entities.
 *
 * This repository provides CRUD operations for system roles, which are used
 * in Role-Based Access Control (RBAC) to define user permissions and access
 * levels. Supports creating roles, retrieving individual roles, listing all
 * roles, updating role details, and deleting roles.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context for asynchronous DB operations.
 */
@Singleton
class RolesRepository @Inject()(
                                 dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                               )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /**
   * Slick table mapping for the `roles` database table.
   *
   * This table defines the core RBAC roles used in the system.
   *
   * @param tag Slick table tag.
   */
  private class RolesTable(tag: Tag) extends Table[Role](tag, "roles") {
    def roleId   = column[Int]("role_id", O.PrimaryKey, O.AutoInc)
    def roleName = column[String]("role_name")

    /** Maps SQL columns to the [[Role]] case class. */
    def * = (roleId.?, roleName) <> (Role.tupled, Role.unapply)
  }

  /** Slick query object for the `roles` table. */
  private val roles = TableQuery[RolesTable]

  /**
   * Creates a new role in the database.
   *
   * @param r Role to create.
   * @return  Future containing the auto-generated role ID.
   */
  def create(r: Role): Future[Int] =
    db.run(roles returning roles.map(_.roleId) += r)

  /**
   * Finds a role by its ID.
   *
   * @param id Role ID.
   * @return   Future optional containing the role if it exists.
   */
  def findById(id: Int): Future[Option[Role]] =
    db.run(roles.filter(_.roleId === id).result.headOption)

  /**
   * Retrieves all roles defined in the system.
   *
   * @return Future sequence of roles.
   */
  def list(): Future[Seq[Role]] =
    db.run(roles.result)

  /**
   * Updates an existing role.
   *
   * @param id Role ID to update.
   * @param r  New role data.
   * @return   Future number of affected rows.
   */
  def update(id: Int, r: Role): Future[Int] =
    db.run(roles.filter(_.roleId === id).update(r.copy(roleId = Some(id))))

  /**
   * Deletes a role from the database.
   *
   * @param id Role ID to delete.
   * @return   Future number of affected rows.
   */
  def delete(id: Int): Future[Int] =
    db.run(roles.filter(_.roleId === id).delete)
}
