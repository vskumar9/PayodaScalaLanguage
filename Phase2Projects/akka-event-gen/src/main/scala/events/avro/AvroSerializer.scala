package events.avro

import java.io.ByteArrayOutputStream
import org.apache.avro.generic.{GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory

/**
 * Utility object for serializing Avro [[org.apache.avro.generic.GenericRecord]]
 * instances into binary byte arrays.
 *
 * This serializer is commonly used before sending Avro-encoded messages
 * to systems like Kafka or writing them to files. It uses Avro’s
 * [[org.apache.avro.io.EncoderFactory]] and [[org.apache.avro.generic.GenericDatumWriter]]
 * to perform efficient binary encoding.
 *
 * Typical usage:
 * {{{
 *   val serializedBytes = AvroSerializer.serialize(schema, record)
 * }}}
 */
object AvroSerializer {

  /**
   * Serializes a given Avro [[org.apache.avro.generic.GenericRecord]] into
   * a binary byte array using the provided schema.
   *
   * @param schema The Avro schema describing the structure of the record.
   * @param rec The Avro record to serialize.
   * @return A byte array containing the serialized Avro data.
   */
  def serialize(schema: org.apache.avro.Schema, rec: GenericRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(baos, null)
    new GenericDatumWriter[GenericRecord](schema).write(rec, encoder)
    encoder.flush()
    baos.toByteArray
  }
}
