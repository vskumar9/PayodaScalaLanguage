package events.kafka

import java.util.Properties
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.ByteArraySerializer

/**
 * Factory object for creating and configuring Kafka producer instances.
 *
 * This utility simplifies Kafka producer creation by encapsulating common
 * configuration properties such as serializers, acknowledgment strategy,
 * and batching behavior. It’s primarily used by event producers (e.g.,
 * [[events.actor.AvroEventProducerActor]]) to send serialized events to Kafka.
 *
 * Typical usage:
 * {{{
 *   val producer = KafkaProducerFactory.create("localhost:9092")
 *   producer.send(new ProducerRecord("topic", key, value))
 * }}}
 */
object KafkaProducerFactory {

  /**
   * Creates a configured [[org.apache.kafka.clients.producer.KafkaProducer]]
   * instance for sending String keys and binary (Array[Byte]) values.
   *
   * @param bootstrap The Kafka bootstrap servers (host:port list),
   *                  used to connect the producer to the Kafka cluster.
   * @return A ready-to-use Kafka producer with sensible default configurations.
   *
   * Configuration details:
   *  - `bootstrap.servers`: Kafka cluster to connect to.
   *  - `key.serializer`: String key serializer.
   *  - `value.serializer`: Byte array serializer for message payloads.
   *  - `acks`: Set to `"all"` to ensure durability (waits for full commit).
   *  - `linger.ms`: Adds a short delay to batch multiple records (default: 5ms).
   *  - `batch.size`: Maximum batch size in bytes (default: 32 KB).
   */
  def create(bootstrap: String): KafkaProducer[String, Array[Byte]] = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrap)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", classOf[ByteArraySerializer].getName)
    props.put("acks", "all")
    props.put("linger.ms", "5")
    props.put("batch.size", (32 * 1024).toString)

    new KafkaProducer[String, Array[Byte]](props)
  }
}
