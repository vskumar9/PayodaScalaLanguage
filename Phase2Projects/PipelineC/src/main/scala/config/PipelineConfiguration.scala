package config

import com.typesafe.config.ConfigFactory

/**
 * Configuration for Keyspaces/S3 storage connectivity.
 *
 * @param accessKey AWS access key for authentication
 * @param secretKey AWS secret key for authentication
 * @param region AWS region for S3 operations
 * @param endpoint Custom S3 endpoint (supports MinIO, custom S3-compatible storage)
 */
case class KeyspacesConfig(accessKey: String, secretKey: String, region: String, endpoint: String)

/**
 * Application-level configuration for data lake paths.
 *
 * @param lakeEventsBasePath Base S3 path for event archiving (e.g., s3a://bucket/events/)
 */
case class AppConfig(lakeEventsBasePath: String)

/**
 * Kafka consumer configuration for event streaming.
 *
 * @param bootstrapServers Kafka broker connection string (comma-separated)
 * @param customerEventsTopic Topic containing customer event Avro records
 */
case class KafkaConfig(bootstrapServers: String, customerEventsTopic: String)

/**
 * Spark application tuning configuration.
 *
 * @param shufflePartitions Number of partitions for Spark shuffle operations
 *                         (controls parallelism and memory usage)
 */
case class SparkAppConfig(shufflePartitions: String)

/**
 * Centralized configuration loader for Pipeline C application.
 *
 * Loads settings from `application.conf` using Typesafe Config and provides:
 * - Immutable, type-safe configuration objects
 * - Lazy evaluation of nested config sections
 * - Centralized access point for all pipeline components
 *
 * Expected config structure:
 * ```
 * kafka {
 *   bootstrapServers = "localhost:9092"
 *   customerEventsTopic = "customer-events"
 * }
 * keyspaces {
 *   accesskey = "your-access-key"
 *   secretkey = "your-secret-key"
 *   region = "us-east-1"
 *   endpoint = "http://minio:9000"
 * }
 * app {
 *   lakeEventsBasePath = "s3a://events-lake/customer/"
 * }
 * spark {
 *   shufflePartitions = "200"
 * }
 * ```
 */
object PipelineConfiguration {
  /** Loaded Typesafe Config instance (application.conf + overrides) */
  private val cfg = ConfigFactory.load()

  /**
   * Kafka configuration for Structured Streaming consumer.
   *
   * @note bootstrapServers supports multiple brokers: "host1:9092,host2:9092"
   */
  val kafka: KafkaConfig = KafkaConfig(
    cfg.getString("kafka.bootstrapServers"),
    cfg.getString("kafka.customerEventsTopic")
  )

  /**
   * Keyspaces/S3 credentials and endpoint configuration.
   *
   * @note endpoint optional - empty string uses default AWS S3
   */
  private val ks = cfg.getConfig("keyspaces")
  val keyspaces: KeyspacesConfig = KeyspacesConfig(
    ks.getString("accesskey"),
    ks.getString("secretkey"),
    ks.getString("region"),
    ks.getString("endpoint")
  )

  /**
   * Application paths configuration.
   *
   * Defines the root S3 location for event partitioning and checkpointing.
   */
  private val app = cfg.getConfig("app")
  val appCfg: AppConfig = AppConfig(app.getString("lakeEventsBasePath"))

  /**
   * Spark performance tuning configuration.
   *
   * Controls shuffle parallelism - should match cluster size and data volume.
   */
  val sparkCfg: SparkAppConfig = SparkAppConfig(cfg.getString("spark.shufflePartitions"))
}
