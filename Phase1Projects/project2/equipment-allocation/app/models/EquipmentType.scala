package models

import java.time.Instant

/**
 * Represents a classification/category of equipment in the inventory system.
 *
 * Equipment types help group similar equipment items (e.g., *Laptop*, *Monitor*,
 * *Projector*, *Tablet*) and serve as the parent category for EquipmentItem.
 * This model controls whether a type is active and supports soft deletion to
 * maintain historical integrity.
 *
 * @param typeId Unique identifier for the equipment type
 * @param typeName Human-readable name of the type (e.g., "Laptop", "Printer")
 * @param description Optional additional details about this type
 * @param isActive Indicates whether this type is currently active and allowed for use
 * @param createdAt Timestamp when the equipment type was created
 * @param updatedAt Timestamp when the equipment type was last updated
 * @param isDeleted Soft-delete flag (true if logically deleted)
 * @param deletedAt Optional timestamp when the type was soft-deleted
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
