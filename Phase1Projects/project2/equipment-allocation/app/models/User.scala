package models

import java.time.Instant

/**
 * Represents an application user account.
 *
 * This model stores authentication credentials, personal profile information,
 * account status, assigned roles, and audit timestamps. It is used for
 * authentication, authorization (RBAC), and linking to employee records.
 *
 * Passwords are stored as **hashed values** — never as plain text.
 *
 * @param userId Unique identifier for the user
 * @param username Login username (unique)
 * @param passwordHash Hashed password stored securely (bcrypt or equivalent)
 * @param fullName Full display name of the user
 * @param email Email address used for communication and verification
 * @param phone Optional phone number for contact or 2FA integration
 * @param isActive Indicates whether the account is active and allowed to log in
 *
 * @param createdAt Timestamp when the user record was created
 * @param updatedAt Timestamp when the user record was last modified
 *
 * @param roles List of roles assigned to the user (supports RBAC)
 *              Examples:
 *              - `"Admin"`
 *              - `"MaintenanceTeam"`
 *              - `"InventoryTeam"`
 *              - `"ReceptionStaff"`
 *
 * @param isDeleted Soft-delete flag indicating logical deletion
 * @param deletedAt Optional timestamp recording when deletion occurred
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
