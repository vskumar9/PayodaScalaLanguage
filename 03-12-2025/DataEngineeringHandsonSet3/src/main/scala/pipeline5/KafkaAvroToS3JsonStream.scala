package pipeline5

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.avro.functions.from_avro

object KafkaAvroToS3JsonStream {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // ---------- Kafka Config ----------
    val kafka = conf.getConfig("kafka")
    val kafkaBootstrap = kafka.getString("bootstrap.servers")
    val kafkaTopic     = kafka.getString("topic")

    // ---------- S3 Output Paths ----------
    val outputPath     = conf.getString("app.streamJsonOutputPath")
    val checkpointPath = conf.getString("app.streamCheckPointsOutputPath")

    // ---------- S3 Credentials ----------
    val s3 = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ---------- Spark Session ----------
    val spark = SparkSession.builder()
      .appName("Kafka (Avro) -> JSON on S3")
      .master("local[*]")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .getOrCreate()

    import spark.implicits._

    // ---------- Configure Hadoop S3A with explicit credentials ----------
    val hconf = spark.sparkContext.hadoopConfiguration

    hconf.set("fs.s3a.endpoint", s3endpoint)
    hconf.set("fs.s3a.region", s3region)
    hconf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hconf.set("fs.s3a.path.style.access", "false")
    hconf.set("fs.s3a.connection.maximum", "100")
    hconf.set("fs.s3a.fast.upload", "true")
    hconf.set("fs.s3a.access.key", s3accessKey)
    hconf.set("fs.s3a.secret.key", s3secretKey)
    hconf.set(
      "fs.s3a.aws.credentials.provider",
      "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
    )

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

    // ---------- 1. Read from Kafka ----------
    val kafkaDf = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrap)
      .option("subscribe", kafkaTopic)
      .option("startingOffsets", "earliest")
      .load()

    // ---------- 2. Decode Avro payload ----------
    val decoded = kafkaDf
      .select(
        from_avro(col("value"), avroSchemaJson).as("data")
      )
      .select("data.*")  // order_id, customer_id, amount, created_at

    // Optional: print to console for debugging
    val debugStream = decoded.writeStream
      .format("console")
      .outputMode("append")
      .option("truncate", "false")
      .option("numRows", 20)
      .start()

    // ---------- 3 & 4. Write JSON to S3 ----------
    val jsonQuery = decoded.writeStream
      .format("json")
      .outputMode("append")
      .option("path", outputPath)
      .option("checkpointLocation", checkpointPath)
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .start()

    jsonQuery.awaitTermination()
//    debugStream.awaitTermination()
  }
}
