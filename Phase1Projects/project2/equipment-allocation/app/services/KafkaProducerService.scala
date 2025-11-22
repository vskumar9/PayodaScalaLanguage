package services

import play.api.inject.ApplicationLifecycle
import javax.inject._
import play.api.Configuration
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import java.util.Properties
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import play.api.Logger

/**
 * Kafka producer service for publishing events from the application.
 *
 * This service:
 *   - Initializes a KafkaProducer using application configuration.
 *   - Publishes JSON events to configured Kafka topics.
 *   - Provides both synchronous (Future-based) and fire-and-forget publishing.
 *   - Closes the producer safely during application shutdown.
 *
 * Configuration keys:
 * {{{
 *   kafka.bootstrap.servers = "localhost:9092"
 *   kafka.topics.equipment = "equipment-events"
 *   kafka.producer.acks = "all"
 *   kafka.producer.retries = 3
 *   kafka.producer.linger.ms = 5
 * }}}
 *
 * @param config Play configuration
 * @param lifecycle ApplicationLifecycle for graceful shutdown
 * @param ec ExecutionContext for async operations
 */
@Singleton
class KafkaProducerService @Inject()(
                                      config: Configuration,
                                      lifecycle: ApplicationLifecycle
                                    )(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /** Kafka bootstrap servers (e.g., "localhost:9092") */
  private val bootstrap = config.get[String]("kafka.bootstrap.servers")

  /** Sub-configuration for topics */
  private val topicsConf = config.get[Configuration]("kafka.topics")

  /** Topic name for equipment-related events */
  val topicEquipment: String = topicsConf.get[String]("equipment")

  /** Kafka producer configuration */
  private val props = new Properties()
  props.put("bootstrap.servers", bootstrap)
  props.put("key.serializer", classOf[StringSerializer].getName)
  props.put("value.serializer", classOf[StringSerializer].getName)
  props.put("acks",    config.getOptional[String]("kafka.producer.acks").getOrElse("all"))
  props.put("retries", config.getOptional[Int]("kafka.producer.retries").getOrElse(3).toString)
  props.put("linger.ms", config.getOptional[Int]("kafka.producer.linger.ms").getOrElse(5).toString)

  /** Internal Kafka producer instance */
  private val producer = new KafkaProducer[String, String](props)

  /**
   * Publishes an event to the specified Kafka topic.
   *
   * This method blocks internally on `producer.send().get()` but returns a Future
   * so callers do not block the main thread.
   *
   * @param topic Kafka topic to publish to
   * @param eventType logical type of the event (Kafka message key)
   * @param payload JSON payload to publish
   * @return Future containing Kafka RecordMetadata on success
   */
  private def publish(topic: String, eventType: String, payload: JsValue): Future[RecordMetadata] = {
    val json = Json.stringify(payload)
    val record = new ProducerRecord[String, String](topic, eventType, json)

    Future {
      val meta = producer.send(record).get()
      logger.info(s"Published $eventType -> ${meta.topic()} [${meta.partition()}] @ ${meta.offset()}")
      meta
    }
  }

  /**
   * Publish an equipment-related event to the equipment Kafka topic.
   *
   * Uses the event type as the message key, and JSON payload as the value.
   *
   * @param eventType logical event name (e.g., "INVENTORY_UPDATE", "REMINDER")
   * @param payload JSON payload
   * @return Future containing RecordMetadata on success
   */
  def publishEquipment(eventType: String, payload: JsValue): Future[RecordMetadata] =
    publish(topicEquipment, eventType, payload)

  /**
   * Fire-and-forget mode for publishing a Kafka event.
   *
   * The operation is asynchronous and logs failures without returning a Future.
   * Suitable for non-critical logging-style messages.
   *
   * @param topic Kafka topic
   * @param eventType Kafka key
   * @param payload JSON data to publish
   */
  private def publishFireAndForget(topic: String, eventType: String, payload: JsValue): Unit = {
    val json = Json.stringify(payload)
    val rec = new ProducerRecord[String, String](topic, eventType, json)

    producer.send(rec, (meta: RecordMetadata, ex: Exception) => {
      if (ex != null) {
        logger.error(s"Kafka publish failed for $eventType", ex)
      } else {
        logger.info(s"Published $eventType -> ${meta.topic()}@${meta.partition()}:${meta.offset()}")
      }
    })
  }

  /**
   * Registers shutdown hook to close the Kafka producer gracefully.
   *
   * Ensures:
   *   - All pending messages are flushed.
   *   - Any producer exception during close is logged.
   */
  lifecycle.addStopHook(() => Future {
    try {
      logger.info("Closing Kafka producer...")
      producer.flush()
      producer.close()
      logger.info("Kafka producer closed.")
    } catch {
      case ex: Exception =>
        logger.error("Error closing Kafka producer", ex)
    }
  })
}
