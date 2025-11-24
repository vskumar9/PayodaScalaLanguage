package services

import javax.inject._
import repositories.TasksRepository
import models.{Task, User}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/**
 * Service layer responsible for managing tasks and applying business logic
 * related to task creation, updates, deletion, and status transitions.
 *
 * This service acts as a higher-level abstraction over [[TasksRepository]],
 * adding important behaviors such as:
 *   - automatic timestamp handling (`createdAt`, `updatedAt`)
 *   - triggering assignment & reminder notifications when tasks are created
 *   - soft-delete and restore operations
 *   - safe status updates with validation that the task exists
 *
 * @param tasksRepo            repository for task persistence
 * @param notificationService  service used to generate task-related notifications
 * @param ec                   execution context for async operations
 */
@Singleton
class TaskService @Inject()(
                             tasksRepo: TasksRepository,
                             notificationService: NotificationService
                           )(implicit ec: ExecutionContext) {

  /**
   * Creates a new task and automatically schedules:
   *   - an assignment notification
   *   - reminder notifications (if applicable)
   *
   * The new task is created with:
   *   - `status = "assigned"`
   *   - current timestamps
   *   - deleted flags set to false
   *
   * @param eventId             event that owns this task
   * @param createdBy           ID of the user creating this task
   * @param title               short title of the task
   * @param description         optional description
   * @param assignedTeamId      optional assigned team
   * @param assignedToUserId    optional assigned user
   * @param priority            task priority (e.g., low/normal/high)
   * @param estimatedStart      optional estimated start timestamp
   * @param estimatedEnd        optional estimated end timestamp
   * @param specialRequirements optional instructions or metadata
   * @return Future[Int] ID of the newly created task
   */
  def createTask(eventId: Int,
                 createdBy: Int,
                 title: String,
                 description: Option[String],
                 assignedTeamId: Option[Int],
                 assignedToUserId: Option[Int],
                 priority: String,
                 estimatedStart: Option[Instant],
                 estimatedEnd: Option[Instant],
                 specialRequirements: Option[String]): Future[Int] = {

    val now = Instant.now()

    val task = Task(
      taskId = None,
      eventId = eventId,
      createdBy = createdBy,
      title = title,
      description = description,
      assignedTeamId = assignedTeamId,
      assignedToUserId = assignedToUserId,
      priority = priority,
      status = "assigned",
      estimatedStart = estimatedStart,
      estimatedEnd = estimatedEnd,
      actualStart = None,
      actualEnd = None,
      specialRequirements = specialRequirements,
      createdAt = now,
      updatedAt = now,
      isDeleted = false,
      deletedAt = None
    )

    for {
      taskId <- tasksRepo.create(task)
      _ <- notificationService.createAssignmentNotification(eventId, taskId, assignedTeamId, assignedToUserId, title, estimatedStart)
      _ <- notificationService.scheduleReminderNotifications(eventId, taskId, assignedTeamId, assignedToUserId, title, estimatedStart)
    } yield taskId
  }

  /**
   * Retrieves a task by ID, returning only active (non-deleted) tasks.
   *
   * @param id task ID
   * @return Future containing Some(task) if found, otherwise None
   */
  def getTask(id: Int): Future[Option[Task]] =
    tasksRepo.findByIdActive(id)

  /**
   * Lists all active tasks belonging to the specified event.
   *
   * @param eventId event identifier
   * @return Future sequence of active tasks
   */
  def listTasksForEvent(eventId: Int): Future[Seq[Task]] =
    tasksRepo.findByEventActive(eventId)

  /**
   * Updates an existing task by overwriting the stored record.
   *
   * The method ensures the correct `taskId` is set on the updated task.
   *
   * @param id      task ID
   * @param updated task object containing new values
   * @return number of affected rows (1 on success, 0 otherwise)
   */
  def updateTask(id: Int, updated: Task): Future[Int] = {
    val toSave = updated.copy(taskId = Some(id))
    tasksRepo.update(id, toSave)
  }

  /**
   * Soft-deletes a task, marking it as deleted without removing it from the database.
   *
   * Also updates the `deletedAt` timestamp.
   *
   * @param id task ID to delete
   * @return number of affected rows
   */
  def softDeleteTask(id: Int): Future[Int] =
    tasksRepo.softDelete(id)

  /**
   * Restores a previously soft-deleted task.
   *
   * @param id ID of the task to restore
   * @return number of affected rows
   */
  def restoreTask(id: Int): Future[Int] =
    tasksRepo.restore(id)

  /**
   * Updates the status of an existing active task.
   *
   * If the task does not exist or is deleted, returns 0.
   *
   * @param id     task ID
   * @param status new status value
   * @return number of affected rows (1 on success, 0 if task not found)
   */
  def updateTaskStatus(id: Int, status: String): Future[Int] = {
    tasksRepo.findByIdActive(id).flatMap {
      case Some(t) =>
        val updated = t.copy(status = status, updatedAt = Instant.now())
        tasksRepo.update(id, updated)
      case None =>
        Future.successful(0)
    }
  }

  def listTasksBetweenWithUserPaged(start: Instant, end: Instant, page: Int, pageSize: Int): Future[(Seq[(Task, User)], Int)] =
    tasksRepo.listBetweenWithUserPagedByEstimatedStart(start, end, page, pageSize)
}
