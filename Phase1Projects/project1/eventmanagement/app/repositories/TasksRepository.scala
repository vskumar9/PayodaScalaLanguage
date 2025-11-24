package repositories

import javax.inject._
import models.{Task, User}
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

@Singleton
class TasksRepository @Inject()(
                                 dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                               )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  private class TasksTable(tag: Tag) extends Table[Task](tag, "tasks") {
    def taskId             = column[Int]("task_id", O.PrimaryKey, O.AutoInc)
    def eventId            = column[Int]("event_id")
    def createdBy          = column[Int]("created_by")
    def title              = column[String]("title")
    def description        = column[Option[String]]("description")
    def assignedTeamId     = column[Option[Int]]("assigned_team_id")
    def assignedToUserId   = column[Option[Int]]("assigned_to_user_id")
    def priority           = column[String]("priority")
    def status             = column[String]("status")
    def estimatedStart     = column[Option[Instant]]("estimated_start")
    def estimatedEnd       = column[Option[Instant]]("estimated_end")
    def actualStart        = column[Option[Instant]]("actual_start")
    def actualEnd          = column[Option[Instant]]("actual_end")
    def specialRequirements= column[Option[String]]("special_requirements")
    def createdAt          = column[Instant]("created_at")
    def updatedAt          = column[Instant]("updated_at")
    def isDeleted          = column[Boolean]("is_deleted")
    def deletedAt          = column[Option[Instant]]("deleted_at")

    def * =
      (
        taskId.?, eventId, createdBy, title, description, assignedTeamId, assignedToUserId,
        priority, status, estimatedStart, estimatedEnd, actualStart, actualEnd, specialRequirements,
        createdAt, updatedAt, isDeleted, deletedAt
      ) <> (Task.tupled, Task.unapply)
  }

  private val tasks = TableQuery[TasksTable]

  // Minimal users mapping used for join. Adjust column names if your users table differs.
  private class UsersTable(tag: Tag) extends Table[User](tag, "users") {
    def userId       = column[Int]("user_id", O.PrimaryKey, O.AutoInc)
    def username     = column[String]("username")
    def passwordHash = column[String]("password_hash")
    def fullName     = column[String]("full_name")
    def email        = column[String]("email")
    def phone        = column[Option[String]]("phone")
    def isActive     = column[Boolean]("is_active")
    def isDeleted    = column[Boolean]("is_deleted")
    def createdAt    = column[Instant]("created_at")
    def updatedAt    = column[Instant]("updated_at")
    def deletedAt    = column[Option[Instant]]("deleted_at")

    def * =
      (userId.?, username, passwordHash, fullName, email, phone, isActive, isDeleted, createdAt, updatedAt, deletedAt) <>
        (User.tupled, User.unapply)
  }

  private val users = TableQuery[UsersTable]

  // --- existing CRUD methods (create, findByIdActive, findByEventActive, update, softDelete, restore etc.) ---
  def create(task: Task): Future[Int] =
    db.run(tasks returning tasks.map(_.taskId) += task)

  def findByIdActive(id: Int): Future[Option[Task]] =
    db.run(tasks.filter(t => t.taskId === id && t.isDeleted === false).result.headOption)

  def findByEventActive(eventId: Int): Future[Seq[Task]] =
    db.run(tasks.filter(t => t.eventId === eventId && t.isDeleted === false).result)

  def update(id: Int, task: Task): Future[Int] =
    db.run(
      tasks
        .filter(t => t.taskId === id && t.isDeleted === false)
        .update(task.copy(taskId = Some(id)))
    )

  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      tasks
        .filter(t => t.taskId === id && t.isDeleted === false)
        .map(t => (t.isDeleted, t.deletedAt, t.updatedAt))
        .update((true, Some(now), now))
    )
  }

  def restore(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      tasks
        .filter(t => t.taskId === id && t.isDeleted === true)
        .map(t => (t.isDeleted, t.deletedAt, t.updatedAt))
        .update((false, None, now))
    )
  }

  def listActive(): Future[Seq[Task]] =
    db.run(tasks.filter(_.isDeleted === false).result)

  def findStartingBetween(from: Instant, to: Instant): Future[Seq[Task]] = {
    val fromLit = LiteralColumn(from)
    val toLit   = LiteralColumn(to)

    db.run(
      tasks.filter { t =>
        (t.isDeleted === false) &&
          (t.estimatedStart.isDefined) &&
          (t.status =!= "Completed") &&
          (t.estimatedStart >= fromLit) &&
          (t.estimatedStart <= toLit)
      }.result
    )
  }

  def findOverdue(now: Instant): Future[Seq[Task]] = {
    val nowLit = LiteralColumn(now)

    db.run(
      tasks.filter { t =>
        (t.isDeleted === false) &&
          (t.estimatedEnd.isDefined) &&
          (t.status =!= "Completed") &&
          (t.estimatedEnd < nowLit)
      }.result
    )
  }

  /**
   * New method: paginated list of (Task, User) for tasks whose estimatedStart is between start and end (inclusive).
   *
   * Returns Future[(Seq[(Task, User)], Int)] where Int is the total count of tasks matching the event filters
   * (time window and non-deleted). If you want total to count only rows that have a non-deleted user, change the countAction accordingly.
   */
  def listBetweenWithUserPagedByEstimatedStart(start: Instant, end: Instant, page: Int, pageSize: Int): Future[(Seq[(Task, User)], Int)] = {
    val safePage     = Math.max(page, 1)
    val safePageSize = Math.max(1, pageSize)
    val offset       = (safePage - 1) * safePageSize

    val baseFiltered = tasks.filter(t =>
      t.isDeleted === false &&
        t.estimatedStart.isDefined &&
        t.estimatedStart >= start &&
        t.estimatedStart <= end
    )

    val countAction = baseFiltered.length.result

    val pagedAction = baseFiltered
      .join(users).on(_.createdBy === _.userId)
      .filter { case (_, u) => u.isDeleted === false } // only include non-deleted users
      .sortBy { case (t, _) => t.estimatedStart.asc.nullsLast } // tasks with estimatedStart first; nulls last
      .drop(offset)
      .take(safePageSize)
      .result

    // compose DBIO and run, then convert table-element types -> your case classes if necessary
    val composed = for {
      total <- countAction
      rows  <- pagedAction
    } yield (rows, total)

    db.run(composed.transactionally).map { case (rows, total) =>
      // rows are typed as table-element types; they should already be mapped to Task and User by your projections.
      // If the compiler complains about types, you can replace the map below with `.map { case (t, u) => (t.asInstanceOf[Task], u.asInstanceOf[User]) }`.
      val converted: Seq[(Task, User)] = rows.map { case (tRow, uRow) =>
        (tRow: Task, uRow: User)
      }
      (converted, total)
    }
  }
}
