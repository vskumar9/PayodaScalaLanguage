package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging

import models.MaintenanceTicket
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for CRUD operations on the `maintenance_tickets` table using Slick.
 *
 * Responsibilities:
 *  - map between database rows and domain model [[MaintenanceTicket]]
 *  - provide asynchronous methods to list, find, create, update status, assign and soft-delete tickets
 *
 * All public methods attach `.recover` handlers that log unexpected database
 * errors and re-throw them so higher layers (controllers/services) can decide
 * how to convert failures into HTTP responses.
 *
 * @param dbConfigProvider Play's database config provider
 * @param ec ExecutionContext used to run asynchronous DB actions
 */
@Singleton
class MaintenanceTicketRepository @Inject()(
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
   * Internal row representation for the maintenance_tickets table.
   *
   * Mirrors DB schema and is used for Slick mapping.
   */
  private case class TicketRow(
                                ticketId: Int,
                                equipmentId: Int,
                                allocationId: Option[Int],
                                issueReportedAt: Instant,
                                issueNotes: String,
                                status: String,
                                severity: String,
                                createdByEmployeeId: Int,
                                assignedToEmployeeId: Option[Int],
                                isResolved: Boolean,
                                resolvedAt: Option[Instant],
                                createdAt: Instant,
                                updatedAt: Instant,
                                isDeleted: Boolean,
                                deletedAt: Option[Instant]
                              )

  /**
   * Slick table mapping for maintenance_tickets.
   */
  private class TicketsTable(tag: Tag) extends Table[TicketRow](tag, "maintenance_tickets") {
    def ticketId              = column[Int]("ticket_id", O.PrimaryKey, O.AutoInc)
    def equipmentId           = column[Int]("equipment_id")
    def allocationId          = column[Option[Int]]("allocation_id")
    def issueReportedAt       = column[Instant]("issue_reported_at")
    def issueNotes            = column[String]("issue_notes")
    def status                = column[String]("status")
    def severity              = column[String]("severity")
    def createdByEmployeeId   = column[Int]("created_by_employee_id")
    def assignedToEmployeeId  = column[Option[Int]]("assigned_to_employee_id")
    def isResolved            = column[Boolean]("is_resolved")
    def resolvedAt            = column[Option[Instant]]("resolved_at")
    def createdAt             = column[Instant]("created_at")
    def updatedAt             = column[Instant]("updated_at")
    def isDeleted             = column[Boolean]("is_deleted")
    def deletedAt             = column[Option[Instant]]("deleted_at")

    def * =
      (ticketId, equipmentId, allocationId, issueReportedAt, issueNotes, status, severity,
        createdByEmployeeId, assignedToEmployeeId, isResolved, resolvedAt, createdAt,
        updatedAt, isDeleted, deletedAt).mapTo[TicketRow]
  }

  private val tickets = TableQuery[TicketsTable]

  /**
   * Convert a DB row to the domain model [[MaintenanceTicket]].
   */
  private def toModel(r: TicketRow): MaintenanceTicket =
    MaintenanceTicket(
      ticketId              = r.ticketId,
      equipmentId           = r.equipmentId,
      allocationId          = r.allocationId,
      issueReportedAt       = r.issueReportedAt,
      issueNotes            = r.issueNotes,
      status                = r.status,
      severity              = r.severity,
      createdByEmployeeId   = r.createdByEmployeeId,
      assignedToEmployeeId  = r.assignedToEmployeeId,
      isResolved            = r.isResolved,
      resolvedAt            = r.resolvedAt,
      createdAt             = r.createdAt,
      updatedAt             = r.updatedAt,
      isDeleted             = r.isDeleted,
      deletedAt             = r.deletedAt
    )

  // ---------------------------------------------------------------------------
  // Queries / Commands
  // ---------------------------------------------------------------------------

  /**
   * Retrieve all non-deleted maintenance tickets, sorted by createdAt descending.
   *
   * @return Future[Seq[MaintenanceTicket]]
   */
  def findAll(): Future[Seq[MaintenanceTicket]] =
    db.run {
      tickets.filter(_.isDeleted === false).result
    }.map { rows =>
      rows.map(toModel).sortWith((a, b) => a.createdAt.isAfter(b.createdAt))
    }.recover { case NonFatal(ex) =>
      logger.error("Error in findAll()", ex)
      throw ex
    }

  /**
   * Find a ticket by id (non-deleted).
   *
   * @param id ticket id
   * @return Future[Option[MaintenanceTicket]]
   */
  def findById(id: Int): Future[Option[MaintenanceTicket]] =
    db.run(
      tickets.filter(t => t.ticketId === id && t.isDeleted === false).result.headOption
    ).map(_.map(toModel)).recover { case NonFatal(ex) =>
      logger.error(s"Error in findById(ticketId=$id)", ex)
      throw ex
    }

  /**
   * Create a new maintenance ticket and return the generated ticket id.
   *
   * @param ticket MaintenanceTicket (ticketId ignored)
   * @return Future[Int] generated ticketId
   */
  def create(ticket: MaintenanceTicket): Future[Int] = {
    val now = Instant.now()
    val row = TicketRow(
      ticketId = 0,
      equipmentId = ticket.equipmentId,
      allocationId = ticket.allocationId,
      issueReportedAt = ticket.issueReportedAt,
      issueNotes = ticket.issueNotes,
      status = ticket.status,
      severity = ticket.severity,
      createdByEmployeeId = ticket.createdByEmployeeId,
      assignedToEmployeeId = ticket.assignedToEmployeeId,
      isResolved = ticket.isResolved,
      resolvedAt = ticket.resolvedAt,
      createdAt = now,
      updatedAt = now,
      isDeleted = false,
      deletedAt = None
    )

    db.run((tickets returning tickets.map(_.ticketId)) += row).recover { case NonFatal(ex) =>
      logger.error(s"Error creating ticket for equipmentId=${ticket.equipmentId}", ex)
      throw ex
    }
  }

  /**
   * Update the status/resolution metadata of a ticket.
   *
   * @param ticketId ticket id
   * @param status new status
   * @param isResolved resolved flag
   * @param resolvedAt optional resolved timestamp
   * @return Future[Int] rows updated (1 if success, 0 if not found)
   */
  def updateStatus(
                    ticketId: Int,
                    status: String,
                    isResolved: Boolean,
                    resolvedAt: Option[Instant]
                  ): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- tickets.filter(t => t.ticketId === ticketId && t.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(
            status = status,
            isResolved = isResolved,
            resolvedAt = resolvedAt,
            updatedAt = now
          )
          tickets.filter(_.ticketId === ticketId).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in updateStatus(ticketId=$ticketId)", ex)
      throw ex
    }
  }

  /**
   * Assign a ticket to an employee.
   *
   * @param ticketId ticket id
   * @param assignedToEmployeeId employee id to assign
   * @return Future[Int] rows updated (1 if success, 0 if not found)
   */
  def assignTo(ticketId: Int, assignedToEmployeeId: Int): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- tickets.filter(t => t.ticketId === ticketId && t.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(assignedToEmployeeId = Some(assignedToEmployeeId), updatedAt = now)
          tickets.filter(_.ticketId === ticketId).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in assignTo(ticketId=$ticketId, assignedTo=$assignedToEmployeeId)", ex)
      throw ex
    }
  }

  /**
   * Soft-delete a ticket by marking `isDeleted = true` and setting `deletedAt`.
   *
   * @param id ticket id
   * @return Future[Int] rows updated (1 if success, 0 if not found)
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- tickets.filter(t => t.ticketId === id && t.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(isDeleted = true, deletedAt = Some(now), updatedAt = now)
          tickets.filter(_.ticketId === id).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in softDelete(ticketId=$id)", ex)
      throw ex
    }
  }
}
