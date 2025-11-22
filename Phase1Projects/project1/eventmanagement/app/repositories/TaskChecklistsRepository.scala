package repositories

import javax.inject._
import models.TaskChecklistItem
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

/**
 * Repository for managing task checklist items stored in the `task_checklists` table.
 *
 * A [[models.TaskChecklistItem]] represents a single actionable step within a task's
 * checklist. This repository supports:
 *
 *   - adding new checklist items
 *   - retrieving active (non-deleted) checklist items for a task
 *   - soft-deleting checklist items
 *   - restoring previously deleted checklist items
 *
 * Soft-delete behavior is implemented via `is_deleted` and `deleted_at` fields.
 *
 * @param dbConfigProvider Slick database configuration provider
 * @param ec               execution context for asynchronous operations
 */
@Singleton
class TaskChecklistsRepository @Inject()(
                                          dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                        )(implicit ec: ExecutionContext) extends SlickMapping {

  /** Slick database configuration. */
  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Column mapper for java.time.Instant. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `task_checklists` table.
   */
  private class ChecklistTable(tag: Tag) extends Table[TaskChecklistItem](tag, "task_checklists") {
    def checkId    = column[Int]("check_id", O.PrimaryKey, O.AutoInc)
    def taskId     = column[Int]("task_id")
    def description= column[String]("description")
    def isDone     = column[Boolean]("is_done")
    def doneBy     = column[Option[Int]]("done_by")
    def doneAt     = column[Option[Instant]]("done_at")
    def createdAt  = column[Instant]("created_at")
    def isDeleted  = column[Boolean]("is_deleted")
    def deletedAt  = column[Option[Instant]]("deleted_at")

    /** Mapping to/from the TaskChecklistItem case class. */
    def * =
      (checkId.?, taskId, description, isDone, doneBy, doneAt, createdAt, isDeleted, deletedAt)
        .<> (TaskChecklistItem.tupled, TaskChecklistItem.unapply)
  }

  /** Table query for performing operations on the checklist table. */
  private val checklist = TableQuery[ChecklistTable]

  /**
   * Inserts a new checklist item.
   *
   * @param item checklist item to insert
   * @return generated `check_id`
   */
  def addItem(item: TaskChecklistItem): Future[Int] =
    db.run(checklist returning checklist.map(_.checkId) += item)

  /**
   * Retrieves all non-deleted checklist items associated with a task.
   *
   * @param taskIdVal task identifier
   * @return sequence of checklist items
   */
  def getByTaskActive(taskIdVal: Int): Future[Seq[TaskChecklistItem]] =
    db.run(checklist.filter(c => c.taskId === taskIdVal && c.isDeleted === false).result)

  /**
   * Soft-deletes a checklist item.
   *
   * Sets:
   *   - `is_deleted = true`
   *   - `deleted_at = now`
   *
   * @param id checklist item ID
   * @return number of affected rows
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    db.run(
      checklist
        .filter(c => c.checkId === id && c.isDeleted === false)
        .map(c => (c.isDeleted, c.deletedAt))
        .update((true, Some(now)))
    )
  }

  /**
   * Restores a previously soft-deleted checklist item.
   *
   * Sets:
   *   - `is_deleted = false`
   *   - `deleted_at = None`
   *
   * @param id checklist item ID
   * @return number of affected rows
   */
  def restore(id: Int): Future[Int] =
    db.run(
      checklist
        .filter(c => c.checkId === id && c.isDeleted === true)
        .map(c => (c.isDeleted, c.deletedAt))
        .update((false, None))
    )
}
