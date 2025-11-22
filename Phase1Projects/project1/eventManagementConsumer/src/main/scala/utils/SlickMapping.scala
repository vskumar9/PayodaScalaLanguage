package utils

import slick.jdbc.MySQLProfile.api._

import java.sql.Timestamp
import java.time.Instant

trait SlickMapping {
  implicit val instantColumnType: BaseColumnType[Instant] =
    MappedColumnType.base[Instant, Timestamp](
      i => Timestamp.from(i),
      ts => ts.toInstant
    )
}
