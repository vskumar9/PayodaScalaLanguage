package repositories

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import models.FileMeta

import java.sql.Timestamp
import java.time.Instant
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.{JdbcProfile, MySQLProfile}

@Singleton
class FileMetaRepository @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)
                                  (implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MySQLProfile]{

  import profile.api._

  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )

  private class FileMetaTable(tag: Tag) extends Table[FileMeta](tag, "file_meta") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def originalName = column[String]("original_name")
    def storedName = column[String]("stored_name")
    def relativePath = column[String]("relative_path")
    def contentType = column[Option[String]]("content_type")
    def size = column[Long]("size")
    // now this column is of type Instant, mapped via the implicit above
    def createdAt = column[Instant]("created_at")

    def * = (id, originalName, storedName, relativePath, contentType, size, createdAt) <> (FileMeta.tupled, FileMeta.unapply)
  }

  private val fileMetas = TableQuery[FileMetaTable]

  def init(): Future[Unit] = db.run(fileMetas.schema.createIfNotExists)

  def create(meta: FileMeta): Future[Long] =
    db.run((fileMetas returning fileMetas.map(_.id)) += meta)

  def findById(id: Long): Future[Option[FileMeta]] =
    db.run(fileMetas.filter(_.id === id).result.headOption)
}
