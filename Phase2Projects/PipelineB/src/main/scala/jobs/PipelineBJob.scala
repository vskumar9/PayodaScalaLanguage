package jobs

import config.PipelineConfiguration
import util.{SparkSessionFactory, Logging}
import extractor.MySQLExtractor
import transformer.DataTransformer
import loader.S3Loader

import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.streaming.Trigger

import java.sql.DriverManager

/**
 * Incremental daily transaction summary pipeline using Spark Structured Streaming.
 *
 * This job:
 *  - Periodically reads new transactions from MySQL based on a monotonic txn_id offset.
 *  - Recomputes daily summaries for only the affected dates.
 *  - Writes the summaries to S3.
 *  - Persists the latest processed offset back to MySQL for the next run.
 *
 * The scheduling is implemented with a rate-based Structured Streaming source and
 * `foreachBatch`, which triggers the incremental batch logic at a fixed interval.
 */
object PipelineBJob extends App with Logging {

  logger.info("==== Starting Pipeline B - Incremental Daily Txn Summary ====")

  /**
   * Implicit SparkSession used throughout the pipeline for DataFrame operations
   * and the Structured Streaming scheduler.
   */
  implicit val spark: SparkSession =
    SparkSessionFactory.create("Pipeline B - Incremental Daily Txn Summary")

  import spark.implicits._

  /** Central application configuration, including MySQL and app-specific settings. */
  val cfg = PipelineConfiguration
  /** MySQL connection configuration (host, port, db, credentials, fetch size). */
  val mysql = cfg.mysql
  /** Application-level configuration (paths, poll interval, offset table, source id). */
  val app = cfg.app

  /** Extractor for reading transactions and products from MySQL. */
  val extractor = new MySQLExtractor(spark)
  /** Loader responsible for writing summarized data into S3. */
  val loader = new S3Loader(app.lakeTxnSummaryBasePath)

  /**
   * Executes one incremental batch of the pipeline.
   *
   * Responsibilities per batch:
   *  - Open a JDBC connection and ensure the offset table exists.
   *  - Read the last processed txn_id for the configured source.
   *  - Load only new transactions (delta) from MySQL.
   *  - Determine affected calendar dates and recompute full summaries for them.
   *  - Write the daily summary to S3.
   *  - Update the stored offset inside a transaction for exactly-once semantics
   *    with respect to txn_id.
   *
   * @param batchId The Structured Streaming micro-batch identifier passed by `foreachBatch`.
   */
  def runIncrementalBatch(batchId: Long): Unit = {
    logger.info(s"---------- Running Batch $batchId ----------")

    var conn: java.sql.Connection = null
    try {
      logger.debug("Loading MySQL JDBC driver...")
      Class.forName("com.mysql.cj.jdbc.Driver")

      logger.info("Opening MySQL connection...")
      conn = DriverManager.getConnection(extractor.jdbcUrl, mysql.user, mysql.password)
      conn.setAutoCommit(false)

      extractor.ensureOffsetTable(conn, app.txnSummaryOffsetTable)
      val lastOffset = extractor.readLastOffset(conn, app.txnSummaryOffsetTable, app.txnSummarySourceId)
      logger.info(s"Last processed txn_id = $lastOffset")

      // Read delta
      val deltaDF = extractor.readDeltaTransactions(lastOffset)
      val hasData = deltaDF.head(1).nonEmpty

      if (!hasData) {
        logger.info("No new transactions found. Skipping batch.")
        conn.commit()
        return
      }

      logger.info("Delta transactions loaded.")

      // Derive affected calendar dates from delta
      val deltaWithDate = deltaDF.withColumn("date", to_date($"txn_timestamp"))
      val affectedDates =
        deltaWithDate.select($"date").distinct().as[java.sql.Date].collect().map(_.toString)

      logger.info(s"Affected dates: ${affectedDates.mkString(", ")}")

      // Determine new offset (max txn_id seen in this delta)
      val maxOffsetOpt =
        deltaDF.agg(max($"txn_id")).collect().headOption.flatMap { r =>
          if (r == null || r.isNullAt(0)) None else Some(r.getLong(0))
        }

      val newOffset = maxOffsetOpt.getOrElse(lastOffset)
      logger.info(s"New offset determined: $newOffset")

      if (affectedDates.isEmpty) {
        logger.warn("No affected dates found (unexpected). Updating offset only.")
        extractor.writeLastOffset(conn, app.txnSummaryOffsetTable, app.txnSummarySourceId, newOffset)
        conn.commit()
        return
      }

      // Read full data for affected dates
      logger.info("Reading full transactions for affected dates...")
      val txnsDF = extractor.readTransactionsForDates(affectedDates)
        .withColumn("date", to_date($"txn_timestamp"))
        .cache()

      logger.info(s"Loaded ${txnsDF.count()} full transactions.")

      val productsDF = extractor.readProducts().cache()
      logger.info(s"Loaded ${productsDF.count()} products.")

      // Transform
      logger.info("Computing daily summary...")
      val summaryDF = DataTransformer.computeSummary(txnsDF, productsDF)

      logger.info(s"Summary row count: ${summaryDF.count()}")

      // Write
      logger.info("Writing summary to S3...")
      loader.writeSummary(summaryDF)

      // Update offset
      logger.info(s"Updating last offset to $newOffset")
      extractor.writeLastOffset(conn, app.txnSummaryOffsetTable, app.txnSummarySourceId, newOffset)

      conn.commit()
      logger.info(s"Batch $batchId completed successfully.")

    } catch {
      case ex: Throwable =>
        logger.error(s"Batch $batchId FAILED: ${ex.getMessage}", ex)
        if (conn != null) {
          try conn.rollback()
          catch { case e: Throwable => logger.error("Rollback failed!", e) }
        }
        throw ex
    } finally {
      if (conn != null) {
        try {
          conn.close()
          logger.debug("MySQL connection closed.")
        } catch {
          case e: Throwable => logger.warn("Failed to close MySQL connection.", e)
        }
      }
    }
  }

  // Streaming ticker
  logger.info(s"Starting streaming scheduler (poll interval = ${app.txnSummaryPollIntervalSec} seconds)...")

  /**
   * Internal rate-based streaming source used as a lightweight scheduler.
   *
   * Generates one row per second, and the `Trigger.ProcessingTime` configuration
   * below ensures `runIncrementalBatch` is invoked at the configured poll interval.
   */
  val ticks = spark.readStream.format("rate").option("rowsPerSecond", 1).load()

  /**
   * Structured Streaming query that drives the periodic execution of `runIncrementalBatch`.
   *
   * Each micro-batch receives the current tick DataFrame and a unique batch id,
   * but only the batch id is used here; the data itself is ignored.
   */
  val query = ticks.writeStream
    .trigger(Trigger.ProcessingTime(s"${app.txnSummaryPollIntervalSec} seconds"))
    .foreachBatch { (_: DataFrame, batchId: Long) =>
      runIncrementalBatch(batchId)
    }
    .outputMode("update")
    .start()

  query.awaitTermination()
}
