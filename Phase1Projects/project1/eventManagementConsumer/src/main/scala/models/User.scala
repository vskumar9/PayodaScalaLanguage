package models

import java.time.Instant

/**
 * Represents a system user, including authentication details, personal information,
 * account status, and lifecycle metadata. Users may belong to teams, receive
 * notifications, be assigned tasks, and perform event-related actions depending on their roles.
 *
 * @param userId        Optional unique identifier for the user (assigned when persisted).
 *
 * @param username      Login username used for authentication. Must be unique.
 *
 * @param passwordHash  BCrypt or equivalent hash of the user's password.
 *                      The plain password is never stored.
 *
 * @param fullName      Full display name of the user.
 *
 * @param email         Email address of the user. Typically used for communication
 *                      and notification delivery.
 *
 * @param phone         Optional phone number for user contact.
 *
 * @param isActive      Indicates whether the account is active and allowed to log in.
 *
 * @param isDeleted     Soft-deletion flag indicating whether the user has been removed.
 *
 * @param createdAt     Timestamp marking when the user account was initially created.
 *
 * @param updatedAt     Timestamp showing the most recent modification to the user record.
 *
 * @param deletedAt     Optional timestamp marking when the user was soft-deleted.
 */
case class User(
                 userId: Option[Int],
                 username: String,
                 passwordHash: String,
                 fullName: String,
                 email: String,
                 phone: Option[String],
                 isActive: Boolean,
                 isDeleted: Boolean,
                 createdAt: Instant,
                 updatedAt: Instant,
                 deletedAt: Option[Instant]
               )
