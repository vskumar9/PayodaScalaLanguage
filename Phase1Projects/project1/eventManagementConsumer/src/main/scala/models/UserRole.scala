package models

/**
 * Represents the association between a user and a role, forming the basis
 * of the system’s Role-Based Access Control (RBAC) model.
 *
 * A user may have one or more roles, and each role defines a set of permissions
 * or access levels throughout the application.
 *
 * @param id      Optional unique identifier for the user-role mapping (assigned when persisted).
 *
 * @param userId  Identifier of the user to whom the role is assigned.
 *
 * @param roleId  Identifier of the role assigned to the user.
 */
case class UserRole(
                     id: Option[Int],
                     userId: Int,
                     roleId: Int
                   )
