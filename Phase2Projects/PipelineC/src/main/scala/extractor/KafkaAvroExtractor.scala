package extractor

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.util
import scala.jdk.CollectionConverters._
import org.apache.spark.sql.avro.functions.from_avro

import config.PipelineConfiguration
import utils.AvroResources

/**
 * Kafka Avro event extractor for Structured Streaming pipelines.
 *
 * Reads raw Kafka messages, deserializes Avro payloads using schema registry,
 * and separates records into valid/malformed streams for downstream processing.
 *
 * Key features:
 * - PERMISSIVE Avro mode handles schema evolution gracefully
 * - Automatic validation by required fields (event_id, event_type)
 * - Data cleansing and timestamp normalization
 * - Preserves raw value for malformed record debugging
 */
object KafkaAvroExtractor {

  /**
   * Avro deserialization options for schema-flexible processing.
   *
   * @note PERMISSIVE mode sets nulls for unresolvable fields instead of failing
   */
  private val avroOptions: util.Map[String, String] = Map("mode" -> "PERMISSIVE").asJava

  /**
   * Reads Kafka topic and returns (validDF, malformedDF) tuple for dual-stream processing.
   *
   * @param spark Active SparkSession with necessary Kafka/S3 dependencies
   * @return Tuple of DataFrames: (valid events, malformed events)
   */
  def read(spark: SparkSession): (DataFrame, DataFrame) = {
    import spark.implicits._

    /**
     * Kafka source configuration from centralized config.
     *
     * @note startingOffsets="latest" processes only new events after pipeline start
     * @note failOnDataLoss="false" prevents restart failures from topic truncation
     */
    val kafkaCfg = PipelineConfiguration.kafka

    /**
     * Raw Kafka stream with value extraction and timestamp capture.
     *
     * Selects only required columns to minimize memory footprint:
     * - value: raw Avro bytes for deserialization
     * - timestamp: Kafka message timestamp for event time processing
     */
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaCfg.bootstrapServers)
      .option("subscribe", kafkaCfg.customerEventsTopic)
      .option("startingOffsets", "latest")
      .option("failOnDataLoss", "false")
      .load()
      .select(col("value"), col("timestamp").as("kafka_ts"))

    /**
     * Avro deserialization layer using from_avro with schema registry.
     *
     * - Decodes binary value into structured event schema
     * - Preserves raw value and Kafka timestamp for debugging/fallback
     * - Expands nested Avro struct into top-level columns
     */
    val decoded = kafkaDF
      .select(from_avro(col("value"), AvroResources.schemaString, avroOptions).as("e"),
        col("value"), col("kafka_ts"))
      .selectExpr("e.*", "value", "kafka_ts")

    /**
     * Malformed events filter - records missing required business keys.
     *
     * Captures deserialization failures and data quality issues:
     * - Null/empty event_id (business primary key)
     * - Null/empty event_type (routing key)
     *
     * Preserves raw value for forensic analysis in MalformedHandler.
     */
    val malformed = decoded.filter(col("event_id").isNull || col("event_type").isNull)

    /**
     * Valid events processing pipeline with data cleansing and enrichment.
     *
     * Transformations applied in sequence:
     * 1. Validate and nullify invalid product_id (< 0)
     * 2. Parse event_timestamp string to proper timestamp
     * 3. Fallback to Kafka timestamp if parsing fails
     * 4. Add event_date partition column for S3 optimization
     * 5. Add ingestion_timestamp for data lineage
     * 6. Drop raw value and temporary columns
     */
    val valid = decoded.filter(col("event_id").isNotNull && col("event_type").isNotNull)
      .withColumn("product_id",
        when(col("product_id").isNull || col("product_id") < 0, lit(null).cast("int"))
          .otherwise(col("product_id")))
      .withColumn("event_timestamp_parsed", to_timestamp(col("event_timestamp")))
      .withColumn("event_timestamp",
        coalesce(col("event_timestamp_parsed"), col("kafka_ts")))
      .drop("event_timestamp_parsed", "kafka_ts")
      .withColumn("event_date", to_date(col("event_timestamp")))
      .withColumn("ingestion_timestamp", current_timestamp())
      .drop("value")

    (valid, malformed)
  }
}
