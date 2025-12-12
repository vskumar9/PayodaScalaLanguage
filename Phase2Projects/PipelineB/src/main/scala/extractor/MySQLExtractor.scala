package extractor

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import config.PipelineConfiguration
import org.apache.spark.sql.{DataFrame, SparkSession}
import util.Logging

/**
 * Encapsulates common JDBC connection options for a MySQL database.
 *
 * @param url The full JDBC connection URL to the MySQL database.
 * @param user The username used for database authentication.
 * @param password The password for the given user.
 * @param fetchSize The number of rows fetched per batch in a query (performance tuning).
 */
case class JdbcOptions(
                        url: String,
                        user: String,
                        password: String,
                        fetchSize: Int
                      )

/**
 * Provides functionality to extract data from a MySQL database into Spark DataFrames.
 *
 * This extractor handles both incremental (delta-based) and full data reads,
 * as well as offset management for tracking the last processed transaction.
 * It interacts directly with JDBC and integrates smoothly with Spark for ETL operations.
 *
 * Responsibilities:
 *  - Ensure offset tracking table exists.
 *  - Read/write last processed offsets.
 *  - Load transactional and product data from MySQL using Spark JDBC.
 *
 * @param spark The active [[SparkSession]] used for reading data via JDBC.
 */
class MySQLExtractor(spark: SparkSession) extends Logging {
  import PipelineConfiguration._

  // Load MySQL configuration parameters
  private val mysql = PipelineConfiguration.mysql

  /** The complete JDBC connection URL for the configured MySQL database. */
  val jdbcUrl: String = s"jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}"

  /** Common JDBC options passed to Spark when reading data. */
  val commonOpts: Map[String, String] = Map(
    "url" -> jdbcUrl,
    "user" -> mysql.user,
    "password" -> mysql.password,
    "fetchsize" -> mysql.fetchSize.toString
  )

  logger.info("MySQLExtractor initialized")

  /**
   * Ensures the offset tracking table exists in MySQL.
   *
   * The offset table stores the last processed transaction ID for each source,
   * allowing incremental (delta) extraction between pipeline runs.
   *
   * @param conn An active MySQL [[Connection]].
   * @param offsetTableName The name of the offset table to verify or create.
   */
  def ensureOffsetTable(conn: Connection, offsetTableName: String): Unit = {
    logger.debug(s"Ensuring offset table exists: $offsetTableName")
    val stmt = conn.createStatement()
    try {
      stmt.execute(
        s"""
           |CREATE TABLE IF NOT EXISTS $offsetTableName (
           |  source       VARCHAR(128) PRIMARY KEY,
           |  last_txn_id  BIGINT NOT NULL
           |)
           |""".stripMargin
      )
    } finally {
      try stmt.close() catch { case _: Throwable => }
    }
  }

  /**
   * Reads the last processed transaction offset for a given data source.
   *
   * @param conn An active MySQL [[Connection]].
   * @param offsetTableName The name of the offset tracking table.
   * @param sourceId The unique identifier of the data source.
   * @return The last processed transaction ID (offset), or 0 if no previous record exists.
   */
  def readLastOffset(conn: Connection, offsetTableName: String, sourceId: String): Long = {
    logger.debug(s"Reading last offset for $sourceId from $offsetTableName")
    var ps: PreparedStatement = null
    var rs: ResultSet = null
    try {
      ps = conn.prepareStatement(s"SELECT last_txn_id FROM $offsetTableName WHERE source = ?")
      ps.setString(1, sourceId)
      rs = ps.executeQuery()
      if (rs.next()) rs.getLong("last_txn_id") else 0L
    } finally {
      if (rs != null) try rs.close() catch { case _: Throwable => }
      if (ps != null) try ps.close() catch { case _: Throwable => }
    }
  }

  /**
   * Updates or inserts the latest processed transaction offset for a specific data source.
   *
   * @param conn An active MySQL [[Connection]].
   * @param offsetTableName The name of the offset tracking table.
   * @param sourceId The unique identifier of the data source.
   * @param newOffset The latest transaction ID to record.
   */
  def writeLastOffset(conn: Connection, offsetTableName: String, sourceId: String, newOffset: Long): Unit = {
    logger.info(s"Writing offset $newOffset for $sourceId into $offsetTableName")
    var ps: PreparedStatement = null
    try {
      ps = conn.prepareStatement(
        s"""
           |INSERT INTO $offsetTableName (source, last_txn_id)
           |VALUES (?, ?)
           |ON DUPLICATE KEY UPDATE last_txn_id = VALUES(last_txn_id)
           |""".stripMargin
      )
      ps.setString(1, sourceId)
      ps.setLong(2, newOffset)
      ps.executeUpdate()
    } finally {
      if (ps != null) try ps.close() catch { case _: Throwable => }
    }
  }

  /**
   * Reads only transactions newer than a given offset (delta extraction).
   *
   * @param lastOffset The last processed transaction ID.
   * @return A Spark [[DataFrame]] containing all new transactions with higher IDs.
   */
  def readDeltaTransactions(lastOffset: Long): DataFrame = {
    logger.info(s"Reading delta transactions > $lastOffset")
    val q =
      s"(SELECT txn_id, customer_id, product_id, qty, amount, txn_timestamp FROM transactions WHERE txn_id > $lastOffset ORDER BY txn_id) AS tx_delta"
    spark.read.format("jdbc").options(commonOpts).option("dbtable", q).load()
  }

  /**
   * Reads full transaction records for a specific set of dates.
   *
   * @param dates A sequence of date strings (YYYY-MM-DD) used for filtering transactions.
   * @return A Spark [[DataFrame]] containing transactions for the given dates.
   */
  def readTransactionsForDates(dates: Seq[String]): DataFrame = {
    logger.info(s"Reading transactions for dates: $dates")
    val dateIn = dates.map(d => s"'$d'").mkString(",")
    val q =
      s"(SELECT txn_id, customer_id, product_id, qty, amount, txn_timestamp FROM transactions WHERE DATE(txn_timestamp) IN ($dateIn)) AS tx_full"
    spark.read.format("jdbc").options(commonOpts).option("dbtable", q).load()
  }

  /**
   * Reads all product records from the `products` table.
   *
   * @return A Spark [[DataFrame]] representing the contents of the `products` table.
   */
  def readProducts(): DataFrame = {
    spark.read.format("jdbc").options(commonOpts).option("dbtable", "products").load()
  }
}
