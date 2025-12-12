package events.avro

import org.apache.avro.Schema

/**
 * Utility object responsible for loading Avro schemas from the application's
 * classpath resources.
 *
 * This loader reads an Avro schema file (e.g., `customer_event.avsc`) from
 * the resources directory, parses it into an [[org.apache.avro.Schema]] object,
 * and returns it for use in Avro serialization and deserialization.
 *
 * Typical usage:
 * {{{
 *   val schema = AvroSchemaLoader.load("schemas/customer_event.avsc")
 * }}}
 */
object AvroSchemaLoader {

  /**
   * Loads and parses an Avro schema from the given resource path.
   *
   * @param resource The relative path of the schema file within the application's resources.
   *                 For example, `"customer_event.avsc"`.
   * @return A parsed [[org.apache.avro.Schema]] instance representing the Avro schema.
   * @throws RuntimeException If the specified schema file is not found in the classpath resources.
   */
  def load(resource: String): Schema = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(throw new RuntimeException(s"$resource not found in resources"))

    val schemaString = scala.io.Source.fromInputStream(stream).mkString
    new Schema.Parser().parse(schemaString)
  }
}
