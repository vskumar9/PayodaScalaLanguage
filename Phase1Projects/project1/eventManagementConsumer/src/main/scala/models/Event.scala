package models

import java.time.Instant

/**
 * Represents an event within the system, including metadata, scheduling details,
 * status, and soft-deletion tracking.
 *
 * @param eventId            Optional unique identifier for the event. Assigned when persisted.
 * @param createdBy          Identifier of the user who created the event.
 * @param title              Title or name of the event.
 * @param eventType          Category or type of the event (e.g., "Corporate", "Training", "Meeting").
 * @param description        Optional detailed description of the event.
 * @param eventDate          Timestamp indicating when the event is scheduled to occur.
 * @param expectedGuestCount Optional number of guests expected to attend.
 * @param location           Optional event location or venue name.
 * @param status             Current status of the event (e.g., "Scheduled", "Completed", "Cancelled").
 * @param createdAt          Timestamp indicating when the event record was created.
 * @param updatedAt          Timestamp indicating the last time the event record was modified.
 * @param isDeleted          Flag indicating whether the event has been soft-deleted.
 * @param deletedAt          Optional timestamp marking when the event was soft-deleted.
 */
case class Event(
                  eventId: Option[Int],
                  createdBy: Int,
                  title: String,
                  eventType: String,
                  description: Option[String],
                  eventDate: Instant,
                  expectedGuestCount: Option[Int],
                  location: Option[String],
                  status: String,
                  createdAt: Instant,
                  updatedAt: Instant,
                  isDeleted: Boolean,
                  deletedAt: Option[Instant]
                )
