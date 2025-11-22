package repositories

import javax.inject._
import models.Task
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * Repository responsible for managing tasks stored in the `tasks` table.
 *
 * A [[models.Task]] represents a unit of work within an event, including
 * scheduling, assignment, and lifecycle status. This repository provides:
 *
 *   - creation of tasks
 *   - retrieval of active (non-deleted) tasks
 *   - listing tasks by event
 *   - updating existing tasks
 *   - soft-deleting and restoring tasks
 *   - querying for scheduled/overdue tasks
 *
 * Soft-delete behavior is implemented using the `is_deleted` and `deleted_at` columns.
 *
 * @param dbConfigProvider Slick database configuration provider
 * @param ec               execution context for asynchronous DB operations
 */
@Singleton
class TasksRepository @Inject()(
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
   * Slick table mapping for the `tasks` table.
   */
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

    /** Projection to/from the Task case class. */
    def * =
      (
        taskId.?, eventId, createdBy, title, description, assignedTeamId, assignedToUserId,
        priority, status, estimatedStart, estimatedEnd, actualStart, actualEnd, specialRequirements,
        createdAt, updatedAt, isDeleted, deletedAt
      ) <> (Task.tupled, Task.unapply)
  }

  /** Table query entry point for the `tasks` table. */
  private val tasks = TableQuery[TasksTable]

  /**
   * Inserts a new task into the database.
   *
   * @param task task to insert
   * @return generated task ID
   */
  def create(task: Task): Future[Int] =
    db.run(tasks returning tasks.map(_.taskId) += task)

  /**
   * Retrieves an active (non-deleted) task by ID.
   *
   * @param id task identifier
   * @return Some(Task) if found and active, otherwise None
   */
  def findByIdActive(id: Int): Future[Option[Task]] =
    db.run(tasks.filter(t => t.taskId === id && t.isDeleted === false).result.headOption)

  /**
   * Retrieves all active tasks belonging to a specific event.
   *
   * @param eventId event identifier
   * @return sequence of active tasks for the event
   */
  def findByEventActive(eventId: Int): Future[Seq[Task]] =
    db.run(tasks.filter(t => t.eventId === eventId && t.isDeleted === false).result)

  /**
   * Updates an existing active task.
   *
   * @param id   task identifier
   * @param task updated task data
   * @return number of affected rows
   */
  def update(id: Int, task: Task): Future[Int] =
    db.run(
      tasks
        .filter(t => t.taskId === id && t.isDeleted === false)
        .update(task.copy(taskId = Some(id)))
    )

  /**
   * Soft-deletes a task.
   *
   * Sets:
   *   - `is_deleted = true`
   *   - `deleted_at = now`
   *   - `updated_at = now`
   *
   * @param id task identifier
   * @return number of affected rows
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      tasks
        .filter(t => t.taskId === id && t.isDeleted === false)
        .map(t => (t.isDeleted, t.deletedAt, t.updatedAt))
        .update((true, Some(now), now))
    )
  }

  /**
   * Restores a previously soft-deleted task.
   *
   * Sets:
   *   - `is_deleted = false`
   *   - `deleted_at = None`
   *   - `updated_at = now`
   *
   * @param id task identifier
   * @return number of affected rows
   */
  def restore(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      tasks
        .filter(t => t.taskId === id && t.isDeleted === true)
        .map(t => (t.isDeleted, t.deletedAt, t.updatedAt))
        .update((false, None, now))
    )
  }

  /**
   * Lists all active (non-deleted) tasks.
   *
   * @return sequence of active tasks
   */
  def listActive(): Future[Seq[Task]] =
    db.run(tasks.filter(_.isDeleted === false).result)

  /**
   * Finds tasks that are scheduled to start within a given time range.
   *
   * A task is considered within the range if:
   *   - estimatedStart is defined
   *   - status is not "Completed"
   *   - estimatedStart falls between `from` and `to`
   *
   * @param from start of the time window
   * @param to   end of the time window
   * @return sequence of tasks starting in the specified range
   */
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

  /**
   * Finds tasks that are overdue.
   *
   * A task is overdue if:
   *   - estimatedEnd is defined
   *   - status is not "Completed"
   *   - estimatedEnd < now
   *
   * @param now current timestamp
   * @return sequence of overdue tasks
   */
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
}
