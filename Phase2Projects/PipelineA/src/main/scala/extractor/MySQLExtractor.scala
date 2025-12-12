package extractor

import config.{PipelineConfiguration => PC}
import org.apache.spark.sql.{DataFrame, SparkSession}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}

/**
 * MySQL data extractor for Spark-based CDC pipelines.
 * Handles offset management, partitioned transaction reads, and dimension lookups
 * for incremental data processing between MySQL and Cassandra Keyspaces.
 */
object MySQLExtractor {
  private val mysql = PC.mysql

  /** Common JDBC options for all Spark reads with optimized cursor fetch. */
  private val commonJdbcOptions = Map(
    "url" -> mysql.jdbcUrl,
    "user" -> mysql.user,
    "password" -> mysql.password,
    "fetchsize" -> mysql.fetchSize.toString,
    "useCursorFetch" -> "true",
    "driver" -> "com.mysql.cj.jdbc.Driver"
  )

  /** Fully qualified table name with database escaping for MySQL. */
  private def fqTable(table: String): String = s"`${mysql.database}`.$table"

  /**
   * Executes JDBC operations with automatic connection management.
   * Intended for offset table operations requiring direct SQL access.
   *
   * @param f Connection callback function
   * @tparam T Return type of the callback
   * @return Result of callback execution
   */
  def withConn[T](f: Connection => T): T = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(mysql.jdbcUrl, mysql.user, mysql.password)
      f(conn)
    } finally if (conn != null) conn.close()
  }

  /**
   * Creates offset table if missing with primary key on source identifier.
   * Stores last processed transaction ID for incremental CDC reads.
   */
  def ensureOffsetTable(conn: Connection, offsetTableName: String): Unit = {
    val fq = fqTable(offsetTableName)
    val stmt = conn.createStatement()
    try {
      stmt.execute(
        s"""
           |CREATE TABLE IF NOT EXISTS $fq (
           |  source VARCHAR(128) PRIMARY KEY,
           |  last_txn_id BIGINT NOT NULL
           |)
           |""".stripMargin)
    } finally stmt.close()
  }

  /**
   * Reads last processed transaction ID for specific pipeline source.
   * Returns 0L if no offset exists (start from beginning).
   */
  def readLastOffset(conn: Connection, offsetTableName: String, offsetSourceId: String): Long = {
    val fq = fqTable(offsetTableName)
    var ps: PreparedStatement = null
    var rs: ResultSet = null
    try {
      ps = conn.prepareStatement(s"SELECT last_txn_id FROM $fq WHERE source = ?")
      ps.setString(1, offsetSourceId)
      rs = ps.executeQuery()
      if (rs.next()) rs.getLong("last_txn_id") else 0L
    } finally {
      if (rs != null) rs.close()
      if (ps != null) ps.close()
    }
  }

  /**
   * Writes new offset using UPSERT with direct connection.
   * Returns success status for monitoring/alerting.
   */
  def writeOffsetDirect(newOffset: Long, offsetTableName: String, offsetSourceId: String): Boolean = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    var conn: Connection = null
    var ps: PreparedStatement = null
    try {
      conn = DriverManager.getConnection(mysql.jdbcUrl, mysql.user, mysql.password)
      if (!conn.getAutoCommit) conn.setAutoCommit(true)
      val fq = fqTable(offsetTableName)
      ps = conn.prepareStatement(
        s"INSERT INTO $fq (source, last_txn_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE last_txn_id = VALUES(last_txn_id)"
      )
      ps.setString(1, offsetSourceId)
      ps.setLong(2, newOffset)
      ps.executeUpdate()
      true
    } catch {
      case _: Throwable => false
    } finally {
      if (ps != null) ps.close()
      if (conn != null) conn.close()
    }
  }

  /**
   * Writes offset with exponential backoff retries for high-contention scenarios.
   * Uses existing connection to avoid connection pool exhaustion.
   *
   * @param maxRetries Maximum retry attempts (default 3)
   */
  def writeOffsetWithRetries(conn: Connection, offsetTableName: String, offsetSourceId: String,
                             offset: Long, maxRetries: Int = 3): Boolean = {
    val fq = fqTable(offsetTableName)
    var attempt = 0
    while (attempt < maxRetries) {
      attempt += 1
      var ps: PreparedStatement = null
      try {
        ps = conn.prepareStatement(
          s"INSERT INTO $fq (source, last_txn_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE last_txn_id = VALUES(last_txn_id)"
        )
        ps.setString(1, offsetSourceId)
        ps.setLong(2, offset)
        ps.executeUpdate()
        return true
      } catch {
        case _: Throwable =>
          if (attempt >= maxRetries) return false
          try Thread.sleep(200L * attempt) catch { case _: Throwable => }
      } finally if (ps != null) ps.close()
    }
    false
  }

  /**
   * Calculates optimal JDBC partition count based on transaction ID range.
   * Balances parallelism vs overhead using ~10K rows per partition heuristic.
   */
  def choosePartitions(minId: Long, maxId: Long, cfg: Int): Int = {
    if (minId >= maxId) 1
    else {
      val span = maxId - minId + 1
      val approxPerPartition = 10000L
      val p = Math.max(2, Math.min(cfg, (span / approxPerPartition + 1).toInt))
      p
    }
  }

  /**
   * Loads products dimension table for join operations.
   * Caches small reference data to avoid repeated network roundtrips.
   */
  def loadProductsDF(spark: SparkSession): DataFrame = {
    spark.read
      .format("jdbc")
      .options(commonJdbcOptions)
      .option("dbtable", "products")
      .load()
      .select("product_id", "category")
      .cache()
  }

  /**
   * Reads incremental transactions using JDBC partitioning on txn_id.
   * Enables parallel Spark reads for CDC delta processing.
   */
  def readDeltaTransactions(spark: SparkSession, mn: Long, mx: Long, numPartitions: Int): DataFrame = {
    spark.read.format("jdbc")
      .options(commonJdbcOptions)
      .option("dbtable", "transactions")
      .option("partitionColumn", "txn_id")
      .option("lowerBound", mn.toString)
      .option("upperBound", mx.toString)
      .option("numPartitions", numPartitions.toString)
      .load()
      .select("txn_id", "customer_id", "product_id", "qty", "amount", "txn_timestamp")
  }

  /**
   * Loads customers by ID list using IN clause subquery.
   * Optimized for small-to-medium affected customer sets (<1000 IDs).
   */
  def readCustomersByIds(spark: SparkSession, ids: Array[Int]): DataFrame = {
    val idList = ids.mkString(",")
    spark.read.format("jdbc")
      .options(commonJdbcOptions)
      .option("dbtable", s"(SELECT customer_id, name, email, gender FROM customers WHERE customer_id IN ($idList)) AS cs")
      .load()
      .cache()
  }

  /**
   * Fallback customer lookup: read all customers and inner join with affected set.
   * Used when IN clause exceeds SQL limits or for very small affected sets.
   */
  def readAllCustomersAndJoinAffected(spark: SparkSession, affectedCustomersDF: DataFrame): DataFrame = {
    val allCustomers = spark.read.format("jdbc")
      .options(commonJdbcOptions)
      .option("dbtable", "customers")
      .load()
      .select("customer_id", "name", "email", "gender")
      .join(affectedCustomersDF, Seq("customer_id"), "inner")
      .cache()
    allCustomers
  }
}
