package services

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

import models.MaintenanceTicket
import repositories.MaintenanceTicketRepository

/**
 * Service layer for managing equipment maintenance tickets.
 *
 * This service provides a simple abstraction over the `MaintenanceTicketRepository`,
 * exposing operations related to:
 *   - Fetching ticket details
 *   - Updating ticket status
 *   - Assigning tickets to employees
 *
 * It acts as the business logic layer between controllers and the database.
 *
 * @param ticketRepo the repository used to perform CRUD operations on maintenance tickets
 * @param ec the execution context used for asynchronous operations
 */
@Singleton
class MaintenanceService @Inject()(
                                    ticketRepo: MaintenanceTicketRepository
                                  )(implicit ec: ExecutionContext) {

  /**
   * Retrieves a single maintenance ticket by its ID.
   *
   * @param id the ID of the ticket to fetch
   * @return a Future containing `Some(ticket)` if found, or `None` if it does not exist
   */
  def getTicket(id: Int): Future[Option[MaintenanceTicket]] =
    ticketRepo.findById(id)

  /**
   * Updates the status of an existing maintenance ticket.
   *
   * This method updates:
   *   - The ticket status (OPEN, IN_PROGRESS, RESOLVED, CLOSED)
   *   - Whether the ticket is resolved
   *   - The timestamp when it was resolved (optional)
   *
   * @param ticketId the ID of the ticket to update
   * @param status the new status value
   * @param isResolved indicates whether the ticket has been fully resolved
   * @param resolvedAt optional timestamp when the ticket was resolved
   * @return a Future containing the number of rows updated (0 if ticket not found)
   */
  def updateStatus(
                    ticketId: Int,
                    status: String,
                    isResolved: Boolean,
                    resolvedAt: Option[Instant]
                  ): Future[Int] =
    ticketRepo.updateStatus(ticketId, status, isResolved, resolvedAt)

  /**
   * Assigns a maintenance ticket to an employee.
   *
   * @param ticketId the ticket to assign
   * @param employeeId the ID of the employee who will be responsible for the ticket
   * @return a Future containing the number of rows updated (0 if ticket not found)
   */
  def assignTicket(ticketId: Int, employeeId: Int): Future[Int] =
    ticketRepo.assignTo(ticketId, employeeId)
}
