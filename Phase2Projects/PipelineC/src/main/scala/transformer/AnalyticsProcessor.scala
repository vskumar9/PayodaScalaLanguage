package transformer

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

import loader.S3ParquetWriter
import utils.AppLogging

/**
 * Analytics processor for valid Kafka event micro-batches.
 *
 * Performs per-batch diagnostics, aggregations, and S3 persistence for data lake storage.
 * Designed for Structured Streaming foreachBatch integration with comprehensive logging.
 *
 * Processing pipeline:
 * 1. Cache batch for multiple aggregation passes
 * 2. Compute row count and sample preview
 * 3. Generate event_type, customer_id, product_id aggregations
 * 4. Write partitioned Parquet to S3 lake
 * 5. Cleanup cache resources
 */
object AnalyticsProcessor extends AppLogging {

  /**
   * Processes a single micro-batch of valid events.
   *
   * @param batchDF Valid events DataFrame from KafkaAvroExtractor
   * @param batchId Unique Spark Streaming batch identifier
   * @param eventsBasePath S3 lake path for partitioned Parquet output
   *
   * @note Caches DataFrame for efficient multiple aggregations
   * @note Generates operational diagnostics for monitoring/alerting
   * @note Partitioned writes optimize S3 query performance
   */
  def foreachBatch(batchDF: DataFrame, batchId: Long, eventsBasePath: String): Unit = {

    info(s"================ BATCH $batchId ================")

    /**
     * Cache DataFrame for reuse across count(), show(), and groupBy operations.
     *
     * MEMORY_AND_DISK storage level balances performance and resource usage.
     * Unpersist() in finally block prevents memory leaks.
     */
    val cached = batchDF.persist()
    try {
      val rowCount = cached.count()
      info(s"[PipelineC] Batch $batchId → rows=$rowCount")

      if (rowCount > 0) {

        /**
         * Sample preview for data validation and debugging.
         *
         * truncate=false preserves full field values in logs.
         */
        info("[PipelineC] Showing sample rows:")
        cached.show(5, truncate = false)

        /**
         * Events distribution by event_type for operational monitoring.
         *
         * Helps detect traffic spikes or missing event types.
         */
        info("[PipelineC] Events per event_type (this batch):")
        val eventsPerType = cached.groupBy("event_type")
          .count()
          .orderBy(desc("count"))
        eventsPerType.show(truncate = false)

        /**
         * Top customers by event volume - business monitoring metric.
         *
         * Identifies high-activity customers per batch window.
         */
        info("[PipelineC] Top 5 customers by event count:")
        val topCustomers = cached.groupBy("customer_id")
          .count()
          .withColumnRenamed("count", "events")
          .orderBy(desc("events"))
          .limit(5)
        topCustomers.show(truncate = false)

        /**
         * Top products excluding null product_id events.
         *
         * Filters non-product events before aggregation.
         */
        info("[PipelineC] Top 5 products in this batch:")
        val topProducts = cached.filter(col("product_id").isNotNull)
          .groupBy("product_id")
          .count()
          .orderBy(desc("count"))
          .limit(5)
        topProducts.show(truncate = false)

        /**
         * Final S3 lake write using partitioned Parquet format.
         *
         * Partitions by event_date (from KafkaAvroExtractor) for:
         * - Efficient S3 list/scan operations
         * - Time-based data pruning in analytics queries
         * - Cost-effective storage tiering
         */
        info(s"[PipelineC] Writing batch $batchId to S3 path: $eventsBasePath")
        S3ParquetWriter.writeBatch(cached, eventsBasePath)

      } else {
        warn(s"[PipelineC] Batch $batchId contains no rows → skipping write.")
      }

    } catch {
      case ex: Throwable =>
        /**
         * Comprehensive error handling preserves streaming job continuity.
         *
         * Logs full stack trace for root cause analysis.
         * Batch failures don't terminate the streaming query.
         */
        error(s"[PipelineC] Error processing batch $batchId: ${ex.getMessage}", ex)
    } finally {
      /**
       * Resource cleanup prevents OOM across long-running streaming jobs.
       *
       * Essential for sustained 24/7 operation with variable batch sizes.
       */
      cached.unpersist()
      debug(s"[PipelineC] Batch $batchId cache cleared.")
    }
  }
}
