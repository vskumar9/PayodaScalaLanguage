package models

import java.time.Instant

/**
 * Represents an application user account with authentication, authorization,
 * and lifecycle information.
 *
 * This model is used throughout the system for:
 *   - Authentication (via `username` + `passwordHash`)
 *   - Authorization (via `roles`)
 *   - Mapping to domain entities such as `Employee`
 *   - Sending notifications (via `email` or optional `phone`)
 *   - Soft-deletion and audit tracking
 *
 * ### Roles
 * A user may have one or more roles (e.g., "admin", "staff", "inventory", "maintenance").
 * These control access in:
 *   - Controllers (RBAC)
 *   - Actors (ticket notifications, reminders)
 *   - UI components
 *
 * ### Lifecycle Fields
 * - `isActive`: Determines if the account can currently log in.
 * - `isDeleted` / `deletedAt`: Soft-delete mechanism for archival.
 *
 * ### Security Notes
 * - `passwordHash` stores the *hashed* password; never store plain-text passwords.
 * - Password hashing is expected to be done using BCrypt or similar secure algorithms.
 *
 * @param userId       Unique identifier for the user.
 * @param username     Username used for login.
 * @param passwordHash BCrypt/SHA-256/etc hashed password string.
 * @param fullName     Full name of the user (used in emails, UI displays).
 * @param email        Primary email address; used for notifications.
 * @param phone        Optional mobile number for SMS or alternative contact.
 * @param isActive     True if the user is allowed to authenticate.
 * @param createdAt    Timestamp when the user account was created.
 * @param updatedAt    Timestamp when the user account was last modified.
 * @param roles        List of assigned roles for RBAC (default empty list).
 * @param isDeleted    Soft-delete flag; true means the user is logically removed.
 * @param deletedAt    Optional timestamp when the user was soft-deleted.
 */
case class User(
                 userId: Int,
                 username: String,
                 passwordHash: String,
                 fullName: String,
                 email: String,
                 phone: Option[String],
                 isActive: Boolean,
                 createdAt: Instant,
                 updatedAt: Instant,
                 roles: List[String] = List.empty,
                 isDeleted: Boolean = false,
                 deletedAt: Option[Instant] = None
               )
