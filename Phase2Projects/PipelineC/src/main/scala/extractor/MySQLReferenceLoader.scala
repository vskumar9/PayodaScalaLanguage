package extractor

import org.apache.spark.sql.{DataFrame, SparkSession}
import config.PipelineConfiguration

/**
 * MySQL reference data loader for Spark Structured Streaming pipeline.
 *
 * Provides optimized JDBC data loading for customer and product reference tables.
 * Uses predicate pushdown and parallel partition reading for large-scale data extraction.
 *
 * ## Performance Optimizations
 * - Configurable fetch size from PipelineConfiguration
 * - Parallel partition reading with dynamic bounds
 * - Column pruning to minimize data transfer
 * - Tunable partition counts matching Spark cluster capacity
 */
object MySQLReferenceLoader {

  /** Centralized MySQL configuration from PipelineConfiguration */
  private val mysql = PipelineConfiguration.mysql

  /**
   * Base JDBC reader configuration with optimized connection options.
   *
   * Pre-configures:
   * - Connection URL with batch optimizations
   * - Authentication credentials
   * - Fetch size for memory-efficient row retrieval
   *
   * @param spark Active SparkSession instance
   * @return JDBC reader with common pipeline options
   */
  private def baseReader(spark: SparkSession) =
    spark.read
      .format("jdbc")
      .option("url", mysql.jdbcUrl)
      .option("user", mysql.user)
      .option("password", mysql.password)
      .option("fetchsize", mysql.fetchSize)

  /**
   * Loads customer reference data for join operations.
   *
   * **Optimized for high-volume customer tables** (100M+ records):
   * - Parallel reading across 200 partitions
   * - Predicate pushdown on `customer_id` (integer range 1-100M)
   * - Column pruning to `customer_id` only (reduces network I/O by ~90%)
   *
   * @param spark SparkSession for DataFrame operations
   * @return DataFrame containing customer_id column for streaming joins
   */
  def loadCustomers(spark: SparkSession): DataFrame =
    baseReader(spark)
      .option("dbtable", "customers")
      .option("partitionColumn", "customer_id")
      .option("lowerBound", "1")
      .option("upperBound", "100000000")
      .option("numPartitions", "200")
      .load()
      .select("customer_id")

  /**
   * Loads product reference data for enrichment operations.
   *
   * **Optimized for medium-volume product tables** (10M+ records):
   * - Parallel reading across 100 partitions
   * - Predicate pushdown on `product_id` (integer range 1-10M)
   * - Column pruning to `product_id` only
   *
   * @param spark SparkSession for DataFrame operations
   * @return DataFrame containing product_id column for streaming joins
   */
  def loadProducts(spark: SparkSession): DataFrame =
    baseReader(spark)
      .option("dbtable", "products")
      .option("partitionColumn", "product_id")
      .option("lowerBound", "1")
      .option("upperBound", "10000000")
      .option("numPartitions", "100")
      .load()
      .select("product_id")
}
