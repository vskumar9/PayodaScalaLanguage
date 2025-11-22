package models

import java.time.Instant

/**
 * Represents a physical equipment item stored and managed within the inventory system.
 *
 * This model contains the core metadata used across allocation, maintenance, inventory audit,
 * and reminder workflows. Each record corresponds to one uniquely identifiable physical item.
 *
 * ### Typical Uses
 * - Allocation workflows (assigning equipment to employees)
 * - Maintenance ticketing and damage tracking
 * - Status updates (e.g., moving from AVAILABLE → ALLOCATED → RETURNED → RETIRED)
 * - Inventory dashboards and reporting
 *
 * ### Key Fields
 * - **assetTag**: Organization-issued label or barcode used for human-visible tracking.
 * - **serialNumber**: Manufacturer hardware identifier (typically unique).
 * - **typeId**: FK to equipment type/category (e.g., laptop, monitor, projector).
 * - **status**: Operational lifecycle state (AVAILABLE, ALLOCATED, UNDER_MAINTENANCE, RETIRED).
 * - **condition**: Physical condition status (GOOD, DAMAGED, LOST, REPAIR_PENDING).
 *
 * Supports soft delete behavior through `isDeleted` and `deletedAt`.
 *
 * @param equipmentId Unique identifier for the equipment item.
 * @param assetTag Organization-specific tag/barcode printed on the device.
 * @param serialNumber Manufacturer-assigned serial number.
 * @param typeId Foreign key identifying the equipment type or category.
 * @param location Optional storage/office location (may be None if mobile/offsite).
 * @param status Lifecycle status of the item (e.g., AVAILABLE, ALLOCATED, UNDER_MAINTENANCE, RETIRED).
 * @param condition Physical/operational condition of the item (GOOD, DAMAGED, LOST, REPAIR_PENDING).
 * @param createdAt Timestamp when the item record was initially created.
 * @param updatedAt Timestamp when the item record was last modified.
 * @param isDeleted Soft-delete flag; true means this item is logically removed from active listings.
 * @param deletedAt Optional timestamp indicating when the item was soft-deleted.
 */
case class EquipmentItem(
                          equipmentId: Int,
                          assetTag: String,
                          serialNumber: String,
                          typeId: Int,
                          location: Option[String],
                          status: String,    // AVAILABLE, ALLOCATED, UNDER_MAINTENANCE, RETIRED
                          condition: String, // GOOD, DAMAGED, LOST, REPAIR_PENDING
                          createdAt: Instant,
                          updatedAt: Instant,
                          isDeleted: Boolean,
                          deletedAt: Option[Instant]
                        )
