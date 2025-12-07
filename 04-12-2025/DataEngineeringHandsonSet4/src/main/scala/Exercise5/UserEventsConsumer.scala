package Exercise5

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.protobuf.functions.from_protobuf

object UserEventsConsumer {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder()
      .appName("Exercise 5 - Kafka Protobuf UserEvents Consumer")
      .master("local[*]")
      .getOrCreate()

    import spark.implicits._

    val kafkaBootstrapServers = "localhost:9092"
    val topic                 = "user-events"

    // Same descriptor + type as producer
    val descriptorFile = "UserEvent.desc"
    val messageType    = "protobuf.UserEvent"

    // 1) Read from Kafka (value as binary, only needed columns)
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrapServers)
      .option("subscribe", topic)
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()
      .select($"value")                        // drop unused Kafka metadata

    // 2) Decode Protobuf using spark-protobuf
    val decoded = kafkaDF
      .select(
        from_protobuf($"value".cast("BINARY"), messageType, descriptorFile).alias("event")
      )

    // Handle malformed messages: event == null when decode fails
    val validEvents = decoded
      .filter($"event".isNotNull)
      .select("event.*") // => userId, action, value

    val malformedEvents = decoded.filter($"event".isNull)

    // ===== Stream 1: malformed events (just log counts) =====
    val malformedQuery = malformedEvents.writeStream
      .outputMode("append")
      .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
        val cnt = batchDF.count()
        if (cnt > 0) {
          println(s"\nBatch $batchId: malformed / undecodable messages = $cnt")
        }
      }
      .option("checkpointLocation", "src/main/scala/Exercise5/checkpoints/user-events-malformed") // better than src/
      .start()

    // ===== Stream 2: main analytics with per-batch caching =====
    val query = validEvents.writeStream
      .outputMode("append")
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
        println(s"\n================ BATCH $batchId ================")

        // Cache the batch
        val cached = batchDF.persist()

        // Materialize the cache once
        val rowCount = cached.count()
        println(s"Rows in this batch: $rowCount")

        // Show a few rows for debugging
        cached.show(5, truncate = false)

        // ---- (1) Events per action type ----
        val eventsPerAction = cached
          .groupBy("action")
          .count()
          .orderBy(desc("count"))

        println("\nEvents per action (this batch):")
        eventsPerAction.show(truncate = false)

        // ---- (2) Top 5 users by total value (within this batch) ----
        val topUsers = cached
          .groupBy("userId")
          .agg(sum($"value").alias("totalValue"))
          .orderBy(desc("totalValue"))
          .limit(5)

        println("\nTop 5 users by value (this batch):")
        topUsers.show(truncate = false)

        // Release memory/disk for this batch
        cached.unpersist()
        ()
      }
      .option("checkpointLocation", "src/main/scala/Exercise5/user-events-consumer")
      .start()


    query.awaitTermination()
    malformedQuery.awaitTermination()
  }
}
