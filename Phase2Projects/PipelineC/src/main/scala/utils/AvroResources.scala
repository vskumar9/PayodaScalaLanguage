package utils

import org.apache.avro.Schema
import scala.util.Try

/**
 * Avro schema resource loader for Kafka event deserialization.
 *
 * Loads and parses the canonical customer_event.avsc schema from classpath resources.
 * Provides both raw schema string (for Spark from_avro) and parsed Schema (for Avro readers).
 *
 * Usage in pipeline:
 * - KafkaAvroExtractor: `from_avro(col("value"), AvroResources.schemaString, options)`
 * - MalformedHandler: `new GenericDatumReader(AvroResources.schema)`
 */
object AvroResources {

  /**
   * Raw Avro schema as string for Spark SQL `from_avro` function.
   *
   * Loaded from `customer_event.avsc` classpath resource (src/main/resources).
   *
   * @throws RuntimeException if schema file missing from classpath
   *                          (fail-fast at pipeline startup)
   */
  lazy val schemaString: String =
    Try(scala.io.Source.fromResource("customer_event.avsc").mkString)
      .getOrElse(throw new RuntimeException("customer_event.avsc not found on classpath"))

  /**
   * Parsed Avro Schema object for programmatic Avro readers/writers.
   *
   * Used by MalformedHandler for diagnostic decoding attempts.
   * Automatically parsed from schemaString on first access.
   */
  lazy val schema: Schema = new Schema.Parser().parse(schemaString)
}
