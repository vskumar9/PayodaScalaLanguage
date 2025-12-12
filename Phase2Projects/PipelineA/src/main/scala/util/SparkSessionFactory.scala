package util

import org.apache.spark.sql.SparkSession
import config.{PipelineConfiguration => PC}

/**
 * Factory for creating Spark sessions pre-configured for Cassandra Keyspaces integration.
 * Centralizes SSL, authentication, consistency, and connection settings for pipeline jobs.
 */
object SparkSessionFactory {

  /**
   * Creates local Spark session optimized for incremental pipeline processing.
   * Configures Cassandra Keyspaces connector with SSL, auth, and consistency settings.
   *
   * @param appName Application name for Spark UI and logging (default: "PipelineA_IncrementalOnly")
   * @return Pre-configured SparkSession ready for JDBC reads and Cassandra writes
   */
  def createLocalSession(appName: String = "PipelineA_IncrementalOnly"): SparkSession = {
    val ks = PC.keyspaces
    val app = PC.app

    SparkSession.builder()
      .appName(appName)
      .master("local[*]") // Use all available cores for local development/testing

      // Cassandra Keyspaces connection settings
      .config("spark.cassandra.connection.host", ks.host)
      .config("spark.cassandra.connection.port", ks.port)

      // SSL configuration for Keyspaces (AWS managed Cassandra)
      .config("spark.cassandra.connection.ssl.enabled", "true")
      .config("spark.cassandra.connection.ssl.trustStore.path", app.trustStorePath)
      .config("spark.cassandra.connection.ssl.trustStore.password", app.trustStorePassword)

      // Regional awareness and authentication
      .config("spark.cassandra.connection.localDC", ks.region)
      .config("spark.cassandra.auth.username", ks.username)
      .config("spark.cassandra.auth.password", ks.password)

      // Consistency level for read/write operations
      .config("spark.cassandra.input.consistency.level", app.consistency)

      .getOrCreate()
  }
}
