package services

import java.nio.file.{Files, Paths, StandardCopyOption}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import play.api.Configuration
import play.api.mvc.MultipartFormData.FilePart
import repositories.FileMetaRepository
import models.FileMeta
import java.time.Instant


@Singleton
class FileService @Inject()(
                             config: Configuration,
                             repo: FileMetaRepository
                           )(implicit ec: ExecutionContext) {


  private val baseDir = Paths.get(config.get[String]("file.upload.dir"))
  if (!Files.exists(baseDir)) Files.createDirectories(baseDir)


  /** Save uploaded file to disk and metadata to DB. Returns generated id. */
  def saveUploadedFile(filePart: FilePart[java.io.File]): Future[Long] = Future {
    val original = filePart.filename
    val contentType = filePart.contentType
    val size = filePart.ref.length()
    val dotIdx = original.lastIndexOf('.')
    val uuid = UUID.randomUUID().toString
    val storedName = if (dotIdx > 0) {
      val name = original.substring(0, dotIdx)
      val ext = original.substring(dotIdx)
      s"${name}_${uuid}${ext}"
    } else s"${original}_${uuid}"


    val target = baseDir.resolve(storedName)
    Files.copy(filePart.ref.toPath, target, StandardCopyOption.REPLACE_EXISTING)


    val relativePath = baseDir.relativize(target).toString // storedName essentially
    val meta = FileMeta(
      originalName = original,
      storedName = storedName,
      relativePath = relativePath,
      contentType = contentType,
      size = size,
      createdAt = Instant.now()
    )


    // Insert metadata (blocking inside Future but DB call should be async; we'll call repo.create after)
    // Return to caller the created id by chaining a DB future.
    meta
  }.flatMap { meta =>
    repo.create(meta)
  }


  def getFileMeta(id: Long): Future[Option[FileMeta]] = repo.findById(id)


  def resolvePath(meta: FileMeta): java.nio.file.Path = baseDir.resolve(meta.storedName)
}