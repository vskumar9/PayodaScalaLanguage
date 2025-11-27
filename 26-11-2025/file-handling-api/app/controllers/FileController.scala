package controllers

import javax.inject._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import services.FileService
import play.api.libs.Files.TemporaryFile
import play.api.Logger
import play.api.libs.json.{Json, JsObject}
import play.api.mvc.MultipartFormData.FilePart
import java.io.File
@Singleton
class FileController @Inject()(
                                cc: ControllerComponents,
                                fileService: FileService
                              )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(this.getClass)

  def upload: Action[MultipartFormData[TemporaryFile]] = Action.async(parse.multipartFormData) { request =>
    val fileParts: Seq[MultipartFormData.FilePart[TemporaryFile]] = request.body.files

    if (fileParts.isEmpty) {
      Future.successful(BadRequest(Json.obj("error" -> "No files provided in 'file' field")))
    } else {
      val saves: Seq[Future[JsObject]] = fileParts.map { tf =>
        val javaFile: File = tf.ref.path.toFile
        val fp: FilePart[File] = FilePart(tf.key, tf.filename, tf.contentType, javaFile)

        fileService.saveUploadedFile(fp).map { id =>
          val contentTypeStr = tf.contentType.getOrElse("unknown")
          Json.obj(
            "originalName" -> tf.filename,
            "contentType"  -> contentTypeStr,
            "id"           -> id
          )
        }.recover { case ex =>
          val contentTypeStr = tf.contentType.getOrElse("unknown")
          val errMsg = Option(ex.getMessage).getOrElse("save failed")
          Json.obj(
            "originalName" -> tf.filename,
            "contentType"  -> contentTypeStr,
            "error"        -> errMsg
          )
        }
      }

      Future.sequence(saves).map { results =>
        Created(Json.obj("files" -> results))
      }.recover { case ex =>
        logger.error("batch upload failed", ex)
        InternalServerError(Json.obj("error" -> "batch upload failed"))
      }
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
