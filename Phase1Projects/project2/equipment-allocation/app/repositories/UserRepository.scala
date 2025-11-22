package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging

import models.User
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.time.Instant

/**
 * Repository for user CRUD operations and role management.
 *
 * Responsibilities:
 *  - map between DB rows and domain model [[models.User]]
 *  - provide transactional create/update operations that maintain user_roles
 *  - support lookups by id and username and listing users with their roles
 *
 * Note: this repository intentionally blanks out the passwordHash in the
 * returned User objects from non-authentication queries (see `toUser`).
 *
 * All public methods attach `.recover` handlers that log unexpected database
 * errors and re-throw them so higher layers (controllers/services) can decide
 * how to convert failures into HTTP responses.
 *
 * @param dbConfigProvider Play's DatabaseConfigProvider
 * @param ec ExecutionContext for DB operations
 */
@Singleton
class UserRepository @Inject()(
                                protected val dbConfigProvider: DatabaseConfigProvider
                              )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile]
    with Logging {

  import profile.api._

  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, java.sql.Timestamp](
      i  => java.sql.Timestamp.from(i),
      ts => ts.toInstant
    )

  private case class UserRow(
                              userId: Int,
                              username: String,
                              passwordHash: String,
                              fullName: String,
                              email: String,
                              phone: Option[String],
                              isActive: Boolean,
                              createdAt: Instant,
                              updatedAt: Instant,
                              isDeleted: Boolean,
                              deletedAt: Option[Instant]
                            )

  private class UsersTable(tag: Tag) extends Table[UserRow](tag, "users") {
    def userId       = column[Int]("user_id", O.PrimaryKey, O.AutoInc)
    def username     = column[String]("username")
    def passwordHash = column[String]("password_hash")
    def fullName     = column[String]("full_name")
    def email        = column[String]("email")
    def phone        = column[Option[String]]("phone")
    def isActive     = column[Boolean]("is_active")
    def createdAt    = column[Instant]("created_at")
    def updatedAt    = column[Instant]("updated_at")
    def isDeleted    = column[Boolean]("is_deleted")
    def deletedAt    = column[Option[Instant]]("deleted_at")

    def * =
      (userId, username, passwordHash, fullName, email, phone,
        isActive, createdAt, updatedAt, isDeleted, deletedAt)
        .mapTo[UserRow]
  }

  private case class RoleRow(
                              roleId: Int,
                              roleName: String
                            )

  private class RolesTable(tag: Tag) extends Table[RoleRow](tag, "roles") {
    def roleId   = column[Int]("role_id", O.PrimaryKey, O.AutoInc)
    def roleName = column[String]("role_name")

    def * = (roleId, roleName).mapTo[RoleRow]
  }

  private case class UserRoleRow(
                                  id: Int,
                                  userId: Int,
                                  roleId: Int
                                )

  private class UserRolesTable(tag: Tag) extends Table[UserRoleRow](tag, "user_roles") {
    def id     = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def userId = column[Int]("user_id")
    def roleId = column[Int]("role_id")

    def userFk =
      foreignKey("fk_user_roles_user", userId, users)(_.userId, onDelete = ForeignKeyAction.Cascade)

    def roleFk =
      foreignKey("fk_user_roles_role", roleId, roles)(_.roleId, onDelete = ForeignKeyAction.Restrict)

    def * = (id, userId, roleId).mapTo[UserRoleRow]
  }

  private val users     = TableQuery[UsersTable]
  private val roles     = TableQuery[RolesTable]
  private val userRoles = TableQuery[UserRolesTable]

  private val insertUserProjection =
    users.map(u => (u.username, u.passwordHash, u.fullName, u.email, u.phone, u.isActive))

  /**
   * Convert DB row -> domain User.
   * IMPORTANT: do NOT expose passwordHash — we blank it here.
   *
   * @param row DB row
   * @param roleNames list of role names for the user
   */
  private def toUser(row: UserRow, roleNames: List[String]): User =
    User(
      userId       = row.userId,
      username     = row.username,
      passwordHash = "",
      fullName     = row.fullName,
      email        = row.email,
      phone        = row.phone,
      isActive     = row.isActive,
      createdAt    = row.createdAt,
      updatedAt    = row.updatedAt,
      roles        = roleNames,
      isDeleted    = row.isDeleted,
      deletedAt    = row.deletedAt
    )

  /**
   * Convert DB row -> domain User for authentication (includes passwordHash).
   *
   * @param row DB row
   * @param roleNames list of role names for the user
   */
  private def toUserLogin(row: UserRow, roleNames: List[String]): User =
    User(
      userId       = row.userId,
      username     = row.username,
      passwordHash = row.passwordHash,
      fullName     = row.fullName,
      email        = row.email,
      phone        = row.phone,
      isActive     = row.isActive,
      createdAt    = row.createdAt,
      updatedAt    = row.updatedAt,
      roles        = roleNames,
      isDeleted    = row.isDeleted,
      deletedAt    = row.deletedAt
    )

  /**
   * Load role names grouped by user id for the given user ids.
   *
   * @param ids sequence of user ids
   * @return DBIO map userId -> list of role names
   */
  private def loadRoleNamesByUserIds(ids: Seq[Int]): DBIO[Map[Int, List[String]]] = {
    if (ids.isEmpty) DBIO.successful(Map.empty)
    else {
      val q = for {
        ur <- userRoles if ur.userId inSet ids
        r  <- roles if r.roleId === ur.roleId
      } yield (ur.userId, r.roleName)

      q.result.map { rows =>
        rows.groupMap(_._1)(_._2).view.mapValues(_.toList).toMap
      }
    }
  }

  /**
   * Find a user by its id (non-deleted).
   *
   * @param id user id
   * @return Future[Option[User]] (passwordHash blanked)
   */
  def findById(id: Int): Future[Option[User]] = {
    val action = for {
      maybeRow <- users.filter(u => u.userId === id && !u.isDeleted).result.headOption
      result <- maybeRow match {
        case Some(row) =>
          loadRoleNamesByUserIds(Seq(row.userId)).map { roleMap =>
            val names = roleMap.getOrElse(row.userId, Nil)
            Some(toUser(row, names))
          }
        case None =>
          DBIO.successful(None)
      }
    } yield result

    db.run(action).recover { case NonFatal(ex) =>
      logger.error(s"Error in findById(userId=$id)", ex)
      throw ex
    }
  }

  /**
   * Find a user by username (non-deleted).
   * Returns the passwordHash (for authentication flows).
   *
   * @param username username
   * @return Future[Option[User]] (includes passwordHash)
   */
  def findByUsername(username: String): Future[Option[User]] = {
    val action = for {
      maybeRow <- users.filter(u => u.username === username && !u.isDeleted).result.headOption
      result <- maybeRow match {
        case Some(row) =>
          loadRoleNamesByUserIds(Seq(row.userId)).map { roleMap =>
            val names = roleMap.getOrElse(row.userId, Nil)
            Some(toUserLogin(row, names))
          }
        case None =>
          DBIO.successful(None)
      }
    } yield result

    db.run(action).recover { case NonFatal(ex) =>
      logger.error(s"Error in findByUsername(username=$username)", ex)
      throw ex
    }
  }

  /**
   * List all users (non-deleted) with their roles. Passwords are blanked.
   *
   * @return Future[Seq[User]]
   */
  def findAll(): Future[Seq[User]] = {
    val action = for {
      rows      <- users.filter(!_.isDeleted).result
      roleNames <- loadRoleNamesByUserIds(rows.map(_.userId))
    } yield {
      rows.map { r =>
        val names = roleNames.getOrElse(r.userId, Nil)
        toUser(r, names)
      }
    }

    db.run(action).recover { case NonFatal(ex) =>
      logger.error("Error in findAll()", ex)
      throw ex
    }
  }

  /**
   * Create a new user and attach roles transactionally.
   *
   * Validates that all requested roles exist; if not, the transaction fails.
   *
   * @param user User model (userId ignored)
   * @return Future[Int] generated userId
   */
  def create(user: User): Future[Int] = {
    val txAction: DBIO[Int] = for {
      roleRows <- roles.filter(_.roleName inSet user.roles).result
      _ <- {
        val foundNames = roleRows.map(_.roleName).toSet
        val missing    = user.roles.filterNot(foundNames.contains)
        if (missing.nonEmpty)
          DBIO.failed(new IllegalArgumentException(s"Unknown roles: ${missing.mkString(", ")}"))
        else DBIO.successful(())
      }

      newUserId <- (insertUserProjection returning users.map(_.userId)) +=
        (user.username, user.passwordHash, user.fullName, user.email, user.phone, user.isActive)

      _ <- userRoles ++= roleRows.map(r => UserRoleRow(0, newUserId, r.roleId))
    } yield newUserId

    db.run(txAction.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error creating user username=${user.username}", ex)
      throw ex
    }
  }

  /**
   * Update an existing user and replace their roles transactionally.
   *
   * Validates that provided roles exist.
   *
   * @param user User model (userId must be set)
   * @return Future[Int] number of user rows updated (1 if success, 0 if not found)
   */
  def update(user: User): Future[Int] = {
    val txAction: DBIO[Int] = for {
      roleRows <- roles.filter(_.roleName inSet user.roles).result
      _ <- {
        val foundNames = roleRows.map(_.roleName).toSet
        val missing    = user.roles.filterNot(foundNames.contains)
        if (missing.nonEmpty)
          DBIO.failed(new IllegalArgumentException(s"Unknown roles: ${missing.mkString(", ")}"))
        else DBIO.successful(())
      }

      rows <- users
        .filter(u => u.userId === user.userId && !u.isDeleted)
        .map(u => (u.username, u.passwordHash, u.fullName, u.email, u.phone, u.isActive))
        .update((user.username, user.passwordHash, user.fullName, user.email, user.phone, user.isActive))

      _ <- userRoles.filter(_.userId === user.userId).delete
      _ <- userRoles ++= roleRows.map(r => UserRoleRow(0, user.userId, r.roleId))
    } yield rows

    db.run(txAction.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error updating user userId=${user.userId}", ex)
      throw ex
    }
  }

  /**
   * Soft-delete a user (sets isDeleted = true and deletedAt).
   *
   * @param id user id
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
   */
  def delete(id: Int): Future[Int] = {
    val now = Instant.now()

    val action = for {
      maybeRow <- users.filter(u => u.userId === id && u.isDeleted === false).result.headOption
      res <- maybeRow match {
        case Some(row) =>
          val updated = row.copy(isDeleted = true, deletedAt = Some(now), updatedAt = now)
          users.filter(_.userId === id).update(updated)
        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error deleting user userId=$id", ex)
      throw ex
    }
  }

}
