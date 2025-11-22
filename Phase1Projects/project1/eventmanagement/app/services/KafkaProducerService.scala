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
 * Kafka producer service responsible for publishing event notifications
 * from the Event Management system to Kafka topics.
 *
 * This service initializes a Kafka producer using configurations defined in
 * `application.conf` and provides helper methods to publish events either
 * synchronously (with metadata) or asynchronously (fire-and-forget).
 *
 * Features:
 *  - Configurable Kafka bootstrap servers, retries, acks, and batching settings
 *  - JSON payload serialization for outbound Kafka messages
 *  - Graceful producer shutdown during application lifecycle stop
 *  - Asynchronous publishing using a blocking Kafka `send().get()` wrapped in a Future
 *  - Fire-and-forget publishing for non-critical operations
 *
 * Expected configuration (example):
 * {{{
 * kafka.bootstrap.servers = "localhost:9092"
 * kafka.topics.eventManagement = "event-mgmt-topic"
 * kafka.producer {
 *   acks       = "all"
 *   retries    = 3
 *   linger.ms  = 5
 * }
 * }}}
 *
 * @param config     Play application configuration
 * @param lifecycle  application lifecycle hook for graceful shutdown
 * @param ec         execution context for asynchronous operations
 */
@Singleton
class KafkaProducerService @Inject()(config: Configuration, lifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /** Kafka connection details loaded from configuration. */
  private val bootstrap = config.get[String]("kafka.bootstrap.servers")
  private val topicsConf = config.get[Configuration]("kafka.topics")

  /** Main application topic where event management messages are published. */
  val topicName: String = topicsConf.get[String]("eventManagement")

  /** Kafka producer configuration properties. */
  private val props = new Properties()
  props.put("bootstrap.servers", bootstrap)
  props.put("key.serializer", classOf[StringSerializer].getName)
  props.put("value.serializer", classOf[StringSerializer].getName)
  props.put("acks",    config.getOptional[String]("kafka.producer.acks").getOrElse("all"))
  props.put("retries", config.getOptional[Int]("kafka.producer.retries").getOrElse(3).toString)
  props.put("linger.ms", config.getOptional[Int]("kafka.producer.linger.ms").getOrElse(5).toString)

  /** Underlying Kafka producer instance. */
  private val producer = new KafkaProducer[String, String](props)

  /**
   * Publishes a message to Kafka and returns a Future containing `RecordMetadata`.
   *
   * This uses a blocking `.get()` call wrapped inside a `Future` because the Kafka
   * Java producer is inherently synchronous when metadata is required.
   *
   * @param topic     Kafka topic
   * @param eventType key used for Kafka partitioning
   * @param payload   JSON payload to serialize and send
   * @return          Future containing Kafka record metadata
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
   * Publishes an event to the default Event Management Kafka topic.
   *
   * @param eventType logical event type (Kafka message key)
   * @param payload   JSON payload
   * @return          Future containing Kafka metadata
   */
  def publishEquipment(eventType: String, payload: JsValue): Future[RecordMetadata] =
    publish(topicName, eventType, payload)

  /**
   * Sends a Kafka message asynchronously without waiting for metadata.
   *
   * Errors are logged, but not propagated. Useful for non-critical publishing.
   *
   * @param topic     Kafka topic
   * @param eventType message key
   * @param payload   JSON data
   */
  private def publishFireAndForget(topic: String, eventType: String, payload: JsValue): Unit = {
    val json = Json.stringify(payload)
    val rec = new ProducerRecord[String, String](topic, eventType, json)

    producer.send(rec, (meta: RecordMetadata, ex: Exception) => {
      if (ex != null) logger.error(s"Kafka publish failed for $eventType", ex)
      else logger.info(s"Published $eventType -> ${meta.topic()}@${meta.partition()}:${meta.offset()}")
    })
  }

  /**
   * Registers a lifecycle stop hook to flush and close the Kafka producer safely.
   */
  lifecycle.addStopHook(() => Future {
    logger.info("Closing Kafka producer...")
    try {
      producer.flush()
      producer.close()
      logger.info("Kafka producer closed.")
    } catch {
      case ex: Exception =>
        logger.error("Error closing Kafka producer", ex)
    }
  })
}
