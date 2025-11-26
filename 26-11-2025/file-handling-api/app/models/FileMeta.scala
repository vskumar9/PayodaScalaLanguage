package models

import java.time.Instant

case class FileMeta(
                     id: Long = 0L,
                     originalName: String,
                     storedName: String,
                     relativePath: String,
                     contentType: Option[String],
                     size: Long,
                     createdAt: Instant,
                   )
