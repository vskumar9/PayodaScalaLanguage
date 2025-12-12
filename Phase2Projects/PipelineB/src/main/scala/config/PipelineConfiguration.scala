package config

import com.typesafe.config.ConfigFactory

/**
 * MySQL configuration parameters used for database connectivity.
 *
 * @param host The hostname or IP address of the MySQL server.
 * @param port The port number on which the MySQL service is running.
 * @param database The name of the target MySQL database.
 * @param user The username used to connect to the MySQL database.
 * @param password The password associated with the given user.
 * @param fetchSize The number of rows to fetch in each batch during queries.
 */
case class MySQLConf(
                      host: String,
                      port: Int,
                      database: String,
                      user: String,
                      password: String,
                      fetchSize: Int
                    )

/**
 * Configuration for accessing a keyspace or remote data store,
 * typically used for connecting to an AWS S3-like storage or other key-value stores.
 *
 * @param accessKey The access key used for authentication.
 * @param secretKey The secret key corresponding to the access key.
 * @param region The region identifier where the keyspace or service is located.
 * @param endpoint The endpoint URL of the service or keyspace.
 */
case class KeyspaceConf(
                         accessKey: String,
                         secretKey: String,
                         region: String,
                         endpoint: String
                       )

/**
 * Application-level configuration parameters that govern the pipeline’s
 * transaction summary processing and polling behavior.
 *
 * @param lakeTxnSummaryBasePath The base storage path in the data lake for transaction summaries.
 * @param txnSummaryPollIntervalSec The polling interval in seconds for checking new transaction summaries.
 * @param txnSummaryOffsetTable The name of the table storing offset information for transaction summaries.
 * @param txnSummarySourceId The unique identifier for the transaction summary source.
 */
case class AppConf(
                    lakeTxnSummaryBasePath: String,
                    txnSummaryPollIntervalSec: Int,
                    txnSummaryOffsetTable: String,
                    txnSummarySourceId: String
                  )

/**
 * Loads and encapsulates configuration details from the application’s reference.conf or application.conf file.
 *
 * This object provides top-level access to MySQL, Keyspace, and App configuration blocks,
 * making it easier to retrieve configuration data in a type-safe and organized way.
 */
object PipelineConfiguration {
  // Loads the default configuration file from the application classpath
  private val cfg = ConfigFactory.load()

  /** Loads the MySQL configuration block. */
  val mysql: MySQLConf = {
    val c = cfg.getConfig("mysql")
    MySQLConf(
      host = c.getString("host"),
      port = c.getInt("port"),
      database = c.getString("database"),
      user = c.getString("user"),
      password = c.getString("password"),
      fetchSize = c.getInt("fetchSize")
    )
  }

  /** Loads the Keyspace configuration block. */
  val keyspace: KeyspaceConf = {
    val c = cfg.getConfig("keyspaces")
    KeyspaceConf(
      accessKey = c.getString("accesskey"),
      secretKey = c.getString("secretkey"),
      region = c.getString("region"),
      endpoint = c.getString("endpoint")
    )
  }

  /** Loads the Application configuration block. */
  val app: AppConf = {
    val a = cfg.getConfig("app")
    AppConf(
      lakeTxnSummaryBasePath = a.getString("lakeTxnSummaryBasePath"),
      txnSummaryPollIntervalSec = a.getInt("txnSummaryPollIntervalSec"),
      txnSummaryOffsetTable = a.getString("txnSummaryOffsetTable"),
      txnSummarySourceId = a.getString("txnSummarySourceId")
    )
  }
}
