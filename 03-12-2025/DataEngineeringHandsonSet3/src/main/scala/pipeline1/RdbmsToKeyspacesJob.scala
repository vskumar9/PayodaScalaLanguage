package pipeline1

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}

object RdbmsToKeyspacesJob {
  def main(args: Array[String]): Unit = {

    val config = ConfigFactory.load()

    // ---------------- MySQL ----------------
    val mysql = config.getConfig("mysql")
    val jdbcHost      = mysql.getString("host")
    val jdbcPort      = mysql.getInt("port")
    val jdbcDatabase  = mysql.getString("database")
    val jdbcUser      = mysql.getString("user")
    val jdbcPassword  = mysql.getString("password")
    val jdbcFetchSize = mysql.getInt("fetchSize")

    val jdbcUrl = s"jdbc:mysql://$jdbcHost:$jdbcPort/$jdbcDatabase"

    // ---------------- Keyspaces ----------------
    val ks = config.getConfig("keyspaces")
    val cassandraHost = ks.getString("host")
    val cassandraPort = ks.getInt("port").toString
    val cassandraKeyspace = ks.getString("keyspace")
    val cassandraTable = ks.getString("table")
    val accessKey = ks.getString("username")
    val accessKeyPass = ks.getString("password")

    // ---------------- Truststore Options ----------------
    val sparkConf = config.getConfig("spark")
    val consistency = sparkConf.getString("consistency")
    val trustStorePath = sparkConf.getString("truststore.path")
    val trustStorePassword = sparkConf.getString("truststore.password")


    // --------------------
    // Spark session
    // --------------------
    val spark = SparkSession.builder()
      .appName("RDBMS -> Spark -> Amazon Keyspaces")
      .master("local[*]")
      .config("spark.cassandra.connection.host", cassandraHost)
      .config("spark.cassandra.connection.port", cassandraPort)
      .config("spark.cassandra.connection.ssl.enabled", "true")
      .config("spark.cassandra.auth.username", accessKey)
      .config("spark.cassandra.auth.password", accessKeyPass )
      .config("spark.cassandra.input.consistency.level", consistency)
      .config("spark.cassandra.connection.ssl.trustStore.path",trustStorePath)
      .config("spark.cassandra.connection.ssl.trustStore.password", trustStorePassword)
      .getOrCreate()

    // --------------------
    // Helper to read a table from MySQL via JDBC
    // --------------------
    def readJdbcTable(tableName: String, extraOptions: Map[String, String] = Map.empty): DataFrame = {
      val baseOptions = Map(
        "url" -> jdbcUrl,
        "dbtable" -> tableName,
        "user" -> jdbcUser,
        "password" -> jdbcPassword,
        "driver" -> "com.mysql.cj.jdbc.Driver",
        "fetchsize" -> jdbcFetchSize.toString
      )

      // merge and read
      spark.read
        .format("jdbc")
        .options(baseOptions ++ extraOptions)
        .load()
    }

    // --------------------
    // Read tables
    // --------------------
    val customersDF = readJdbcTable("customers")
      // ensure expected schema types
      .select(
        col("customer_id").cast(IntegerType).as("customer_id"),
        col("name").cast(StringType).as("name"),
        col("email").cast(StringType).as("email"),
        col("city").cast(StringType).as("city")
      )

    val ordersDF = readJdbcTable("orders")
      .select(
        col("order_id").cast(IntegerType).as("order_id"),
        col("customer_id").cast(IntegerType).as("customer_id"),
        col("order_date").as("order_date"), // expect DATE
        col("amount").cast(DoubleType).as("amount")
      )

    val orderItemsDF = readJdbcTable("order_items")
      .select(
        col("item_id").cast(IntegerType).as("item_id"),
        col("order_id").cast(IntegerType).as("order_id"),
        col("product_name").cast(StringType).as("product_name"),
        col("quantity").cast(IntegerType).as("quantity")
      )

    // --------------------
    // Joins
    // customers.customer_id = orders.customer_id
    // orders.order_id = order_items.order_id
    // --------------------
    val custOrdersDF = customersDF.join(ordersDF, Seq("customer_id"), "inner")

    val denormDF = custOrdersDF
      .join(orderItemsDF, Seq("order_id"), "inner")
      // The above join sequence results in columns: customer_id, name, email, city, order_id, order_date, amount, item_id, product_name, quantity
      .select(
        col("customer_id"),
        col("name"),
        col("email"),
        col("city"),
        col("order_id"),
        // convert DATE to timestamp (Cassandra timestamp expects a timestamp)
        col("order_date").cast(TimestampType).as("order_date"),
        col("amount"),
        col("item_id"),
        col("product_name"),
        col("quantity")
      )
      .dropDuplicates("customer_id", "order_id", "item_id")

    // Show a sample
    println("Sample denormalized rows:")
    denormDF.show(10, truncate = false)

    try {
      denormDF.write
        .format("org.apache.spark.sql.cassandra")
        .option("keyspace", cassandraKeyspace)
        .option("table", cassandraTable)
        .mode("append")
        .save()

      println(s"Wrote ${denormDF.count()} rows to $cassandraKeyspace.$cassandraTable")
    } catch {
      case e: Exception =>
        System.err.println("Error writing to Cassandra/Keyspaces: " + e.getMessage)
        e.printStackTrace()
    }

    spark.stop()
  }
}
