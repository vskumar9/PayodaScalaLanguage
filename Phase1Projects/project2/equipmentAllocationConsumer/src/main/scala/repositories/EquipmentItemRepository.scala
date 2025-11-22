package repositories

import models.EquipmentItem
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing `EquipmentItem` records using Slick and a MySQL profile.
 *
 * Responsibilities:
 *  - CRUD-like operations for equipment items (create, read, update, soft-delete).
 *  - Helper methods for common queries (find available items by type).
 *  - Converts between database row representation (`EquipmentItemRow`) and domain model [[models.EquipmentItem]].
 *
 * Notes:
 *  - Uses a Slick `MappedColumnType` to map `java.time.Instant` ↔ SQL `TIMESTAMP`.
 *  - Implements soft-delete semantics via `isDeleted` and `deletedAt`.
 *  - Methods return `Future` results and require an implicit `ExecutionContext`.
 *
 * Typical usage:
 *  - Inventory management controllers
 *  - Allocation workflows (to find available items)
 *  - Maintenance and reminder flows that need item metadata
 *
 * @param dbConfigProvider Play `DatabaseConfigProvider` injected by DI
 * @param ec               Implicit ExecutionContext for async DB calls
 */
@Singleton
class EquipmentItemRepository @Inject()(
                                         protected val dbConfigProvider: DatabaseConfigProvider
                                       )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile] {

  import profile.api._

  /**
   * Slick column mapping for converting between `Instant` and SQL `Timestamp`.
   *
   * This implicit is used automatically by Slick when reading/writing Instant columns.
   */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /**
   * Internal Slick representation of a row from `equipment_items`.
   *
   * Not exposed externally; converted to the domain model using `toModel`.
   */
  private case class EquipmentItemRow(
                                       equipmentId: Int,
                                       assetTag: String,
                                       serialNumber: String,
                                       typeId: Int,
                                       location: Option[String],
                                       status: String,
                                       condition: String,
                                       createdAt: Instant,
                                       updatedAt: Instant,
                                       isDeleted: Boolean,
                                       deletedAt: Option[Instant]
                                     )

  /**
   * Slick table mapping for `equipment_items`.
   */
  private class EquipmentItemsTable(tag: Tag) extends Table[EquipmentItemRow](tag, "equipment_items") {
    def equipmentId  = column[Int]("equipment_id", O.PrimaryKey, O.AutoInc)
    def assetTag     = column[String]("asset_tag")
    def serialNumber = column[String]("serial_number")
    def typeId       = column[Int]("type_id")
    def location     = column[Option[String]]("location")
    def status       = column[String]("status")
    def condition    = column[String]("condition")
    def createdAt    = column[Instant]("created_at")
    def updatedAt    = column[Instant]("updated_at")
    def isDeleted    = column[Boolean]("is_deleted")
    def deletedAt    = column[Option[Instant]]("deleted_at")

    def * =
      (equipmentId, assetTag, serialNumber, typeId, location, status, condition,
        createdAt, updatedAt, isDeleted, deletedAt).mapTo[EquipmentItemRow]
  }

  private val equipmentItems = TableQuery[EquipmentItemsTable]

  /**
   * Convert an internal `EquipmentItemRow` to the public [[EquipmentItem]] domain model.
   *
   * @param r the DB row
   * @return domain model
   */
  private def toModel(r: EquipmentItemRow): EquipmentItem =
    EquipmentItem(
      equipmentId  = r.equipmentId,
      assetTag     = r.assetTag,
      serialNumber = r.serialNumber,
      typeId       = r.typeId,
      location     = r.location,
      status       = r.status,
      condition    = r.condition,
      createdAt    = r.createdAt,
      updatedAt    = r.updatedAt,
      isDeleted    = r.isDeleted,
      deletedAt    = r.deletedAt
    )

  /**
   * Convert a domain [[EquipmentItem]] to an `EquipmentItemRow` for insertion/update.
   *
   * If the item's `createdAt` looks like an "unset" value (Instant.EPOCH), the provided `now`
   * will be used as the created timestamp.
   *
   * @param item domain model
   * @param now  current Instant used for createdAt/updatedAt when needed
   * @return row ready for DB operations
   */
  private def toRow(item: EquipmentItem, now: Instant): EquipmentItemRow = {
    val created = Option(item.createdAt).filterNot(_ == Instant.EPOCH).getOrElse(now)
    EquipmentItemRow(
      equipmentId = item.equipmentId,
      assetTag = item.assetTag,
      serialNumber = item.serialNumber,
      typeId = item.typeId,
      location = item.location,
      status = item.status,
      condition = item.condition,
      createdAt = created,
      updatedAt = now,
      isDeleted = item.isDeleted,
      deletedAt = item.deletedAt
    )
  }

  /**
   * Retrieve all non-deleted equipment items.
   *
   * @return Future sequence of EquipmentItem
   */
  def findAll(): Future[Seq[EquipmentItem]] =
    db.run(equipmentItems.filter(!_.isDeleted).result).map(_.map(toModel))

  /**
   * Find an equipment item by its primary key (excluding soft-deleted rows).
   *
   * @param id equipmentId
   * @return Future optional EquipmentItem
   */
  def findById(id: Int): Future[Option[EquipmentItem]] =
    db.run(equipmentItems.filter(e => e.equipmentId === id && !e.isDeleted).result.headOption)
      .map(_.map(toModel))

  /**
   * Find items of a given type that are currently available.
   *
   * Status must equal the literal "AVAILABLE".
   *
   * @param typeId equipment type id
   * @return Future sequence of available items of that type
   */
  def findAvailableByType(typeId: Int): Future[Seq[EquipmentItem]] =
    db.run(
      equipmentItems
        .filter(e => !e.isDeleted && e.typeId === typeId && e.status === "AVAILABLE")
        .result
    ).map(_.map(toModel))

  /**
   * Create a new equipment item record.
   *
   * Automatically sets createdAt/updatedAt to now and returns the generated equipmentId.
   *
   * @param item domain model (any provided createdAt will be overwritten)
   * @return Future generated equipmentId
   */
  def create(item: EquipmentItem): Future[Int] = {
    val now = Instant.now()
    val row = toRow(item.copy(createdAt = now, updatedAt = now), now).copy(equipmentId = 0)
    db.run((equipmentItems returning equipmentItems.map(_.equipmentId)) += row)
  }

  /**
   * Update an existing equipment item. Only updates the mutable fields and updatedAt.
   *
   * Fields changed: assetTag, serialNumber, typeId, location, status, condition, updatedAt.
   *
   * If the item does not exist or is soft-deleted, the returned Future contains 0.
   *
   * @param item domain model with equipmentId set
   * @return Future number of affected rows (0 or 1)
   */
  def update(item: EquipmentItem): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- equipmentItems.filter(e => e.equipmentId === item.equipmentId && e.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(
            assetTag = item.assetTag,
            serialNumber = item.serialNumber,
            typeId = item.typeId,
            location = item.location,
            status = item.status,
            condition = item.condition,
            updatedAt = now
          )
          equipmentItems.filter(_.equipmentId === item.equipmentId).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }

  /**
   * Update only the status and condition of an equipment item.
   *
   * Useful for rapid state transitions (e.g., set to UNDER_MAINTENANCE / DAMAGED).
   *
   * @param equipmentId id of the equipment
   * @param status      new status string
   * @param condition   new condition string
   * @return Future number of affected rows (0 or 1)
   */
  def updateStatusAndCondition(
                                equipmentId: Int,
                                status: String,
                                condition: String
                              ): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- equipmentItems.filter(e => e.equipmentId === equipmentId && e.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(status = status, condition = condition, updatedAt = now)
          equipmentItems.filter(_.equipmentId === equipmentId).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }

  /**
   * Soft-delete an equipment item (preserves history).
   *
   * Sets `isDeleted = true`, `deletedAt = now`, and updates `updatedAt`.
   *
   * @param id equipmentId to soft-delete
   * @return Future number of affected rows (0 or 1)
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- equipmentItems.filter(e => e.equipmentId === id && e.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(isDeleted = true, deletedAt = Some(now), updatedAt = now)
          equipmentItems.filter(_.equipmentId === id).update(updated)
        case None => DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }
}
