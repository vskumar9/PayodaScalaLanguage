package events.actor

import akka.actor.Actor
import org.apache.avro.generic.GenericData
import events.avro.{AvroSchemaLoader, AvroSerializer}
import events.kafka.KafkaProducerFactory
import events.util.RandomEventGenerator
import org.apache.kafka.clients.producer.{ProducerRecord, Callback, RecordMetadata}
import java.time.Instant

/** Message to trigger event generation and publishing to Kafka. */
case class GenerateEvent()

/**
 * An Akka actor responsible for producing Avro-encoded customer events
 * and publishing them to a Kafka topic.
 *
 * This actor:
 *  - Loads the Avro schema for customer events.
 *  - Randomly generates event data using [[RandomEventGenerator]].
 *  - Serializes the data into Avro format using [[AvroSerializer]].
 *  - Publishes the serialized event to Kafka using a producer instance
 *    created from [[KafkaProducerFactory]].
 *
 * @param topic The Kafka topic to which events will be published.
 * @param bootstrap The Kafka bootstrap servers (host:port).
 * @param debug If true, logs sent message details for debugging.
 */
class AvroEventProducerActor(
                              topic: String,
                              bootstrap: String,
                              debug: Boolean,
                              schemaFile: String
                            )
  extends Actor {

  /** The loaded Avro schema for the customer event type. */
  private val schema = AvroSchemaLoader.load(schemaFile)

  /** The schema for the event_type enum field within the main Avro schema. */
  private val eventTypeSchema = schema.getField("event_type").schema()

  /** The Kafka producer instance used to send serialized events. */
  private val producer = KafkaProducerFactory.create(bootstrap)

  /** Ensures the Kafka producer is properly closed when the actor stops. */
  override def postStop(): Unit = producer.close()

  /**
   * Handles received messages. When a [[GenerateEvent]] message arrives,
   * a new event is created, serialized, and sent to Kafka.
   */
  def receive: Receive = {
    case GenerateEvent() => produce()
  }

  /**
   * Generates a random event record, serializes it to Avro format, and publishes it to Kafka.
   *
   * The produced record includes:
   *  - event_id: Unique identifier for the event
   *  - customer_id: Random customer ID
   *  - event_type: Randomly selected event type (e.g., CLICK, PURCHASE)
   *  - product_id: Optional product ID based on event type
   *  - event_timestamp: Current timestamp (ISO-8601 format)
   */
  private def produce(): Unit = {
    val eventId = RandomEventGenerator.eventId()
    val custId = RandomEventGenerator.customerId()
    val etype = RandomEventGenerator.eventType()
    val productId = RandomEventGenerator.maybeProductId(etype)

    // Build the Avro record based on the loaded schema
    val rec = new GenericData.Record(schema)
    rec.put("event_id", eventId)
    rec.put("customer_id", custId)
    rec.put("event_type", new GenericData.EnumSymbol(eventTypeSchema, etype))
    rec.put("product_id", productId.map(Int.box).orNull)
    rec.put("event_timestamp", Instant.now().toString)

    // Serialize and send to Kafka
    val bytes = AvroSerializer.serialize(schema, rec)
    val record = new ProducerRecord[String, Array[Byte]](topic, eventId, bytes)

    producer.send(record, new Callback {
      override def onCompletion(meta: RecordMetadata, ex: Exception): Unit = {
        if (debug && ex == null)
          println(s"[Sent] $eventId -> partition=${meta.partition()} offset=${meta.offset()}")
      }
    })
  }
}
