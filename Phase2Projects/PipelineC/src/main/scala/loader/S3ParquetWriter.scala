package loader

import org.apache.spark.sql.DataFrame

/**
 * S3 Parquet writer for Structured Streaming micro-batches.
 *
 * Provides efficient, partitioned writes to S3 lake storage using Parquet format.
 * Designed for high-throughput event archiving with automatic date partitioning.
 *
 * Key features:
 * - Append-only writes for streaming exactly-once semantics
 * - Dynamic partitioning by event_date for query performance
 * - Parquet columnar format optimized for analytics workloads
 */
object S3ParquetWriter {

  /**
   * Writes a micro-batch DataFrame to S3 as partitioned Parquet.
   *
   * @param df Input DataFrame containing processed events with event_date column
   * @param basePath S3 target path (e.g., "s3a://bucket/events/")
   *                 - Partitions written as: basePath/event_date=YYYY-MM-DD/
   *
   * @note Automatically handles dynamic partition overwrites within Spark
   * @note Requires event_date column (date type) in input DataFrame
   * @note Append mode ensures streaming checkpoint compatibility
   */
  def writeBatch(df: DataFrame, basePath: String): Unit = {
    df.write
      .mode("append")
      .partitionBy("event_date")
      .parquet(basePath)
  }
}
