package models

/**
 * Represents a system role assigned to users for permission and access control.
 *
 * Roles determine what operations a user can perform throughout the application.
 * Examples may include: Admin, EventManager, TeamMember, Viewer, etc.
 *
 * @param roleId   optional unique identifier for the role (None before insertion)
 * @param roleName human-readable name of the role
 */
case class Role(
                 roleId: Option[Int],
                 roleName: String
               )
