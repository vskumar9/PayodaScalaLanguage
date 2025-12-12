package loader

import org.apache.spark.sql.{DataFrame, SaveMode}
import util.Logging

/**
 * Responsible for writing processed data (daily transaction summaries) to S3 as partitioned Parquet files.
 *
 * This loader uses date-based partitioning to enable efficient queries by date range and supports
 * dynamic overwrite mode for idempotent writes. The coalesce operation controls the number of
 * output files per partition for optimal S3 read performance.
 *
 * @param lakeBase The S3 base path where partitioned Parquet files will be written.
 *                 Expected format: `s3a://bucket/prefix/daily-summaries/`
 */
class S3Loader(lakeBase: String) extends Logging {

  /**
   * Writes a transaction summary DataFrame to S3 as date-partitioned Parquet files.
   *
   * The write operation:
   *  - Coalesces the DataFrame to control output file count per partition (reduces S3 list overhead).
   *  - Partitions by `date` column for efficient date-range queries.
   *  - Uses `Overwrite` mode to ensure idempotency for reprocessing affected dates.
   *  - Stores data in efficient columnar Parquet format optimized for analytics.
   *
   * @param summaryDF The summarized transaction data containing a `date` column for partitioning.
   * @param coalesceNum Number of partitions after coalescing (default: 4).
   *                    Controls output files per date partition for S3 read performance.
   *                    Typical values: 1-8 depending on daily data volume.
   */
  def writeSummary(summaryDF: DataFrame, coalesceNum: Int = 4): Unit = {
    logger.info(s"Writing summary to: $lakeBase")
    logger.debug(s"Rows: ${summaryDF.count()}, Coalesce: $coalesceNum")

    summaryDF
      .coalesce(coalesceNum)
      .write
      .mode(SaveMode.Overwrite)
      .partitionBy("date")
      .parquet(lakeBase)
  }
}
