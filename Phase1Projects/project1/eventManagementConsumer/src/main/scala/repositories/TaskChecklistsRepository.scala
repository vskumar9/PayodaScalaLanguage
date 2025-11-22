package repositories

import models.TaskChecklistItem
import slick.jdbc.MySQLProfile
import utils.SlickMapping
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository responsible for performing database operations on
 * [[TaskChecklistItem]] entities.
 *
 * Checklist items represent granular steps associated with a task and may
 * be marked done, soft-deleted, or restored. This repository provides
 * operations for creating checklist items, querying active items, and
 * updating their lifecycle state.
 *
 * @param dbConfigProvider Injected Slick database configuration provider.
 * @param ec               Execution context for asynchronous database operations.
 */
@Singleton
class TaskChecklistsRepository @Inject()(
                                          dbConfigProvider: play.api.db.slick.DatabaseConfigProvider
                                        )(implicit ec: ExecutionContext) extends SlickMapping {

  private val dbConfig = dbConfigProvider.get[MySQLProfile]
  import dbConfig._
  import profile.api._

  /** Implicit Slick column mapping for Java [[Instant]] timestamps. */
  private[this] implicit val instantMapped: BaseColumnType[Instant] =
    instantColumnType.asInstanceOf[BaseColumnType[Instant]]

  /**
   * Slick table mapping for the `task_checklists` table.
   *
   * Defines all table columns and the mapping to the [[TaskChecklistItem]] case class.
   *
   * @param tag Slick table tag.
   */
  private class ChecklistTable(tag: Tag) extends Table[TaskChecklistItem](tag, "task_checklists") {
    def checkId    = column[Int]("check_id", O.PrimaryKey, O.AutoInc)
    def taskId     = column[Int]("task_id")
    def description = column[String]("description")
    def isDone     = column[Boolean]("is_done")
    def doneBy     = column[Option[Int]]("done_by")
    def doneAt     = column[Option[Instant]]("done_at")
    def createdAt  = column[Instant]("created_at")
    def isDeleted  = column[Boolean]("is_deleted")
    def deletedAt  = column[Option[Instant]]("deleted_at")

    /** Projection mapping table columns to the [[TaskChecklistItem]] model. */
    def * =
      (
        checkId.?, taskId, description, isDone, doneBy,
        doneAt, createdAt, isDeleted, deletedAt
      ) <> (TaskChecklistItem.tupled, TaskChecklistItem.unapply)
  }

  /** Slick query object for the `task_checklists` table. */
  private val checklist = TableQuery[ChecklistTable]

  /**
   * Inserts a new checklist item into the database.
   *
   * @param item Checklist item to add.
   * @return     Future containing the generated primary key (checkId).
   */
  def addItem(item: TaskChecklistItem): Future[Int] =
    db.run(checklist returning checklist.map(_.checkId) += item)

  /**
   * Retrieves all active (non-deleted) checklist items for a given task.
   *
   * @param taskIdVal ID of the task whose checklist items are requested.
   * @return          Future containing a sequence of active [[TaskChecklistItem]] entries.
   */
  def getByTaskActive(taskIdVal: Int): Future[Seq[TaskChecklistItem]] =
    db.run(checklist.filter(c => c.taskId === taskIdVal && c.isDeleted === false).result)

  /**
   * Soft deletes a checklist item by marking its `isDeleted` flag and setting `deletedAt`.
   *
   * @param id Checklist item ID.
   * @return   Future containing number of affected rows (0 or 1).
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
   * @param id Checklist item ID.
   * @return   Future containing number of affected rows (0 or 1).
   */
  def restore(id: Int): Future[Int] =
    db.run(
      checklist
        .filter(c => c.checkId === id && c.isDeleted === true)
        .map(c => (c.isDeleted, c.deletedAt))
        .update((false, None))
    )
}
