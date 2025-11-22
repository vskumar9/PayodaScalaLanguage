package repositories

import models.MaintenanceTicket
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing [[MaintenanceTicket]] entities using Slick and MySQL.
 *
 * This repository encapsulates all database operations related to maintenance
 * tickets such as:
 *
 *   • Creating maintenance tickets
 *   • Fetching tickets (single or list)
 *   • Updating ticket status and resolution
 *   • Assigning tickets to employees
 *   • Soft-deleting tickets
 *
 * ### Features
 * - Uses Slick's functional relational mapping.
 * - Automatically maps `Instant` to MySQL `TIMESTAMP`.
 * - Supports **soft delete** (`isDeleted = true`) instead of hard removal.
 * - Sorts ticket lists by newest first.
 * - Ensures DB integrity by always filtering out soft-deleted rows.
 *
 * ### Used By
 * - `MaintenanceActor` (email notifications)
 * - Maintenance controllers (CRUD operations)
 * - Reporting and admin dashboards
 *
 * ### Table Overview
 * Table: **maintenance_tickets**
 *
 * Contains:
 *   - Ticket metadata
 *   - Equipment + Allocation reference
 *   - Issue details
 *   - Assignment / resolution workflow
 *   - Auditing fields
 *
 * @param dbConfigProvider Injected Play Slick DB provider
 * @param ec ExecutionContext used for asynchronous DB interactions
 */
@Singleton
class MaintenanceTicketRepository @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile] {

  import profile.api._

  /**
   * Slick column mapping for Instant <-> SQL Timestamp.
   */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /**
   * Internal DB representation of a maintenance ticket row.
   *
   * This is mapped 1:1 to the database table schema and is not exposed externally.
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
   * Slick table definition for `maintenance_tickets`.
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
        createdByEmployeeId, assignedToEmployeeId, isResolved, resolvedAt,
        createdAt, updatedAt, isDeleted, deletedAt).mapTo[TicketRow]
  }

  private val tickets = TableQuery[TicketsTable]

  /**
   * Convert internal DB row into domain model [[MaintenanceTicket]].
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

  /**
   * Fetch all active (non-deleted) maintenance tickets.
   *
   * Results are sorted in reverse chronological order (`createdAt` descending).
   *
   * @return Future sequence of MaintenanceTicket
   */
  def findAll(): Future[Seq[MaintenanceTicket]] =
    db.run {
      tickets.filter(_.isDeleted === false).result
    }.map { rows =>
      rows.map(toModel).sortWith((a, b) => a.createdAt.isAfter(b.createdAt))
    }

  /**
   * Find a maintenance ticket by ID.
   *
   * @param id Ticket ID
   * @return Future optional MaintenanceTicket
   */
  def findById(id: Int): Future[Option[MaintenanceTicket]] =
    db.run(
      tickets.filter(t => t.ticketId === id && t.isDeleted === false).result.headOption
    ).map(_.map(toModel))

  /**
   * Create a new maintenance ticket record.
   *
   * Automatically sets createdAt and updatedAt timestamps.
   *
   * @param ticket the domain model to insert
   * @return Future generated ticketId
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

    db.run((tickets returning tickets.map(_.ticketId)) += row)
  }

  /**
   * Update ticket status, including resolution metadata.
   *
   * Used when:
   *   - Marking a ticket IN_PROGRESS / RESOLVED / CLOSED
   *   - Updating resolution timestamps
   *
   * @param ticketId   ID of the ticket to update
   * @param status     new status string
   * @param isResolved whether the ticket is resolved
   * @param resolvedAt optional resolution timestamp
   * @return Future number of rows updated
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

    db.run(action.transactionally)
  }

  /**
   * Assign a ticket to an employee.
   *
   * Updates `assignedToEmployeeId` and `updatedAt`.
   *
   * @param ticketId ticket to assign
   * @param assignedToEmployeeId employee receiving the assignment
   * @return Future row count (0 or 1)
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

    db.run(action.transactionally)
  }

  /**
   * Soft-delete a ticket by setting `isDeleted = true`.
   *
   * Preserves historical data for audits and analytics.
   *
   * @param id ticketId
   * @return Future row count (0 if not found, 1 if soft-deleted)
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

    db.run(action.transactionally)
  }
}
