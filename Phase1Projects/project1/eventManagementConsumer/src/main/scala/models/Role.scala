package models

/**
 * Represents a user role within the system, typically used for authorization,
 * access control, and permission grouping.
 *
 * @param roleId    Optional unique identifier for the role. Assigned when persisted.
 * @param roleName  Name of the role (e.g., "Admin", "Manager", "Technician", "User").
 */
case class Role(
                 roleId: Option[Int],
                 roleName: String
               )
