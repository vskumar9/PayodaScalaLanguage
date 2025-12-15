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
 * MySQL database connection configuration for pipeline data operations.
 *
 * @param host MySQL server hostname or IP address
 * @param port MySQL server port (default: 3306)
 * @param database Target database name
 * @param user Database username for authentication
 * @param password Database password for authentication
 * @param fetchSize Number of rows to fetch per JDBC result set batch
 *                  (optimizes memory usage for large result sets)
 */
case class MySQLConfig(
                        host: String,
                        port: Int,
                        database: String,
                        user: String,
                        password: String,
                        fetchSize: Int
                      ) {
  /**
   * Lazily constructed JDBC connection URL with optimized parameters.
   *
   * Includes:
   * - `useSSL=false`: Disables SSL for internal/trusted network connections
   * - `serverTimezone=UTC`: Ensures consistent timezone handling
   * - `rewriteBatchedStatements=true`: Enables batch insert optimization
   */
  lazy val jdbcUrl: String =
    s"jdbc:mysql://$host:$port/$database" +
      "?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true"
}

/**
 * Centralized configuration loader for Pipeline C application.
 *
 * Provides type-safe, immutable access to all pipeline configuration through dedicated case classes.
 * Loads from `application.conf` using Typesafe Config with support for environment overrides.
 *
 * ## Expected `application.conf` Structure
 * ```
 * kafka {
 *   bootstrapServers = "localhost:9092,localhost:9093"
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
 * mysql {
 *   host = "mysql-host"
 *   port = 3306
 *   database = "pipeline_c"
 *   user = "pipeline_user"
 *   password = "secure-password"
 *   fetchSize = 1000
 * }
 * ```
 *
 * ## Usage
 * ```
 * val kafkaConfig = PipelineConfiguration.kafka
 * val s3Path = PipelineConfiguration.appCfg.lakeEventsBasePath
 * val mysqlUrl = PipelineConfiguration.mysql.jdbcUrl
 * ```
 */
object PipelineConfiguration {
  /** Loaded Typesafe Config instance from application.conf + system properties + environment variables */
  private val cfg = ConfigFactory.load()

  /**
   * Kafka configuration for Structured Streaming consumer.
   *
   * @note `bootstrapServers` supports multiple brokers: "host1:9092,host2:9093"
   * @return Immutable KafkaConfig with topic and broker details
   */
  val kafka: KafkaConfig = KafkaConfig(
    cfg.getString("kafka.bootstrapServers"),
    cfg.getString("kafka.customerEventsTopic")
  )

  /**
   * Keyspaces/S3 credentials and endpoint configuration.
   *
   * Supports both AWS S3 and S3-compatible storage (MinIO, Ceph, etc.).
   * @note Empty `endpoint` uses default AWS S3 endpoint for specified region
   * @return KeyspacesConfig with authentication credentials
   */
  private val ks = cfg.getConfig("keyspaces")
  val keyspaces: KeyspacesConfig = KeyspacesConfig(
    ks.getString("accesskey"),
    ks.getString("secretkey"),
    ks.getString("region"),
    ks.getString("endpoint")
  )

  /**
   * Application paths configuration for data lake operations.
   *
   * Defines root S3 locations for:
   * - Event partitioning and archiving
   * - Structured Streaming checkpoints
   * - @return AppConfig with lake storage paths
   */
  private val app = cfg.getConfig("app")
  val appCfg: AppConfig = AppConfig(app.getString("lakeEventsBasePath"))

  /**
   * Spark performance tuning configuration.
   *
   * Critical for production workloads:
   * - `shufflePartitions` should match cluster executor count × cores per executor
   * - Typical values: 200-1000 depending on data volume and cluster size
   * @return SparkAppConfig for runtime tuning
   */
  val sparkCfg: SparkAppConfig = SparkAppConfig(cfg.getString("spark.shufflePartitions"))

  /**
   * MySQL configuration for pipeline metadata and reference data operations.
   *
   * Used for:
   * - Customer lookup tables
   * - products management
   * @return MySQLConfig with connection details and JDBC URL
   */
  private val mysqlCfg = cfg.getConfig("mysql")
  val mysql: MySQLConfig = MySQLConfig(
    host       = mysqlCfg.getString("host"),
    port       = mysqlCfg.getInt("port"),
    database   = mysqlCfg.getString("database"),
    user       = mysqlCfg.getString("user"),
    password   = mysqlCfg.getString("password"),
    fetchSize  = mysqlCfg.getInt("fetchSize")
  )
}
