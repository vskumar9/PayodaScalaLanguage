package repositories

import scala.jdk.CollectionConverters._
import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.servererrors._
import com.datastax.oss.driver.api.core.DriverTimeoutException
import play.api.libs.json._
import play.api.Logger
import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for Amazon Keyspaces (Cassandra) customer profile data.
 *
 * Provides reactive access to `customer_profile` table with prepared statement caching
 * and comprehensive error handling for common Cassandra timeouts/unavailability.
 *
 * Converts Cassandra Rows to Play JSON dynamically based on column definitions.
 */
class CassandraRepository @Inject() (session: CqlSession)(implicit ec: ExecutionContext) {

  /** Logger for query execution and error reporting. */
  private val logger = Logger(this.getClass)

  // -----------------------------------------
  // Prepared Statements (cached)
  // -----------------------------------------
  /**
   * Cached prepared statement for customer profile lookup.
   * Improves performance by avoiding repeated statement parsing.
   */
  private val selectProfileStmt: PreparedStatement =
    session.prepare("SELECT * FROM customer_profile WHERE customer_id = ?")

  // -----------------------------------------
  // Public API: get customer profile row
  // -----------------------------------------
  /**
   * Retrieves customer profile row by ID.
   *
   * Validates customerId fits in Cassandra INT32 column range before querying.
   *
   * @param customerId Customer identifier (must fit in signed 32-bit int)
   * @return Future[Option[Row]] containing the profile row or None if not found/failed
   */
  def getProfile(customerId: Long): Future[Option[Row]] = {
    // Convert Scala Long to Int matching Cassandra column type `int`
    val idAsInt: Int =
      if (customerId.isValidInt) customerId.toInt
      else throw new IllegalArgumentException(s"customerId out of int range: $customerId")

    val bound = selectProfileStmt.bind(Int.box(idAsInt))

    Future(session.execute(bound))
      .map(rs => Option(rs.one()))
      .recoverWith(handleCassandraErrors("getProfile", customerId))
  }

  // -----------------------------------------
  // Convert Cassandra Row → JsObject (Reusable)
  // -----------------------------------------
  /**
   * Dynamically converts a Cassandra Row to Play JSON based on column definitions.
   *
   * Handles all common Cassandra types: primitives, collections (List/Map),
   * timestamps, decimals, etc. Falls back to string conversion for unsupported types.
   *
   * @param row Cassandra result row
   * @return JsObject representation of all non-null columns
   */
  def rowToJson(row: Row): JsObject = {
    val fields = row.getColumnDefinitions.asScala.flatMap { col =>
      val colName = col.getName.asInternal()
      val value: AnyRef = row.getObject(colName)

      val jsValue: JsValue =
        value match {
          case null                     => JsNull
          case b: java.lang.Boolean     => JsBoolean(b)
          case i: java.lang.Integer     => JsNumber(BigDecimal(i.intValue()))
          case l: java.lang.Long        => JsNumber(BigDecimal(l.longValue()))
          case d: java.lang.Double      => JsNumber(BigDecimal(d.doubleValue()))
          case f: java.lang.Float       => JsNumber(BigDecimal.decimal(f))
          case bi: java.math.BigInteger => JsNumber(BigDecimal(bi))
          case bd: java.math.BigDecimal => JsNumber(BigDecimal(bd))
          case s: String                => JsString(s)
          case inst: java.time.Instant  => JsString(inst.toString)

          // Convert Java List -> Scala collection of JsValues
          case list: java.util.List[_] =>
            val scalaList = list.asScala.toSeq.map {
              case null        => JsNull
              case n: Number   => JsNumber(BigDecimal(n.toString))
              case b: Boolean  => JsBoolean(b)
              case x           => JsString(x.toString)
            }
            JsArray(scalaList)

          // Convert Java Map -> JsObject[String, JsValue]
          case map: java.util.Map[_, _] =>
            val scalaMap = map.asScala.map {
              case (k, v) =>
                val jsV: JsValue = v match {
                  case null        => JsNull
                  case n: Number   => JsNumber(BigDecimal(n.toString))
                  case b: Boolean  => JsBoolean(b)
                  case x           => JsString(x.toString)
                }
                k.toString -> jsV
            }.toMap
            JsObject(scalaMap)

          case other => JsString(other.toString)
        }

      Some(colName -> jsValue)
    }

    JsObject(fields.toSeq)
  }

  // -----------------------------------------
  // Error Handling & Standardized Logging
  // -----------------------------------------
  /**
   * Standardized Cassandra error handler that converts exceptions to None results.
   *
   * Logs specific timeout/unavailable errors with context, returns None for all failures.
   * Ensures service remains available during transient Cassandra issues.
   *
   * @param op        Operation name for logging
   * @param customerId Context for error logging
   * @return PartialFunction that maps Throwable → Future[Option[Row]]
   */
  private def handleCassandraErrors(op: String, customerId: Long): PartialFunction[Throwable, Future[Option[Row]]] =
    new PartialFunction[Throwable, Future[Option[Row]]] {

      override def isDefinedAt(x: Throwable): Boolean = true

      override def apply(err: Throwable): Future[Option[Row]] = {
        err match {
          case _: ReadTimeoutException =>
            logger.error(s"Cassandra READ TIMEOUT during $op for customerId=$customerId")
            Future.successful(None)

          case _: WriteTimeoutException =>
            logger.error(s"Cassandra WRITE TIMEOUT during $op for customerId=$customerId")
            Future.successful(None)

          case _: UnavailableException =>
            logger.error(s"Cassandra UNAVAILABLE during $op for customerId=$customerId")
            Future.successful(None)

          case _: DriverTimeoutException =>
            logger.error(s"Cassandra DRIVER TIMEOUT during $op for customerId=$customerId")
            Future.successful(None)

          case ex =>
            logger.error(s"Cassandra unknown error during $op for customerId=$customerId : ${ex.getMessage}", ex)
            Future.successful(None)
        }
      }
    }
}
