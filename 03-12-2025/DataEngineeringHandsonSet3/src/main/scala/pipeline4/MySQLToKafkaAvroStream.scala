package pipeline4

import java.sql.{Connection, DriverManager}
import com.typesafe.config.ConfigFactory

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types._
import org.apache.spark.sql.avro.functions.to_avro

object MySQLToKafkaAvroStream {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // ---------- Config ----------
    val mysql = conf.getConfig("mysql")
    val jdbcHost      = mysql.getString("host")
    val jdbcPort      = mysql.getInt("port")
    val jdbcDatabase  = mysql.getString("database")
    val jdbcUser      = mysql.getString("user")
    val jdbcPassword  = mysql.getString("password")

    val jdbcUrl = s"jdbc:mysql://$jdbcHost:$jdbcPort/$jdbcDatabase"

    val jdbcTable  = mysql.getString("table")        // new_orders
    val offsetsTbl = mysql.getString("offsetsTable") // streaming_offsets

    val kafka     = conf.getConfig("kafka")
    val kafkaBootstrap = kafka.getString("bootstrap.servers")
    val kafkaTopic     = kafka.getString("topic")

    val pollIntervalSec = conf.getInt("app.pollIntervalSec")

    // ---------- Spark ----------
    val spark = SparkSession.builder()
      .appName("MySQL -> Kafka (Avro) Streaming")
      .master("local[*]")
      .getOrCreate()

    import spark.implicits._

    // ---------- Inline AVRO Schema JSON ----------
    val avroSchemaJson: String =
      """
        {
          "type": "record",
          "name": "OrderRecord",
          "namespace": "com.retail",
          "fields": [
            { "name": "order_id", "type": "int" },
            { "name": "customer_id", "type": "int" },
            { "name": "amount", "type": "double" },
            { "name": "created_at", "type": "string" }
          ]
        }
      """.stripMargin

    // ---------- Helper: Offset read/write ----------
    def readLastOffset(conn: Connection): Long = {
      val stmt = conn.prepareStatement(s"SELECT last_order_id FROM $offsetsTbl WHERE source=?")
      stmt.setString(1, jdbcTable)
      val rs = stmt.executeQuery()
      val last = if (rs.next()) rs.getLong(1) else 0L
      rs.close(); stmt.close()
      last
    }

    def writeLastOffset(conn: Connection, last: Long): Unit = {
      val sql =
        s"""
           |INSERT INTO $offsetsTbl (source, last_order_id)
           |VALUES (?, ?)
           |ON DUPLICATE KEY UPDATE last_order_id = GREATEST(last_order_id, VALUES(last_order_id))
         """.stripMargin
      val ps = conn.prepareStatement(sql)
      ps.setString(1, jdbcTable)
      ps.setLong(2, last)
      ps.executeUpdate()
      ps.close()
    }

    // ---------- Dummy stream to trigger batches ----------
    val ticks = spark.readStream
      .format("rate")
      .option("rowsPerSecond", 1)
      .load()

    val query = ticks.writeStream
      .trigger(Trigger.ProcessingTime(s"${pollIntervalSec} seconds"))
      .foreachBatch { (_: DataFrame, batchId: Long) =>
        println(s"==== Batch $batchId ====")

        var conn: Connection = null
        try {
          Class.forName("com.mysql.cj.jdbc.Driver")
          conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)
          conn.setAutoCommit(false)

          // Ensure offset table exists
          conn.createStatement().execute(
            s"""
               CREATE TABLE IF NOT EXISTS $offsetsTbl (
                 source VARCHAR(128) PRIMARY KEY,
                 last_order_id BIGINT NOT NULL
               )
             """
          )

          val lastOffset = readLastOffset(conn)
          println(s"Last offset = $lastOffset")

          // Pull new rows
          val queryStr =
            s"(SELECT order_id, customer_id, amount, created_at FROM $jdbcTable " +
              s"WHERE order_id > $lastOffset ORDER BY order_id) AS new_rows"

          val newRowsDF = spark.read
            .format("jdbc")
            .option("url", jdbcUrl)
            .option("dbtable", queryStr)
            .option("user", jdbcUser)
            .option("password", jdbcPassword)
            .option("driver", "com.mysql.cj.jdbc.Driver")
            .load()

          val hasData = newRowsDF.head(1).nonEmpty

          if (hasData) {
            val prepared = newRowsDF
              .withColumn(
                "created_at_str",
                date_format(col("created_at"), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
              )
              .select(
                col("order_id").cast(IntegerType).as("order_id"),
                col("customer_id").cast(IntegerType).as("customer_id"),
                col("amount").cast(DoubleType).as("amount"),
                col("created_at_str").as("created_at")
              )

            // ---------- Build Avro using explicit schema ----------
            val avroDf = prepared.select(
              col("order_id").cast(StringType).as("key"),
              to_avro(
                struct(
                  col("order_id").as("order_id"),
                  col("customer_id").as("customer_id"),
                  col("amount").as("amount"),
                  col("created_at").as("created_at")
                ),
                avroSchemaJson
              ).as("value")
            )

            // ---------- Write to Kafka ----------
            avroDf
              .select(col("key").cast("binary"), col("value"))
              .write
              .format("kafka")
              .option("kafka.bootstrap.servers", kafkaBootstrap)
              .option("topic", kafkaTopic)
              .save()

            // ---------- Update offset ----------
            val maxOrderId = prepared.agg(max("order_id")).head().getInt(0).toLong
            writeLastOffset(conn, maxOrderId)

            conn.commit()
            println(s"WROTE: ${prepared.count()} rows → new offset = $maxOrderId")
          } else {
            println(s"No new rows in batch $batchId")
            conn.commit()
          }

        } catch {
          case ex: Throwable =>
            println(s"[ERROR] Batch $batchId: ${ex.getMessage}")
            if (conn != null) conn.rollback()
        } finally {
          if (conn != null) conn.close()
        }
      }
      .start()

    query.awaitTermination()
  }
}
