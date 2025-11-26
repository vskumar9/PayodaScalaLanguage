package controllers

import javax.inject._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import services.FileService
import play.api.libs.Files.TemporaryFile
import play.api.Logger
import play.api.libs.json.Json

@Singleton
class FileController @Inject()(
                                cc: ControllerComponents,
                                fileService: FileService
                              )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(this.getClass)

  def upload: Action[MultipartFormData[TemporaryFile]] = Action.async(parse.multipartFormData) { request =>
    request.body.file("file") match {
      case Some(filePart) =>
        // Convert Play's TemporaryFile to java.io.File before passing to the service
        val javaFile = filePart.ref.path.toFile
        val fp = play.api.mvc.MultipartFormData.FilePart(
          filePart.key,
          filePart.filename,
          filePart.contentType,
          javaFile
        )
        fileService.saveUploadedFile(fp).map { id =>
          Created(Json.obj("id" -> id))
        }.recover { case e =>
          logger.error("upload failed", e)
          InternalServerError(Json.obj("error" -> "upload failed"))
        }

      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Missing file field 'file'")))
    }
  }

  def download(id: Long): Action[AnyContent] = Action.async { request =>
    fileService.getFileMeta(id).map {
      case Some(meta) =>
        val path = fileService.resolvePath(meta)
        val file = path.toFile
        if (file.exists()) {
          Ok.sendFile(content = file, fileName = _ => Some(meta.originalName))
        } else NotFound(Json.obj("error" -> "file not found on disk"))

      case None => NotFound(Json.obj("error" -> "file metadata not found"))
    }
  }
}
