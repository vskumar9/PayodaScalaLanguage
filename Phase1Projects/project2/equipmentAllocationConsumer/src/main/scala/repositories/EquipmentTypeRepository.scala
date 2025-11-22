package repositories

import models.EquipmentType
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.sql.Timestamp
import java.time.Instant
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing [[EquipmentType]] records using Slick with a MySQL backend.
 *
 * This repository handles all persistence logic related to equipment types, which
 * categorize inventory items (e.g., Laptop, Monitor, Projector).
 *
 * ### Responsibilities
 * - CRUD-like operations:
 *     - Create new equipment types
 *     - Fetch active (non-deleted) types
 *     - Update existing types
 * - Convert between internal Slick DB row representation and the domain model.
 * - Implement **soft-delete semantics** (`isDeleted`, `deletedAt`).
 *
 * ### Notes
 * - Uses a `MappedColumnType` to map `Instant` <-> MySQL `TIMESTAMP`
 * - All operations return `Future` values.
 * - Filters out soft-deleted records in all `find*` methods.
 *
 * ### Typical Usage
 * - Inventory creation workflows (assign type to equipment)
 * - Admin panels for managing equipment categories
 * - Validation layers ensuring active types exist before allocation
 *
 * @param dbConfigProvider Slick database configuration provider (injected)
 * @param ec ExecutionContext for asynchronous DB operations
 */
@Singleton
class EquipmentTypeRepository @Inject()(
                                         protected val dbConfigProvider: DatabaseConfigProvider
                                       )(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile] {

  import profile.api._

  /**
   * Implicit Slick column mapping from Instant to SQL Timestamp.
   */
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  // ---------------------------------------------------------------------------
  // Internal DB row structure
  // ---------------------------------------------------------------------------

  /**
   * Internal representation of `equipment_types` DB table rows.
   *
   * This struct matches the schema exactly but is only used internally.
   * Domain conversions happen via [[toModel]].
   */
  private case class TypeRow(
                              typeId: Int,
                              typeName: String,
                              description: Option[String],
                              isActive: Boolean,
                              createdAt: Instant,
                              updatedAt: Instant,
                              isDeleted: Boolean,
                              deletedAt: Option[Instant]
                            )

  /**
   * Slick table mapping for the `equipment_types` database table.
   */
  private class TypesTable(tag: Tag) extends Table[TypeRow](tag, "equipment_types") {
    def typeId      = column[Int]("type_id", O.PrimaryKey, O.AutoInc)
    def typeName    = column[String]("type_name")
    def description = column[Option[String]]("description")
    def isActive    = column[Boolean]("is_active")
    def createdAt   = column[Instant]("created_at")
    def updatedAt   = column[Instant]("updated_at")
    def isDeleted   = column[Boolean]("is_deleted")
    def deletedAt   = column[Option[Instant]]("deleted_at")

    def * =
      (typeId, typeName, description, isActive, createdAt, updatedAt, isDeleted, deletedAt)
        .mapTo[TypeRow]
  }

  private val types = TableQuery[TypesTable]

  /**
   * Convert an internal `TypeRow` to the domain model [[EquipmentType]].
   */
  private def toModel(r: TypeRow): EquipmentType =
    EquipmentType(
      typeId      = r.typeId,
      typeName    = r.typeName,
      description = r.description,
      isActive    = r.isActive,
      createdAt   = r.createdAt,
      updatedAt   = r.updatedAt,
      isDeleted   = r.isDeleted,
      deletedAt   = r.deletedAt
    )

  /**
   * Find an equipment type by ID (excluding soft-deleted types).
   *
   * @param id typeId
   * @return Future[Option[EquipmentType]]
   */
  def findById(id: Int): Future[Option[EquipmentType]] =
    db.run(types.filter(t => t.typeId === id && t.isDeleted === false).result.headOption)
      .map(_.map(toModel))

  /**
   * Fetch all active (non-deleted) equipment types.
   *
   * @return Future sequence of EquipmentType
   */
  def findAll(): Future[Seq[EquipmentType]] =
    db.run(types.filter(_.isDeleted === false).result).map(_.map(toModel))

  /**
   * Create a new equipment type.
   *
   * Automatically:
   *  - Generates `typeId`
   *  - Sets createdAt and updatedAt to now
   *  - Marks isDeleted = false
   *
   * @param et EquipmentType model (input timestamps ignored)
   * @return Future generated typeId
   */
  def create(et: EquipmentType): Future[Int] = {
    val now = Instant.now()
    val row = TypeRow(
      typeId = 0,
      typeName = et.typeName,
      description = et.description,
      isActive = et.isActive,
      createdAt = now,
      updatedAt = now,
      isDeleted = false,
      deletedAt = None
    )

    db.run((types returning types.map(_.typeId)) += row)
  }

  /**
   * Update an existing equipment type.
   *
   * Updates:
   *  - typeName
   *  - description
   *  - isActive
   *  - updatedAt
   *
   * If the type does not exist or is soft-deleted, returns 0.
   *
   * @param et updated EquipmentType
   * @return number of rows updated
   */
  def update(et: EquipmentType): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- types.filter(t => t.typeId === et.typeId && t.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(
            typeName = et.typeName,
            description = et.description,
            isActive = et.isActive,
            updatedAt = now
          )
          types.filter(_.typeId === et.typeId).update(updated)

        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally)
  }
}
