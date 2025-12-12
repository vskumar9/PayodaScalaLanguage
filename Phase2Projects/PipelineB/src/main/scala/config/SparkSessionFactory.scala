package util

import org.apache.spark.sql.SparkSession
import config.PipelineConfiguration

/**
 * Factory object responsible for creating and configuring a [[SparkSession]] instance
 * used in the data processing pipeline.
 *
 * This utility centralizes Spark initialization logic, ensuring that all pipeline jobs
 * use a consistent configuration, including S3 settings, Spark runtime parameters, and
 * application defaults.
 *
 * The configuration values for S3 are derived from [[PipelineConfiguration.keyspace]],
 * which typically points to credentials and endpoints for the target storage environment.
 */
object SparkSessionFactory extends Logging {

  /**
   * Creates and configures a SparkSession for the pipeline.
   *
   * @param appName The name assigned to the Spark application. Defaults to `"pipeline-b"`.
   *                This appears in Spark’s web UI and application tracking systems.
   * @return A configured [[SparkSession]] instance ready for data processing.
   */
  def create(appName: String = "pipeline-b"): SparkSession = {
    logger.info(s"Creating SparkSession: $appName")

    // Retrieve S3 credentials and endpoint configuration from application config
    val s3 = PipelineConfiguration.keyspace

    // Initialize SparkSession with standard settings for distributed and local runs
    val spark = SparkSession.builder()
      .appName(appName)
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]")) // Support both cluster and local runs
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "0.0.0.0")
      .config("spark.network.timeout", "600s")
      .config("spark.executor.heartbeatInterval", "60s")
      .config("spark.rpc.askTimeout", "300s")
      .config("spark.sql.shuffle.partitions", "200")
      .config("spark.sql.sources.partitionOverwriteMode", "dynamic")
      .getOrCreate()

    // Configure Hadoop S3A connector with keyspace credentials and performance tuning
    val hconf = spark.sparkContext.hadoopConfiguration
    hconf.set("fs.s3a.endpoint", s3.endpoint)
    hconf.set("fs.s3a.region", s3.region)
    hconf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hconf.set("fs.s3a.path.style.access", "false")
    hconf.set("fs.s3a.connection.maximum", "100")
    hconf.set("fs.s3a.fast.upload", "true")
    hconf.set("fs.s3a.access.key", s3.accessKey)
    hconf.set("fs.s3a.secret.key", s3.secretKey)
    hconf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")

    logger.info("SparkSession created successfully")
    spark
  }
}
