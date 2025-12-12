package app

import config.PipelineConfiguration
import utils.SparkSessionFactory
import extractor.KafkaAvroExtractor
import malformed.MalformedHandler
import transformer.AnalyticsProcessor
import utils.AppLogging
import org.apache.spark.sql.streaming.Trigger

/**
 * Main entry point for Pipeline C: Kafka Avro Events Archive to S3.
 *
 * This structured streaming application processes Kafka Avro events, separating them into:
 * - Valid events: Processed by AnalyticsProcessor and archived to S3 lake storage
 * - Malformed events: Handled by MalformedHandler and archived separately
 *
 * Key features:
 * - Dual streaming queries running concurrently with independent checkpoints
 * - 10-second processing triggers for both streams
 * - S3A configuration for custom endpoints and credentials
 * - Comprehensive logging throughout the pipeline lifecycle
 */
object PipelineApp extends App with AppLogging {

  import scala.concurrent.duration._
  import org.apache.spark.sql.DataFrame

  /**
   * Application startup - initializes the complete Kafka-to-S3 archiving pipeline.
   */
  logger.info("Starting Pipeline C - Kafka Avro Events Archive to S3")

  /**
   * Loads pipeline configuration and defines checkpoint locations.
   *
   * @note Checkpoints ensure exactly-once processing semantics across restarts
   */
  val cfg = PipelineConfiguration
  val eventsBase = cfg.appCfg.lakeEventsBasePath
  val checkpointMalformed = s"$eventsBase/_checkpoints/malformed"
  val checkpointValid = s"$eventsBase/_checkpoints/valid"

  logger.info(s"Loaded configuration. Events base path: $eventsBase")
  logger.info(s"Malformed checkpoint: $checkpointMalformed")
  logger.info(s"Valid checkpoint: $checkpointValid")

  /**
   * Creates Spark session with custom configuration.
   *
   * @param appName identifies this streaming job in Spark UI
   * @param masterOverride allows cluster mode specification via environment
   * @param shufflePartitions controls parallelism for Spark shuffles
   */
  val spark = SparkSessionFactory.create(
    appName = "Pipeline C - Kafka Avro Events Archive to S3",
    masterOverride = Some(sys.env.getOrElse("SPARK_MASTER", "local[*]")),
    shufflePartitions = cfg.sparkCfg.shufflePartitions
  )

  logger.info("Spark session initialized")

  /**
   * Configures S3A filesystem for lake storage writes.
   *
   * Supports custom S3 endpoints (MinIO, custom regions) and AWS credentials.
   * Path style access disabled for better performance with virtual-hosted-style requests.
   */
  val hconf = spark.sparkContext.hadoopConfiguration

  if (cfg.keyspaces.endpoint.nonEmpty) {
    hconf.set("fs.s3a.endpoint", cfg.keyspaces.endpoint)
    logger.info(s"S3 endpoint set to: ${cfg.keyspaces.endpoint}")
  }

  hconf.set("fs.s3a.region", cfg.keyspaces.region)
  hconf.set("fs.s3a.path.style.access", "false")
  hconf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
  hconf.set("fs.s3a.access.key", cfg.keyspaces.accessKey)
  hconf.set("fs.s3a.secret.key", cfg.keyspaces.secretKey)
  hconf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")

  logger.info("S3A credentials and config applied successfully.")

  import spark.implicits._

  /**
   * Initializes Kafka Avro streams, returning (validDF, malformedDF) tuple.
   *
   * KafkaAvroExtractor handles schema registry integration and deserialization,
   * automatically routing malformed records to separate stream.
   */
  logger.info("Initializing Kafka streams...")
  val (valid, malformed) = KafkaAvroExtractor.read(spark)
  logger.info("Kafka stream initialization complete.")

  /**
   * Malformed events stream - processes records that failed Avro deserialization.
   *
   * - Append mode ensures each batch processed exactly once
   * - Independent checkpoint prevents interference with valid stream
   * - Custom MalformedHandler processes raw bytes with batch metadata
   */
  logger.info("Starting malformed events stream...")
  val malformedQuery = malformed.writeStream
    .outputMode("append")
    .trigger(Trigger.ProcessingTime("10 seconds"))
    .option("checkpointLocation", checkpointMalformed)
    .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
      logger.warn(s"Processing malformed batch: $batchId")
      MalformedHandler.foreachBatch(batchDF, batchId)
    }
    .start()

  /**
   * Valid events analytics stream - core business processing pipeline.
   *
   * - Processes successfully deserialized Avro events
   * - AnalyticsProcessor applies transformations and writes to S3 lake
   * - Receives eventsBase path for partitioned S3 output structure
   */
  logger.info("Starting valid events analytics stream...")
  val analyticsQuery = valid.writeStream
    .outputMode("append")
    .trigger(Trigger.ProcessingTime("10 seconds"))
    .option("checkpointLocation", checkpointValid)
    .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
      logger.info(s"Processing valid batch: $batchId")
      AnalyticsProcessor.foreachBatch(batchDF, batchId, eventsBase)
    }
    .start()

  /**
   * Pipeline startup complete - both streams running concurrently.
   *
   * Primary query (analytics) drives termination; malformed stream
   * automatically shuts down when primary terminates.
   */
  logger.info(s"Pipeline C (Avro) started successfully: archiving Kafka events to $eventsBase")

  analyticsQuery.awaitTermination()
  malformedQuery.awaitTermination()
}
