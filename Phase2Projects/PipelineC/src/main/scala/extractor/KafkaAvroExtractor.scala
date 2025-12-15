package extractor

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.util
import scala.jdk.CollectionConverters._
import org.apache.spark.sql.avro.functions.from_avro
import config.PipelineConfiguration
import utils.AvroResources
import extractor.MySQLReferenceLoader._
import org.apache.spark.sql.functions.broadcast

/**
 * Kafka Avro event extractor for customer event streaming pipeline.
 *
 * Reads Kafka topic with Avro-encoded customer events, performs schema validation,
 * reference data enrichment, and splits into valid/malformed streams for downstream processing.
 *
 * ## Processing Pipeline
 * 1. Kafka Structured Streaming source → Avro deserialization
 * 2. MySQL reference data broadcast joins (customer/product validation)
 * 3. Event completeness validation → Split valid/malformed
 * 4. Timestamp normalization and metadata enrichment
 *
 * ## Output Streams
 * - `valid`: Clean events ready for lake storage/transformation
 * - `malformed`: Events for dead letter queue/error handling
 */
object KafkaAvroExtractor {

  /**
   * Avro deserialization configuration for schema evolution tolerance.
   *
   * @note `PERMISSIVE` mode ignores corrupt records instead of failing
   */
  private val avroOptions: util.Map[String, String] =
    Map("mode" -> "PERMISSIVE").asJava

  /**
   * Main extraction method returning parallel valid/malformed DataFrame streams.
   *
   * Processes Kafka customer events through full validation pipeline:
   * - Avro schema decoding with error tolerance
   * - Customer/product reference validation via broadcast joins
   * - Event completeness checks (required fields + reference existence)
   * - Timestamp parsing with Kafka timestamp fallback
   *
   * @param spark Active SparkSession with Structured Streaming enabled
   * @return Tuple of (valid events DataFrame, malformed events DataFrame)
   */
  def read(spark: SparkSession): (DataFrame, DataFrame) = {
    import spark.implicits._

    val kafkaCfg = PipelineConfiguration.kafka

    /** -------------------------------
     * 1. Read Kafka Structured Stream
     * --------------------------------
     * Connects to customer events topic with latest offset positioning.
     * Captures raw value bytes and Kafka metadata timestamp.
     */
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaCfg.bootstrapServers)
      .option("subscribe", kafkaCfg.customerEventsTopic)
      .option("startingOffsets", "latest")
      .option("failOnDataLoss", "false")
      .load()
      .select(col("value"), col("timestamp").as("kafka_ts"))

    /** -------------------------------
     * 2. Avro Schema Decoding
     * --------------------------------
     * Applies registered Avro schema to deserialize binary events.
     * Handles schema evolution via PERMISSIVE mode.
     */
    val decoded = kafkaDF
      .select(
        from_avro(col("value"), AvroResources.schemaString, avroOptions).as("e"),
        col("value"),
        col("kafka_ts")
      )
      .selectExpr("e.*", "value", "kafka_ts")

    /** -------------------------------
     * 3. Reference Data Broadcast Joins
     * --------------------------------
     * Loads MySQL customer/product lookup tables as broadcast DataFrames
     * for efficient streaming enrichment (small table optimization).
     */
    val customersRef =
      broadcast(loadCustomers(spark))
        .withColumnRenamed("customer_id", "ref_customer_id")

    val productsRef =
      broadcast(loadProducts(spark))
        .withColumnRenamed("product_id", "ref_product_id")

    /** -------------------------------
     * 4. Reference Enrichment & Validation
     * --------------------------------
     * Left joins add reference existence flags for downstream filtering.
     * Broadcast joins ensure streaming performance (no shuffle).
     */
    val enriched = decoded
      // Validate customer existence
      .join(
        customersRef,
        decoded("customer_id") === customersRef("ref_customer_id"),
        "left"
      )
      // Validate product existence
      .join(
        productsRef,
        decoded("product_id") === productsRef("ref_product_id"),
        "left"
      )
      // Set validation flags
      .withColumn("customer_valid", col("ref_customer_id").isNotNull)
      .withColumn("product_valid", col("ref_product_id").isNotNull)
      .drop("ref_customer_id", "ref_product_id")

    /** -------------------------------
     * 5. Malformed Event Detection
     * --------------------------------
     * Filters events missing critical fields or failing reference validation.
     * Routes to dead letter queue for error analysis.
     */
    val malformed = enriched.filter(
      col("event_id").isNull ||
        col("event_type").isNull ||
        !col("customer_valid") ||
        !col("product_valid")
    )

    /** -------------------------------
     * 6. Valid Event Processing
     * --------------------------------
     * Final transformations for clean streaming output:
     * - Invalid product_id correction (negative → null)
     * - Event timestamp parsing with Kafka fallback
     * - Partitioning date extraction
     * - Ingestion metadata
     */
    val valid = enriched.filter(
        col("event_id").isNotNull &&
          col("event_type").isNotNull &&
          col("customer_valid") &&
          col("product_valid")
      )
      .withColumn(
        "product_id",
        when(col("product_id") < 0, lit(null).cast("int"))
          .otherwise(col("product_id"))
      )
      .withColumn("event_timestamp_parsed", to_timestamp(col("event_timestamp")))
      .withColumn(
        "event_timestamp",
        coalesce(col("event_timestamp_parsed"), col("kafka_ts"))
      )
      .drop("event_timestamp_parsed", "kafka_ts")
      .withColumn("event_date", to_date(col("event_timestamp")))
      .withColumn("ingestion_timestamp", current_timestamp())
      .drop("value", "customer_valid", "product_valid")

    (valid, malformed)
  }
}
