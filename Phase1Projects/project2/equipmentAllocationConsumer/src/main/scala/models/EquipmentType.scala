package models

import java.time.Instant

/**
 * Represents a category or classification of equipment items.
 *
 * Equipment types define logical groupings used across the inventory system.
 * Examples include:
 *   - Laptop
 *   - Monitor
 *   - Keyboard
 *   - Projector
 *   - Networking Equipment
 *
 * The `EquipmentType` model is used primarily to:
 *   - Categorize equipment for reporting, filtering, and analytics.
 *   - Associate each `EquipmentItem` via its `typeId`.
 *   - Control whether a category is active and usable by allocations (via `isActive` flag).
 *   - Maintain soft-delete semantics (`isDeleted`, `deletedAt`) for archival or deprecation.
 *
 * @param typeId      Unique identifier for this equipment type.
 * @param typeName    Human-readable name of the category (e.g., "Laptop", "Projector").
 * @param description Optional longer description of the category or intended use.
 * @param isActive    Indicates whether this type is active and available for use.
 * @param createdAt   Timestamp when this type record was created.
 * @param updatedAt   Timestamp when this type record was last updated.
 * @param isDeleted   Soft-delete flag; true means this type is logically removed.
 * @param deletedAt   Optional timestamp recorded when the type was soft-deleted.
 */
case class EquipmentType(
                          typeId: Int,
                          typeName: String,
                          description: Option[String],
                          isActive: Boolean,
                          createdAt: Instant,
                          updatedAt: Instant,
                          isDeleted: Boolean,
                          deletedAt: Option[Instant]
                        )
