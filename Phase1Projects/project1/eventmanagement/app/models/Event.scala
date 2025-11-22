package models

import java.time.Instant

/**
 * Represents an event in the system.
 *
 * This model stores all core event metadata, including scheduling details,
 * descriptive fields, audit information, and soft-delete tracking fields.
 *
 * @param eventId            optional unique identifier of the event (None before insert)
 * @param createdBy          user ID of the creator
 * @param title              title of the event
 * @param eventType          event category/type (e.g., Conference, Meeting, Ceremony)
 * @param description        optional description providing more context
 * @param eventDate          date and time at which the event is scheduled to occur
 * @param expectedGuestCount optional expected number of guests
 * @param location           optional location or venue of the event
 * @param status             current status (e.g., Scheduled, Completed, Cancelled)
 * @param createdAt          timestamp when the record was created
 * @param updatedAt          timestamp when the record was last updated
 * @param isDeleted          soft-delete flag indicating whether the event is deleted
 * @param deletedAt          timestamp when the event was soft-deleted (if applicable)
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
