package models

/**
 * Represents the many-to-many relationship between users and roles.
 *
 * A user may have multiple roles, and each role may be assigned to multiple users.
 * This model links a user to a specific role and is typically used as part of the
 * application's RBAC (Role-Based Access Control) system.
 *
 * @param id     optional unique identifier for this user-role association
 * @param userId identifier of the user
 * @param roleId identifier of the assigned role
 */
case class UserRole(
                     id: Option[Int],
                     userId: Int,
                     roleId: Int
                   )
