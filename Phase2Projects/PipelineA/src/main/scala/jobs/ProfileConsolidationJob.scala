package jobs

import config.PipelineConfiguration
import util.{Logging, SparkSessionFactory}
import extractor.MySQLExtractor
import transformer.DataTransformer
import loader.CassandraLoader

import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

/**
 * Incremental customer profile consolidation pipeline.
 * Uses Spark Structured Streaming with rate source trigger to periodically:
 * 1. Read new transactions since last offset
 * 2. Identify affected customers
 * 3. Compute consolidated profiles using all their transactions
 * 4. Write profiles to Cassandra Keyspaces
 * 5. Advance processing offset
 */
object ProfileConsolidationJob extends App with Logging {

  /** Centralized pipeline configuration. */
  val pc = PipelineConfiguration

  /** Application-specific settings for this pipeline instance. */
  val confApp = pc.app

  /** Local Spark session for incremental processing only. */
  val spark = SparkSessionFactory.createLocalSession("PipelineA")
  import spark.implicits._

  /**
   * Preloads and caches products dimension table.
   * Small reference data reused across all micro-batches for join efficiency.
   */
  val productsDF = MySQLExtractor.loadProductsDF(spark)
  logger.info("Products DF cached successfully.")

  /**
   * Executes single incremental batch processing.
   * Implements CDC pattern: read delta → process affected → write → commit offset.
   *
   * @param batchId Unique identifier from Spark foreachBatch
   */
  def runIncrementalBatch(batchId: Long): Unit = {
    logger.info(s"Starting incremental batch #$batchId")

    MySQLExtractor.withConn { conn =>
      /** Ensure offset tracking table exists. */
      MySQLExtractor.ensureOffsetTable(conn, confApp.offsetTable)

      /** Read last successfully processed transaction ID. */
      val lastOffset = MySQLExtractor.readLastOffset(conn, confApp.offsetTable, confApp.offsetSourceId)
      logger.info(s"Last processed txn offset = $lastOffset")

      /**
       * Discover new transaction range and count since last offset.
       * Uses single metadata query to avoid full table scan.
       */
      val (mn, mx, cnt) = {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(
          s"SELECT COALESCE(MIN(txn_id),0) AS mn, COALESCE(MAX(txn_id),0) AS mx, COUNT(*) AS cnt FROM transactions WHERE txn_id > $lastOffset"
        )
        var mn0 = 0L; var mx0 = 0L; var cnt0 = 0L
        if (rs.next()) {
          mn0 = rs.getLong("mn")
          mx0 = rs.getLong("mx")
          cnt0 = rs.getLong("cnt")
        }
        rs.close(); stmt.close()
        (mn0, mx0, cnt0)
      }

      /** Early return when no new data available. */
      if (cnt == 0L) {
        logger.info(s"No new transactions since offset $lastOffset. Skipping batch.")
        return
      }

      logger.info(s"New transactions found: count=$cnt range=[$mn,$mx]")

      /**
       * Calculate and cap JDBC partitions for parallel delta read.
       * Balances parallelism with connection limits.
       */
      val npRaw = MySQLExtractor.choosePartitions(mn, mx, confApp.jdbcNumPartitions)
      val np = Math.max(1, Math.min(npRaw, confApp.jdbcMaxConnections))
      logger.info(s"Using $np JDBC partitions for delta read (raw=$npRaw).")

      /**
       * Read only new transactions using JDBC partitioning on txn_id.
       * Additional Spark filter ensures exact offset boundary.
       */
      val deltaDF = MySQLExtractor
        .readDeltaTransactions(spark, mn, mx, np)
        .filter($"txn_id" > lit(lastOffset))
        .cache()

      /**
       * Identify unique customers affected by new transactions.
       * Cached for reuse in multiple downstream operations.
       */
      val affectedCustomersDF = deltaDF.select("customer_id").distinct().cache()
      val affectedCount = affectedCustomersDF.count().toInt
      logger.info(s"Affected customers = $affectedCount")

      /**
       * Adaptive customer lookup strategy:
       * - Small sets: IN-list subquery (fast, low memory)
       * - Large sets: Full table read + join (avoids SQL limits)
       */
      val customersSubDF =
        if (affectedCount <= confApp.maxInListForSql) {
          logger.info(s"Reading customers via IN-list optimization...")
          MySQLExtractor.readCustomersByIds(spark, affectedCustomersDF.as[Int].collect())
        } else {
          logger.info(s"Large affected set → reading full customer table then joining.")
          MySQLExtractor.readAllCustomersAndJoinAffected(spark, affectedCustomersDF)
        }

      /**
       * Adaptive transaction history read for affected customers:
       * - Small sets: IN-list on customer_id (precise, efficient)
       * - Large sets: Full partitioned transaction read + customer join
       */
      val txnsForAffectedDF =
        if (affectedCount <= confApp.maxInListForSql) {
          logger.info("Reading all transactions for affected customers (IN-list).")
          val ids = affectedCustomersDF.as[Int].collect().mkString(",")
          spark.read.format("jdbc")
            .options(Map(
              "url" -> pc.mysql.jdbcUrl,
              "user" -> pc.mysql.user,
              "password" -> pc.mysql.password,
              "driver" -> "com.mysql.cj.jdbc.Driver"
            ))
            .option("dbtable",
              s"(SELECT txn_id, customer_id, product_id, qty, amount, txn_timestamp FROM transactions WHERE customer_id IN ($ids)) AS taff"
            )
            .load()
        } else {
          logger.info("Joining all transactions with affected customers...")
          /** Get full transaction ID range for partitioning. */
          val stmt = conn.createStatement()
          val rs = stmt.executeQuery("SELECT COALESCE(MIN(txn_id),0) as mn, COALESCE(MAX(txn_id),0) as mx FROM transactions")
          var mn2 = 0L; var mx2 = 0L
          if (rs.next()) { mn2 = rs.getLong("mn"); mx2 = rs.getLong("mx") }
          rs.close(); stmt.close()

          val npAllRaw = MySQLExtractor.choosePartitions(mn2, mx2, confApp.jdbcNumPartitions)
          val npAll = Math.max(1, Math.min(npAllRaw, confApp.jdbcMaxConnections))

          logger.info(s"Reading entire transaction table with $npAll partitions.")

          spark.read.format("jdbc")
            .options(Map(
              "url" -> pc.mysql.jdbcUrl,
              "user" -> pc.mysql.user,
              "password" -> pc.mysql.password,
              "driver" -> "com.mysql.cj.jdbc.Driver"
            ))
            .option("dbtable", "transactions")
            .option("partitionColumn", "txn_id")
            .option("lowerBound", mn2.toString)
            .option("upperBound", mx2.toString)
            .option("numPartitions", npAll.toString)
            .load()
            .select("txn_id","customer_id","product_id","qty","amount","txn_timestamp")
            .join(customersSubDF.select("customer_id"), Seq("customer_id"), "inner")
        }

      txnsForAffectedDF.cache()

      /**
       * Compute consolidated customer profiles from complete transaction history.
       * Includes aggregation parameters tuned for this workload size.
       */
      logger.info("Computing profiles for affected customers...")
      val profilesDF = DataTransformer.computeProfilesForCustomers(
        txnsForAffectedDF, customersSubDF, productsDF,
        pc.app.writeParallelism, affectedCount
      )

      /**
       * Atomic write to Cassandra with success/failure tracking.
       * Offset advancement gated on successful profile write.
       */
      logger.info(s"Writing profiles to Cassandra...")
      val cassOk = CassandraLoader.writeProfiles(profilesDF)

      /**
       * Two-phase offset commit with fallback retry.
       * Only advances on Cassandra success (or debug mode).
       */
      if (cassOk || pc.app.debugForceOffsetWrite) {
        val maybeRow = deltaDF.agg(max("txn_id")).collect().headOption
        maybeRow match {
          case Some(row) if !row.isNullAt(0) =>
            val newMax = row.getLong(0)
            logger.info(s"Attempting to advance offset to $newMax")

            /** Primary offset write attempt (direct connection). */
            val okDirect = MySQLExtractor.writeOffsetDirect(newMax, confApp.offsetTable, confApp.offsetSourceId)
            if (!okDirect) {
              logger.warn("Direct offset write failed. Trying fallback write (JDBC connection).")
              /** Retry with exponential backoff using existing connection. */
              val fallbackOk = MySQLExtractor.writeOffsetWithRetries(
                conn,
                confApp.offsetTable,
                confApp.offsetSourceId,
                newMax
              )
              if (!fallbackOk)
                logger.error("Both direct and fallback offset writes failed.")
            }

          case _ =>
            logger.error("Failed to compute newMax → skipping offset advance.")
        }
      } else {
        logger.warn("Skipping offset write because Cassandra write failed and debugForceOffsetWrite=false")
      }

      /**
       * Explicit cache cleanup to control memory pressure across batches.
       * Blocking unpersist ensures resources released before next batch.
       */
      deltaDF.unpersist(false)
      affectedCustomersDF.unpersist(false)
      customersSubDF.unpersist(false)
      txnsForAffectedDF.unpersist(false)
      profilesDF.unpersist(false)

      logger.info(s"Batch #$batchId completed.")
    }
  }

  /**
   * Rate source generating periodic trigger events.
   * RowsPerSecond=1 ensures exactly one batch per trigger interval.
   */
  val ticks = spark.readStream.format("rate").option("rowsPerSecond", 1).load()

  /**
   * Streaming query driving the incremental pipeline.
   * Uses foreachBatch to execute custom batch logic on each trigger.
   */
  val q = ticks.writeStream
    .trigger(Trigger.ProcessingTime(s"${confApp.pollIntervalSec} seconds"))
    .foreachBatch { (_: DataFrame, batchId: Long) =>
      try runIncrementalBatch(batchId)
      catch { case t: Throwable => logger.error("Batch failed", t) }
    }
    .outputMode("update")
    .start()

  /** Startup confirmation and blocking termination wait. */
  logger.info(s"PipelineA incremental updater started. pollIntervalSec=${confApp.pollIntervalSec}")
  q.awaitTermination()
}
