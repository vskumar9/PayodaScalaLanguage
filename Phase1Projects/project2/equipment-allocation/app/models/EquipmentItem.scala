package models

import java.time.Instant

/**
 * Represents an inventory item (physical equipment) within the system.
 *
 * This model stores all metadata about a piece of equipment, including its
 * identifiers, type classification, physical condition, status, and audit
 * timestamps. Equipment items are allocated to employees, returned, repaired,
 * retired, and tracked using this entity.
 *
 * @param equipmentId Unique identifier for the equipment item
 * @param assetTag Internal asset tag or barcode number used by the organization
 * @param serialNumber Manufacturer-provided serial number
 * @param typeId Foreign key reference to the equipment type (e.g., Laptop, Projector)
 * @param location Optional physical location of the item (storage room, floor, branch)
 *
 * @param status Current operational status of the equipment:
 *               - `"AVAILABLE"`          → ready for allocation
 *               - `"ALLOCATED"`          → currently issued to an employee
 *               - `"UNDER_MAINTENANCE"`  → unavailable due to active maintenance work
 *               - `"RETIRED"`            → removed permanently from inventory
 *
 * @param condition Condition/state of the equipment:
 *                  - `"GOOD"`            → working condition
 *                  - `"DAMAGED"`         → damaged and requires attention
 *                  - `"LOST"`            → missing and cannot be allocated
 *                  - `"REPAIR_PENDING"`  → waiting for maintenance/repair
 *
 * @param createdAt Timestamp when the equipment record was created
 * @param updatedAt Timestamp when the equipment record was last modified
 * @param isDeleted Soft-delete flag (true if logically deleted)
 * @param deletedAt Optional timestamp when soft deletion occurred
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
