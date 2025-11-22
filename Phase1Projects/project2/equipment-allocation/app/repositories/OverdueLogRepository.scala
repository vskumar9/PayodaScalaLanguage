package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging

import models.OverdueLog
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for CRUD operations on `overdue_logs` table using Slick.
 *
 * Responsibilities:
 *  - insert overdue reminder log entries
 *  - query overdue logs (all or by allocation)
 *
 * All public methods attach `.recover` handlers which log unexpected database
 * errors and re-throw them so higher layers (services/controllers) can decide
 * how to convert failures into HTTP responses or retries.
 *
 * @param dbConfigProvider Play's DatabaseConfigProvider
 * @param ec ExecutionContext for DB operations
 */
@Singleton
class OverdueLogRepository @Inject()(
                                      protected val dbConfigProvider: DatabaseConfigProvider
                                    )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile]
    with Logging {

  import profile.api._

  /** Slick mapping for java.time.Instant <-> java.sql.Timestamp. */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /**
   * Internal row representation for the `overdue_logs` table.
   *
   * Mirrors DB schema and is used for Slick mapping.
   */
  private case class OverdueRow(
                                 overdueId: Int,
                                 allocationId: Int,
                                 notifiedAt: Instant,
                                 reminderType: String,
                                 recipientEmployeeId: Int,
                                 channel: String
                               )

  /**
   * Slick table mapping for overdue_logs.
   */
  private class OverdueLogsTable(tag: Tag) extends Table[OverdueRow](tag, "overdue_logs") {
    def overdueId           = column[Int]("overdue_id", O.PrimaryKey, O.AutoInc)
    def allocationId        = column[Int]("allocation_id")
    def notifiedAt          = column[Instant]("notified_at")
    def reminderType        = column[String]("reminder_type")
    def recipientEmployeeId = column[Int]("recipient_employee_id")
    def channel             = column[String]("channel")

    def * =
      (overdueId, allocationId, notifiedAt, reminderType, recipientEmployeeId, channel)
        .mapTo[OverdueRow]
  }

  private val overdueLogs = TableQuery[OverdueLogsTable]

  /** Convert DB row to domain model. */
  private def toModel(r: OverdueRow): OverdueLog =
    OverdueLog(
      overdueId           = r.overdueId,
      allocationId        = r.allocationId,
      notifiedAt          = r.notifiedAt,
      reminderType        = r.reminderType,
      recipientEmployeeId = r.recipientEmployeeId,
      channel             = r.channel
    )

  /**
   * Insert a new overdue log entry and return the generated overdueId.
   *
   * The repository uses the `notifiedAt` value from the provided model; if you
   * want DB-generated timestamps, set `notifiedAt` before calling this method.
   *
   * @param log OverdueLog (overdueId is ignored)
   * @return Future[Int] generated overdueId
   */
  def create(log: OverdueLog): Future[Int] = {
    val now = log.notifiedAt
    val row = OverdueRow(
      overdueId = 0, // DB will generate
      allocationId = log.allocationId,
      notifiedAt = now,
      reminderType = log.reminderType,
      recipientEmployeeId = log.recipientEmployeeId,
      channel = log.channel
    )

    db.run((overdueLogs returning overdueLogs.map(_.overdueId)) += row).recover { case NonFatal(ex) =>
      logger.error(s"Error creating overdue log for allocationId=${log.allocationId}", ex)
      throw ex
    }
  }

  /**
   * Retrieve all overdue log entries.
   *
   * @return Future[Seq[OverdueLog]]
   */
  def findAll(): Future[Seq[OverdueLog]] =
    db.run(overdueLogs.result)
      .map(_.map(toModel))
      .recover { case NonFatal(ex) =>
        logger.error("Error fetching all overdue logs", ex)
        throw ex
      }

  /**
   * Retrieve overdue log entries for a specific allocation id.
   *
   * @param allocationId allocation id to filter logs by
   * @return Future[Seq[OverdueLog]]
   */
  def findByAllocation(allocationId: Int): Future[Seq[OverdueLog]] =
    db.run(overdueLogs.filter(_.allocationId === allocationId).result)
      .map(_.map(toModel))
      .recover { case NonFatal(ex) =>
        logger.error(s"Error fetching overdue logs for allocationId=$allocationId", ex)
        throw ex
      }
}
