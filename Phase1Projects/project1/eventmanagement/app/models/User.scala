package models

import java.time.Instant

/**
 * Represents a system user.
 *
 * Users may belong to one or more teams and may hold one or more roles
 * depending on the application's RBAC configuration. This model includes
 * authentication details (password hash), profile information, and audit/
 * soft-delete metadata.
 *
 * @param userId       optional unique identifier of the user (None before creation)
 * @param username     unique login name for authentication
 * @param passwordHash hashed password using a secure algorithm (e.g., BCrypt)
 * @param fullName     full name of the user for display purposes
 * @param email        user's email address
 * @param phone        optional contact phone number
 *
 * @param isActive     flag indicating whether the user account is active
 * @param isDeleted    soft-delete flag indicating whether the user account is deleted
 *
 * @param createdAt    timestamp when the user record was created
 * @param updatedAt    timestamp when the user record was last updated
 * @param deletedAt    optional timestamp when the user was soft-deleted
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
