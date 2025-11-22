package repositories

import javax.inject._
import models.User
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * Repository for managing user accounts stored in the `users` table.
 *
 * A [[models.User]] represents an application user, including authentication
 * details, contact information, and lifecycle metadata. This repository provides:
 *
 *   - creation of users
 *   - lookup by ID or username
 *   - listing active (non-deleted) users
 *   - updating user records
 *   - soft-deleting and restoring users
 *
 * Soft deletes are handled through the `is_deleted` and `deleted_at` fields.
 *
 * @param dbConfigProvider Slick database configuration provider
 * @param ec               execution context for asynchronous DB operations
 */
@Singleton
class UsersRepository @Inject()(
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
   * Slick mapping for the `users` table.
   */
  private class UsersTable(tag: Tag) extends Table[User](tag, "users") {
    def userId      = column[Int]("user_id", O.PrimaryKey, O.AutoInc)
    def username    = column[String]("username")
    def passwordHash= column[String]("password_hash")
    def fullName    = column[String]("full_name")
    def email       = column[String]("email")
    def phone       = column[Option[String]]("phone")
    def isActive    = column[Boolean]("is_active")
    def isDeleted   = column[Boolean]("is_deleted")
    def createdAt   = column[Instant]("created_at")
    def updatedAt   = column[Instant]("updated_at")
    def deletedAt   = column[Option[Instant]]("deleted_at")

    /** Maps DB rows to User case class. */
    def * =
      (
        userId.?, username, passwordHash, fullName, email, phone,
        isActive, isDeleted, createdAt, updatedAt, deletedAt
      ) <> (User.tupled, User.unapply)
  }

  /** Table query interface for the `users` table. */
  private val users = TableQuery[UsersTable]

  /**
   * Creates a new user in the database.
   *
   * @param u user record to insert
   * @return generated user ID
   */
  def create(u: User): Future[Int] =
    db.run(users returning users.map(_.userId) += u)

  /**
   * Retrieves a user by ID, only if they are active (not soft-deleted).
   *
   * @param id user identifier
   * @return Some(User) if found and active, None otherwise
   */
  def findByIdActive(id: Int): Future[Option[User]] =
    db.run(
      users
        .filter(u => u.userId === id && u.isDeleted === false)
        .result
        .headOption
    )

  /**
   * Retrieves a user by ID, including deleted records.
   *
   * @param id user identifier
   * @return Some(User) if found, or None
   */
  def findByIdIncludeDeleted(id: Int): Future[Option[User]] =
    db.run(users.filter(_.userId === id).result.headOption)

  /**
   * Retrieves an active (non-deleted) user by username.
   *
   * @param username login username
   * @return Some(User) if found and active, None otherwise
   */
  def findByUsername(username: String): Future[Option[User]] =
    db.run(
      users
        .filter(u => u.username === username && u.isDeleted === false)
        .result
        .headOption
    )

  /**
   * Lists all active (non-deleted) users.
   *
   * @return sequence of users
   */
  def listActive(): Future[Seq[User]] =
    db.run(users.filter(_.isDeleted === false).result)

  /**
   * Updates an existing active user.
   *
   * @param id user identifier
   * @param u  updated user data
   * @return number of affected rows
   */
  def update(id: Int, u: User): Future[Int] =
    db.run(
      users
        .filter(u0 => u0.userId === id && u0.isDeleted === false)
        .update(u.copy(userId = Some(id)))
    )

  /**
   * Soft-deletes a user.
   *
   * Sets:
   *   - `is_deleted = true`
   *   - `deleted_at = now`
   *   - `updated_at = now`
   *
   * @param id user identifier
   * @return number of affected rows
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    val q =
      users
        .filter(u => u.userId === id && u.isDeleted === false)
        .map(u => (u.isDeleted, u.deletedAt, u.updatedAt))
        .update((true, Some(now), now))

    db.run(q)
  }

  /**
   * Restores a previously soft-deleted user record.
   *
   * Sets:
   *   - `is_deleted = false`
   *   - `deleted_at = None`
   *   - `updated_at = now`
   *
   * @param id user identifier
   * @return number of affected rows
   */
  def restore(id: Int): Future[Int] = {
    val now = Instant.now()
    val q =
      users
        .filter(u => u.userId === id && u.isDeleted === true)
        .map(u => (u.isDeleted, u.deletedAt, u.updatedAt))
        .update((false, None, now))

    db.run(q)
  }
}
