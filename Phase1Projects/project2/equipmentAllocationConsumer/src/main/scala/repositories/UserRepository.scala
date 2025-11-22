package repositories

import models.User
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository responsible for CRUD and lookup operations for [[models.User]] entities.
 *
 * This repository:
 *  - Exposes methods to create, read, update and soft-delete users.
 *  - Resolves user roles via join tables (`roles`, `user_roles`) and returns role names on the domain model.
 *  - Validates that roles referenced on create/update exist in the `roles` table (rejects unknown roles).
 *  - Uses Slick with a MySQL profile and Play's `DatabaseConfigProvider`.
 *
 * Important behaviors:
 *  - Soft-delete semantics: `delete` sets `isDeleted = true` and `deletedAt = now` (rows are not physically removed).
 *  - All read methods filter out soft-deleted rows by default.
 *  - `create` / `update` operations are executed transactionally to ensure user and role mappings remain consistent.
 *
 * Typical consumers:
 *  - Authentication / authorization services
 *  - Admin UI controllers
 *  - Actor systems that need to resolve admin user lists (e.g., reminders / maintenance)
 *
 * @param dbConfigProvider Play Slick database provider (injected)
 * @param ec               ExecutionContext used for async DB calls
 */
@Singleton
class UserRepository @Inject()(
                                protected val dbConfigProvider: DatabaseConfigProvider
                              )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile] {

  import profile.api._

  /**
   * Slick column mapping for java.time.Instant <-> java.sql.Timestamp.
   * This implicit allows Slick to read/write Instant fields directly.
   */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, java.sql.Timestamp](
      i  => java.sql.Timestamp.from(i),
      ts => ts.toInstant
    )

  /**
   * Internal DB row representation for `users` table.
   *
   * Not exposed outside the repository; converted to the domain [[User]] by [[toUser]].
   */
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

  /**
   * Slick table mapping for the `users` table.
   */
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

  /**
   * Internal DB row representation for `roles` table.
   * Minimal structure used to resolve role names.
   */
  private case class RoleRow(
                              roleId: Int,
                              roleName: String
                            )

  /**
   * Slick table mapping for `roles`.
   */
  private class RolesTable(tag: Tag) extends Table[RoleRow](tag, "roles") {
    def roleId   = column[Int]("role_id", O.PrimaryKey, O.AutoInc)
    def roleName = column[String]("role_name")

    def * = (roleId, roleName).mapTo[RoleRow]
  }

  /**
   * Internal DB row representation for `user_roles` join table.
   */
  private case class UserRoleRow(
                                  id: Int,
                                  userId: Int,
                                  roleId: Int
                                )

  /**
   * Slick table mapping for `user_roles` join table.
   *
   * Includes foreign keys to `users` and `roles` tables to enforce basic referential integrity.
   */
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

  /** Projection used for user insertion (excludes audit/soft-delete columns). */
  private val insertUserProjection =
    users.map(u => (u.username, u.passwordHash, u.fullName, u.email, u.phone, u.isActive))

  /**
   * Convert a DB user row and its role names into the public domain [[User]] model.
   *
   * @param row       DB row
   * @param roleNames List of role names assigned to the user
   * @return User domain model
   */
  private def toUser(row: UserRow, roleNames: List[String]): User =
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
   * Load role names for a set of user IDs.
   *
   * Returns a DBIO that yields a Map[userId -> List(roleName)].
   * When `ids` is empty, returns an empty map immediately.
   *
   * This helper executes a join between `user_roles` and `roles`.
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
   * Find a user by their primary key (excludes soft-deleted users).
   *
   * Loads the user's role names and attaches them to the domain model.
   *
   * @param id userId to fetch
   * @return Future optional User
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

    db.run(action)
  }

  /**
   * Find a user by username (excludes soft-deleted users).
   *
   * Loads the user's role names and attaches them to the domain model.
   *
   * @param username login username
   * @return Future optional User
   */
  def findByUsername(username: String): Future[Option[User]] = {
    val action = for {
      maybeRow <- users.filter(u => u.username === username && !u.isDeleted).result.headOption
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

    db.run(action)
  }

  /**
   * Fetch all non-deleted users with their role names.
   *
   * Useful for admin screens and bulk operations.
   *
   * @return Future sequence of User
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

    db.run(action)
  }

  /**
   * Create a new user and associate roles transactionally.
   *
   * Validation:
   *  - Ensures every role name in `user.roles` exists in the `roles` table. If any are missing,
   *    the returned Future fails with IllegalArgumentException and no row is inserted.
   *
   * Returns the generated `userId`.
   *
   * @param user domain model to create (role names must be valid)
   * @return Future generated userId
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

    db.run(txAction.transactionally)
  }

  /**
   * Update an existing user and replace their roles transactionally.
   *
   * Validation:
   *  - Ensures every role name in `user.roles` exists in the `roles` table.
   *  - If validation fails, the entire transaction is rolled back.
   *
   * Returns number of rows updated in the `users` table (0 if the user does not exist / is deleted).
   *
   * @param user domain model with userId set
   * @return Future number of affected rows
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

    db.run(txAction.transactionally)
  }

  /**
   * Soft-delete a user by setting `isDeleted = true` and `deletedAt = now`.
   *
   * Returns the number of rows updated (0 if the user was not found or already deleted).
   *
   * @param id userId to soft-delete
   * @return Future number of affected rows
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

    db.run(action.transactionally)
  }

}
