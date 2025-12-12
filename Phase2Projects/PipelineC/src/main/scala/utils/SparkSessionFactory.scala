package utils

import org.apache.spark.sql.SparkSession

/**
 * Factory for creating SparkSession optimized for Structured Streaming + S3 workloads.
 *
 * Configures production settings for Kafka-to-S3 event pipelines:
 * - Customizable shuffle partitions for cluster scaling
 * - Dynamic partition overwrites for event_date partitioning
 * - S3-optimized commit algorithm
 * - Speculation disabled for streaming determinism
 */
object SparkSessionFactory {

  /**
   * Creates production-ready SparkSession for streaming pipelines.
   *
   * @param appName Application name for Spark UI and YARN labels
   * @param masterOverride Optional Spark master URL (e.g., "yarn", "spark://master:7077")
   *                       - Falls back to default if None
   * @param shufflePartitions Number of shuffle partitions (cluster-size dependent)
   *
   * @return Active SparkSession with streaming/S3 optimizations applied
   */
  def create(appName: String, masterOverride: Option[String], shufflePartitions: String): SparkSession = {
    val builder = SparkSession.builder()
      .appName(appName)
      .config("spark.sql.shuffle.partitions", shufflePartitions)

      /**
       * S3-optimized commit algorithm for atomicity and performance.
       *
       * Version 2 uses staging directories to prevent partial writes.
       * Critical for streaming appends to avoid S3 eventual consistency issues.
       */
      .config("spark.hadoop.mapreduce.fileoutputcommitter.algorithm.version", "2")

      /**
       * Disables speculative execution for streaming determinism.
       *
       * Ensures exactly-once processing order in foreachBatch handlers.
       */
      .config("spark.speculation", "false")
      
      /**
       * Enables dynamic partition overwrites for event_date partitioning.
       *
       * Allows overwriting specific dates without affecting other partitions.
       * Essential for late-arriving events and idempotent streaming.
       */
      .config("spark.sql.sources.partitionOverwriteMode", "dynamic")

    /**
     * Applies master override if specified (supports YARN, Kubernetes, local[*]).
     *
     * Environment-driven via SPARK_MASTER for CI/CD flexibility.
     */
    val builderWithMaster = masterOverride match {
      case Some(m) => builder.master(m)
      case None    => builder
    }
    builderWithMaster.getOrCreate()
  }
}
