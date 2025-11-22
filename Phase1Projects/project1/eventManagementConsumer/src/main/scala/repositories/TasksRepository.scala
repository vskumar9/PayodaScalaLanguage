package repositories

import models.Task
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository responsible for CRUD operations and lifecycle management
 * of [[Task]] entities.
 *
 * This repository exposes methods for creating tasks, retrieving active tasks,
 * updating task metadata, soft-deleting/restoring tasks, and querying tasks
 * based on scheduling criteria such as overdue tasks or tasks starting within
 * a specific time window.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context for asynchronous database access.
 */
@Singleton
class TasksRepository @Inject()(
                                 dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                               )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit Slick column mapping for Java [[Instant]]. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping representing the `tasks` table.
   *
   * Maps all task-related fields, including assignment data, scheduling timestamps,
   * lifecycle state, and soft deletion metadata.
   *
   * @param tag Slick table tag.
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
    def specialRequirements = column[Option[String]]("special_requirements")
    def createdAt          = column[Instant]("created_at")
    def updatedAt          = column[Instant]("updated_at")
    def isDeleted          = column[Boolean]("is_deleted")
    def deletedAt          = column[Option[Instant]]("deleted_at")

    /** Mapping of SQL columns to the [[Task]] model. */
    def * =
      (
        taskId.?, eventId, createdBy, title, description,
        assignedTeamId, assignedToUserId, priority, status,
        estimatedStart, estimatedEnd, actualStart, actualEnd,
        specialRequirements, createdAt, updatedAt, isDeleted, deletedAt
      ) <> (Task.tupled, Task.unapply)
  }

  /** Slick query interface for the `tasks` table. */
  private val tasks = TableQuery[TasksTable]

  /**
   * Inserts a new task into the database.
   *
   * @param task Task to create.
   * @return     Future containing the generated task ID.
   */
  def create(task: Task): Future[Int] =
    db.run(tasks returning tasks.map(_.taskId) += task)

  /**
   * Retrieves a task by ID, but only if not soft-deleted.
   *
   * @param id Task ID.
   * @return   Future optional containing the task if found.
   */
  def findByIdActive(id: Int): Future[Option[Task]] =
    db.run(tasks.filter(t => t.taskId === id && t.isDeleted === false).result.headOption)

  /**
   * Retrieves all active (non-deleted) tasks for a given event.
   *
   * @param eventId Event ID.
   * @return        Future list of active tasks.
   */
  def findByEventActive(eventId: Int): Future[Seq[Task]] =
    db.run(tasks.filter(t => t.eventId === eventId && t.isDeleted === false).result)

  /**
   * Updates an existing task if it is not soft-deleted.
   *
   * @param id   Task ID.
   * @param task Updated task data.
   * @return     Future number of affected rows.
   */
  def update(id: Int, task: Task): Future[Int] =
    db.run(
      tasks
        .filter(t => t.taskId === id && t.isDeleted === false)
        .update(task.copy(taskId = Some(id)))
    )

  /**
   * Soft deletes a task by marking it deleted and updating timestamps.
   *
   * @param id Task ID.
   * @return   Future number of affected rows.
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
   * @param id Task ID.
   * @return   Future number of affected rows.
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
   * Retrieves all active (non-deleted) tasks.
   *
   * @return Future list of active tasks.
   */
  def listActive(): Future[Seq[Task]] =
    db.run(tasks.filter(_.isDeleted === false).result)

  /**
   * Returns tasks with estimated start times inside a specific time window.
   *
   * Excludes completed tasks.
   *
   * @param from Start of the time window.
   * @param to   End of the time window.
   * @return     Future sequence of matching tasks.
   */
  def findStartingBetween(from: Instant, to: Instant): Future[Seq[Task]] = {
    val fromLit = LiteralColumn(from)
    val toLit = LiteralColumn(to)

    db.run(
      tasks.filter { t =>
        (t.isDeleted === false) &&
          t.estimatedStart.isDefined &&
          (t.status =!= "Completed") &&
          (t.estimatedStart >= fromLit) &&
          (t.estimatedStart <= toLit)
      }.result
    )
  }

  /**
   * Returns tasks that are overdue based on their estimated end time.
   *
   * A task is considered overdue when:
   *   - it is not deleted
   *   - it has an estimated end timestamp
   *   - it is not completed
   *   - its estimated end time is earlier than the provided `now`
   *
   * @param now Current timestamp.
   * @return    Future sequence of overdue tasks.
   */
  def findOverdue(now: Instant): Future[Seq[Task]] = {
    val nowLit = LiteralColumn(now)

    db.run(
      tasks.filter { t =>
        (t.isDeleted === false) &&
          t.estimatedEnd.isDefined &&
          (t.status =!= "Completed") &&
          (t.estimatedEnd < nowLit)
      }.result
    )
  }
}
