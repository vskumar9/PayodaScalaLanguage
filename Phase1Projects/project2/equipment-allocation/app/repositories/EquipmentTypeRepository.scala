package repositories

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Logging

import models.EquipmentType
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.MySQLProfile

import java.time.Instant
import java.sql.Timestamp

/**
 * Repository for CRUD operations on the `equipment_types` table using Slick.
 *
 * Responsibilities:
 *  - map between database rows and domain model [[EquipmentType]]
 *  - provide asynchronous methods to find, create, update and soft-delete equipment types
 *
 * All public methods attach `.recover` handlers that log unexpected database
 * errors and re-throw them so higher layers (controllers/services) can decide
 * how to convert failures into HTTP responses.
 *
 * @param dbConfigProvider Play's database config provider
 * @param ec ExecutionContext used to run asynchronous DB actions
 */
@Singleton
class EquipmentTypeRepository @Inject()(
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
   * Internal row representation for the equipment_types table.
   *
   * Mirrors DB schema and is used for Slick mapping.
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
   * Slick table mapping for equipment_types.
   */
  private class TypesTable(tag: Tag) extends Table[TypeRow](tag, "equipment_types") {
    def typeId     = column[Int]("type_id", O.PrimaryKey, O.AutoInc)
    def typeName   = column[String]("type_name")
    def description= column[Option[String]]("description")
    def isActive   = column[Boolean]("is_active")
    def createdAt  = column[Instant]("created_at")
    def updatedAt  = column[Instant]("updated_at")
    def isDeleted  = column[Boolean]("is_deleted")
    def deletedAt  = column[Option[Instant]]("deleted_at")

    def * =
      (typeId, typeName, description, isActive, createdAt, updatedAt, isDeleted, deletedAt)
        .mapTo[TypeRow]
  }

  private val types = TableQuery[TypesTable]

  /**
   * Convert DB row to domain model.
   */
  private def toModel(r: TypeRow): EquipmentType =
    EquipmentType(
      typeId     = r.typeId,
      typeName   = r.typeName,
      description= r.description,
      isActive   = r.isActive,
      createdAt  = r.createdAt,
      updatedAt  = r.updatedAt,
      isDeleted  = r.isDeleted,
      deletedAt  = r.deletedAt
    )

  /**
   * Find an equipment type by id (non-deleted).
   *
   * @param id type id
   * @return Future[Option[EquipmentType]]
   */
  def findById(id: Int): Future[Option[EquipmentType]] =
    db.run(types.filter(t => t.typeId === id && t.isDeleted === false).result.headOption)
      .map(_.map(toModel))
      .recover { case NonFatal(ex) =>
        logger.error(s"Error in findById(typeId=$id)", ex)
        throw ex
      }

  /**
   * Retrieve all non-deleted equipment types.
   *
   * @return Future[Seq[EquipmentType]]
   */
  def findAll(): Future[Seq[EquipmentType]] =
    db.run(types.filter(_.isDeleted === false).result)
      .map(_.map(toModel))
      .recover { case NonFatal(ex) =>
        logger.error("Error in findAll()", ex)
        throw ex
      }

  /**
   * Create a new equipment type and return generated id.
   *
   * @param et EquipmentType model (typeId ignored)
   * @return Future[Int] generated typeId
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

    db.run((types returning types.map(_.typeId)) += row).recover { case NonFatal(ex) =>
      logger.error(s"Error creating equipment type typeName=${et.typeName}", ex)
      throw ex
    }
  }

  /**
   * Update an existing equipment type.
   *
   * If the type does not exist or is deleted, returns 0.
   *
   * @param et EquipmentType with typeId set
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
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

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error updating equipment type typeId=${et.typeId}", ex)
      throw ex
    }
  }

  /**
   * Soft-delete an equipment type by marking `isDeleted = true` and setting `deletedAt`.
   *
   * @param id type id
   * @return Future[Int] number of rows updated (1 if success, 0 if not found)
   */
  def softDelete(id: Int): Future[Int] = {
    val now = Instant.now()
    val action = for {
      maybeExisting <- types.filter(t => t.typeId === id && t.isDeleted === false).result.headOption
      res <- maybeExisting match {
        case Some(existing) =>
          val updated = existing.copy(isDeleted = true, deletedAt = Some(now), updatedAt = now)
          types.filter(_.typeId === id).update(updated)
        case None =>
          DBIO.successful(0)
      }
    } yield res

    db.run(action.transactionally).recover { case NonFatal(ex) =>
      logger.error(s"Error in softDelete(typeId=$id)", ex)
      throw ex
    }
  }
}
