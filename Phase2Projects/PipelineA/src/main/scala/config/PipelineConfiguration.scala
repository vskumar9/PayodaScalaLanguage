package config

import com.typesafe.config.{Config, ConfigFactory}

/**
 * Configuration for MySQL database connection used in data pipelines.
 * Defines host, port, credentials, and JDBC-specific settings like fetch size.
 *
 * @param host      MySQL server hostname or IP address
 * @param port      MySQL server port (default typically 3306)
 * @param database  Target database name
 * @param user      Database username
 * @param password  Database password
 * @param fetchSize Number of rows to fetch per JDBC result set batch
 * @param jdbcUrl   Complete JDBC connection URL
 */
case class MySQLConfig(host: String, port: Int, database: String, user: String, password: String,
                       fetchSize: Int, jdbcUrl: String)

/**
 * Configuration for Cassandra Keyspaces connection.
 * Used for profile data storage and retrieval in pipelines.
 *
 * @param host        Cassandra cluster contact points (comma-separated)
 * @param port        Native transport port (default 9042)
 * @param keyspace    Target keyspace name
 * @param profileTable Table name storing profile data
 * @param username    Authentication username
 * @param password    Authentication password
 * @param region      AWS region for Keyspaces service
 */
case class KeyspacesConfig(host: String, port: String, keyspace: String, profileTable: String,
                           username: String, password: String, region: String)

/**
 * Application-specific configuration for pipeline processing.
 * Controls polling, partitioning, parallelism, and Spark integration settings.
 *
 * @param pollIntervalSec       Interval between CDC polling cycles (seconds)
 * @param offsetTable           Table storing offset/watermark data
 * @param offsetSourceId        Unique identifier for this pipeline's offsets
 * @param jdbcNumPartitions     Number of JDBC partitions for parallel reads
 * @param maxInListForSql       Maximum IN clause size for batch SQL queries
 * @param jdbcMaxConnections    Maximum concurrent JDBC connections
 * @param debugForceOffsetWrite Force offset writes for testing (debug only)
 * @param writeParallelism      Spark parallelism for write operations
 * @param shufflePartitions     Spark shuffle partition count
 * @param consistency           Cassandra write consistency level
 * @param trustStorePath        Path to SSL truststore for secure connections
 * @param trustStorePassword    Password for truststore file
 */
case class AppConfig(pollIntervalSec: Int, offsetTable: String, offsetSourceId: String,
                     jdbcNumPartitions: Int, maxInListForSql: Int, jdbcMaxConnections: Int,
                     debugForceOffsetWrite: Boolean, writeParallelism: Int, shufflePartitions: Int,
                     consistency: String, trustStorePath: String, trustStorePassword: String)

/**
 * Centralized configuration loader for data pipelines.
 * Loads Typesafe Config from resources and provides validated case class instances
 * for MySQL, Keyspaces, and application settings. Supports optional config paths
 * with sensible defaults for robustness.
 */
object PipelineConfiguration {
  private val conf: Config = ConfigFactory.load()

  /**
   * MySQL configuration with dynamic JDBC URL construction and fetch size fallback.
   * Uses default fetchSize of 1000 if not specified in config.
   */
  val mysql = {
    val c = conf.getConfig("mysql")
    val fetchSize = if (c.hasPath("fetchSize")) c.getInt("fetchSize") else 1000
    val url = s"jdbc:mysql://${c.getString("host")}:${c.getInt("port")}/${c.getString("database")}"
    MySQLConfig(c.getString("host"), c.getInt("port"), c.getString("database"),
      c.getString("user"), c.getString("password"), fetchSize, url)
  }

  /**
   * Keyspaces configuration loaded directly from config hierarchy.
   * Expects all fields to be present in configuration.
   */
  val keyspaces = {
    val k = conf.getConfig("keyspaces")
    KeyspacesConfig(
      k.getString("host"),
      k.getString("port"),
      k.getString("keyspace"),
      k.getString("profileTable"),
      k.getString("username"),
      k.getString("password"),
      k.getString("region")
    )
  }

  /**
   * Application configuration for pipelineA with fallback defaults.
   * Combines app.pipelineA settings with global spark configuration.
   * Provides jdbcMaxConnections fallback to jdbcNumPartitions if unspecified.
   */
  val app = {
    val a = conf.getConfig("app.pipelineA")
    val debug = if (a.hasPath("debugForceOffsetWrite")) a.getBoolean("debugForceOffsetWrite") else false
    AppConfig(
      a.getInt("pollIntervalSec"),
      a.getString("offsetTable"),
      a.getString("offsetSourceId"),
      a.getInt("jdbcNumPartitions"),
      a.getInt("maxInListForSql"),
      if (a.hasPath("maxJdbcConnections")) a.getInt("maxJdbcConnections") else a.getInt("jdbcNumPartitions"),
      debug,
      conf.getConfig("spark").getInt("writeParallelism"),
      conf.getConfig("spark").getInt("shufflePartitions"),
      conf.getConfig("spark").getString("consistency"),
      conf.getConfig("spark").getString("truststore.path"),
      conf.getConfig("spark").getString("truststore.password")
    )
  }
}
