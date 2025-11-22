package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging

import models.EquipmentItem
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for CRUD operations on `equipment_items` table using Slick.
 *
 * Responsibilities:
 *  - map between database rows and domain model [[EquipmentItem]]
 *  - provide asynchronous methods to find, create, update status, and soft-delete items
 *
 * All public methods attach `.recover` handlers that log unexpected database
 * errors and re-throw them so higher layers (controllers/services) can decide
 * how to convert failures into HTTP responses.
 *
 * @param dbConfigProvider Play's database config provider
 * @param ec ExecutionContext used to run asynchronous DB actions
 */
@Singleton
class EquipmentItemRepository @Inject()(
                                         protected val dbConfigProvider: DatabaseConfigProvider
                                       )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile]
    with Logging {

  import profile.api._

  /** Slick mapping for java.time.Instant <-> java.sql.Timestamp. */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  /**
   * Internal row representation for the equipment_items table.
   *
   * Mirrors DB schema and is used for Slick mapping.
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
   * Slick table definition for `equipment_items`.
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
   * Convert DB row to domain model.
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
   * Convert domain model to DB row, ensuring createdAt/updatedAt are set.
   *
   * @param item domain model
   * @param now current instant
   * @return EquipmentItemRow
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
    db.run(equipmentItems.filter(_.isDeleted === false).result)
      .map(_.map(toModel))
      .recover { case NonFatal(ex) =>
        logger.error("Error in findAll()", ex)
        throw ex
      }

  /**
   * Find a single equipment item by id (if not soft-deleted).
   *
   * @param id equipment id
   * @return Future[Option[EquipmentItem]]
   */
  def findById(id: Int): Future[Option[EquipmentItem]] =
    db.run(equipmentItems.filter(e => e.equipmentId === id && e.isDeleted === false).result.headOption)
      .map(_.map(toModel))
      .recover { case NonFatal(ex) =>
        logger.error(s"Error in findById(id=$id)", ex)
        throw ex
      }

  /**
   * Find available equipment items by type (status = "AVAILABLE").
   *
   * @param typeId equipment type id
   * @return Future sequence of available EquipmentItem
   */
  def findAvailableByType(typeId: Int): Future[Seq[EquipmentItem]] =
    db.run(
      equipmentItems
        .filter(e => e.isDeleted === false && e.typeId === typeId && e.status === "AVAILABLE")
        .result
    ).map(_.map(toModel)).recover { case NonFatal(ex) =>
      logger.error(s"Error in findAvailableByType(typeId=$typeId)", ex)
      throw ex
    }

  /**
   * Create a new equipment item and return generated id.
   *
   * @param item EquipmentItem (equipmentId is ignored)
   * @return Future[Int] generated equipmentId
   */
  def create(item: EquipmentItem): Future[Int] = {
    val now = Instant.now()
    val row = toRow(item.copy(createdAt = now, updatedAt = now), now).copy(equipmentId = 0)
    db.run((equipmentItems returning equipmentItems.map(_.equipmentId)) += row).recover { case NonFatal(ex) =>
      logger.error(s"Error creating equipment item assetTag=${item.assetTag}", ex)
      throw ex
    }
  }

  /**
   * Update an existing equipment item. Returns number of rows updated (1 if success).
   *
   * @param item EquipmentItem with equipmentId set
   * @return Future[Int] rows updated
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error updating equipment item ${item.equipmentId}", ex)
      throw ex
    }
  }

  /**
   * Update only status and condition for an equipment item.
   *
   * @param equipmentId id of equipment
   * @param status new status value
   * @param condition new condition value
   * @return Future[Int] rows updated
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in updateStatusAndCondition(equipmentId=$equipmentId)", ex)
      throw ex
    }
  }

  /**
   * Soft-delete an equipment item by setting isDeleted = true and deletedAt.
   *
   * @param id equipment id
   * @return Future[Int] rows updated (1 if success)
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in softDelete(equipmentId=$id)", ex)
      throw ex
    }
  }
}
