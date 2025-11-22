package repositories

import models.User
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository responsible for managing [[User]] entities.
 *
 * Provides CRUD operations, user lookup by username, soft deletion and restoration,
 * and utilities for filtering active users. This repository forms the foundation for
 * authentication, authorization, user management, and system administration flows.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context used for asynchronous DB operations.
 */
@Singleton
class UsersRepository @Inject()(
                                 dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                               )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit Slick column mapping for Java [[Instant]] timestamps. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `users` table.
   *
   * Stores all user account information including authentication metadata,
   * profile details, activation state, and soft-deletion tracking.
   *
   * @param tag Slick table tag.
   */
  private class UsersTable(tag: Tag) extends Table[User](tag, "users") {
    def userId      = column[Int]("user_id", O.PrimaryKey, O.AutoInc)
    def username    = column[String]("username")
    def passwordHash = column[String]("password_hash")
    def fullName    = column[String]("full_name")
    def email       = column[String]("email")
    def phone       = column[Option[String]]("phone")
    def isActive    = column[Boolean]("is_active")
    def isDeleted   = column[Boolean]("is_deleted")
    def createdAt   = column[Instant]("created_at")
    def updatedAt   = column[Instant]("updated_at")
    def deletedAt   = column[Option[Instant]]("deleted_at")

    /** Maps table columns to the [[User]] case class. */
    def * =
      (
        userId.?, username, passwordHash, fullName, email, phone,
        isActive, isDeleted, createdAt, updatedAt, deletedAt
      ) <> (User.tupled, User.unapply)
  }

  /** Slick query interface for the `users` table. */
  private val users = TableQuery[UsersTable]

  /**
   * Creates a new user record.
   *
   * @param u User instance.
   * @return  Future containing the newly generated user ID.
   */
  def create(u: User): Future[Int] =
    db.run(users returning users.map(_.userId) += u)

  /**
   * Retrieves a user by ID, only if the record is not soft-deleted.
   *
   * @param id User ID.
   * @return   Future optional containing the user.
   */
  def findByIdActive(id: Int): Future[Option[User]] =
    db.run(users.filter(u => u.userId === id && u.isDeleted === false).result.headOption)

  /**
   * Retrieves a user by ID, regardless of soft-deletion state.
   *
   * @param id User ID.
   * @return   Future optional containing the user.
   */
  def findByIdIncludeDeleted(id: Int): Future[Option[User]] =
    db.run(users.filter(_.userId === id).result.headOption)

  /**
   * Finds a user by username, but only if the account is active and not deleted.
   *
   * @param username Username to search for.
   * @return         Future optional containing the matching user.
   */
  def findByUsername(username: String): Future[Option[User]] =
    db.run(users.filter(u => u.username === username && u.isDeleted === false).result.headOption)

  /**
   * Lists all active (non-deleted) users.
   *
   * @return Future list of active users.
   */
  def listActive(): Future[Seq[User]] =
    db.run(users.filter(_.isDeleted === false).result)

  /**
   * Updates an existing user if not soft-deleted.
   *
   * @param id User ID.
   * @param u  Updated user data.
   * @return   Future number of affected rows.
   */
  def update(id: Int, u: User): Future[Int] =
    db.run(
      users
        .filter(u0 => u0.userId === id && u0.isDeleted === false)
        .update(u.copy(userId = Some(id)))
    )

  /**
   * Soft deletes a user by marking `isDeleted` and updating timestamps.
   *
   * @param id User ID.
   * @return   Future number of affected rows.
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
   * Restores a previously soft-deleted user.
   *
   * @param id User ID.
   * @return   Future number of affected rows.
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
