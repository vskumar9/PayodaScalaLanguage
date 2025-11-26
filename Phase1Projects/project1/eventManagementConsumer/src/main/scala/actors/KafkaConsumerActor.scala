package actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.kafka.clients.consumer.KafkaConsumer
import play.api.libs.json._
import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.util.Properties
import com.typesafe.config.ConfigFactory

/**
 * Companion object for [[KafkaConsumerActor]], providing factory methods and protocol messages.
 */
object KafkaConsumerActor {

  /**
   * Creates Props for a KafkaConsumerActor instance.
   *
   * @param eventActor         actor responsible for handling EVENT-related messages
   * @param teamActor          actor responsible for handling TEAM-related messages
   * @param taskActor          actor responsible for handling TASK-related messages
   * @param notificationActor  actor responsible for handling NOTIFICATION-related messages
   * @return Props configured for KafkaConsumerActor
   */
  def props(
             eventActor: ActorRef,
             teamActor: ActorRef,
             taskActor: ActorRef,
             notificationActor: ActorRef
           ): Props =
    Props(new KafkaConsumerActor(
      eventActor,
      teamActor,
      taskActor,
      notificationActor
    ))

  /**
   * Periodic polling message used internally by the KafkaConsumerActor.
   * A self-sent tick that triggers Kafka consumer polling.
   */
  case object Poll
}

/**
 * KafkaConsumerActor is responsible for:
 *
 *  - Subscribing to a Kafka topic (`eventManagement`)
 *  - Polling records periodically
 *  - Parsing JSON payloads from the consumer records
 *  - Determining routing key from either:
 *      1. Kafka record key, or
 *      2. JSON "eventType"
 *  - Creating an `EquipmentEvent` wrapper
 *  - Forwarding messages to the appropriate domain actor:
 *      - EVENT → EventActor
 *      - TEAM → TeamActor
 *      - TASK → TaskActor
 *      - NOTIFICATION → NotificationActor
 *
 *  It also performs full error logging and safeguards against malformed JSON.
 *
 *  The actor uses a fixed scheduler interval (500ms) to continuously poll Kafka.
 *
 * @param eventActor         reference to event handler actor
 * @param teamActor          reference to team handler actor
 * @param taskActor          reference to task handler actor
 * @param notificationActor  reference to notification handler actor
 */
class KafkaConsumerActor(
                          eventActor: ActorRef,
                          teamActor: ActorRef,
                          taskActor: ActorRef,
                          notificationActor: ActorRef
                        ) extends Actor with ActorLogging {

  import KafkaConsumerActor._

  /** ExecutionContext used for scheduling. */
  implicit val ec: ExecutionContext = context.dispatcher

  private val config = ConfigFactory.load().getConfig("kafka")

  // read values with safe fallbacks
  private val bootstrapServers = Option(config.getString("bootstrap-servers")).filter(_.nonEmpty)
    .orElse(Option(System.getenv("KAFKA_BOOTSTRAP_SERVERS")))
    .getOrElse(throw new IllegalStateException("KAFKA bootstrap servers not configured"))

  private val groupId = Option(config.getString("group-id")).filter(_.nonEmpty)
    .orElse(Option(System.getenv("KAFKA_CONSUMER_GROUP_ID")))
    .getOrElse("eventManagementGroup")

  private val topic = Option(config.getString("topic")).filter(_.nonEmpty)
    .orElse(Option(System.getenv("KAFKA_CONSUMER_TOPIC")))
    .getOrElse("eventManagement")

  private val autoOffsetReset = Option(config.getString("auto-offset-reset")).filter(_.nonEmpty)
    .orElse(Option(System.getenv("KAFKA_AUTO_OFFSET_RESET")))
    .getOrElse("earliest")

//  private val props = new java.util.Properties()
//  props.put("bootstrap.servers", "localhost:9092")
//  props.put("group.id", "eventManagementGroup")
//  props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
//  props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
//  props.put("auto.offset.reset", "earliest")

  private val props = new Properties()
  props.put("bootstrap.servers", bootstrapServers)
  props.put("group.id", groupId)
  props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  props.put("auto.offset.reset", autoOffsetReset)

  private val consumer = new KafkaConsumer[String, String](props)
  consumer.subscribe(java.util.Collections.singletonList(topic))

  override def preStart(): Unit = {
    log.info(s"[KafkaConsumerActor] Starting consumer for topic=$topic group=$groupId brokers=$bootstrapServers")
    self ! Poll
  }

  /** Underlying Kafka consumer instance. */
//  private val consumer = new KafkaConsumer[String, String](props)

  // Subscribe to the eventManagement topic
//  consumer.subscribe(java.util.Collections.singletonList("eventManagement"))

  /**
   * Called when the actor starts.
   * Immediately schedules the first Poll message.
   */
//  override def preStart(): Unit = self ! Poll

  /**
   * Called when the actor stops.
   * Closes Kafka consumer safely.
   */
  override def postStop(): Unit = consumer.close()

  /**
   * Main actor receive loop. Handles:
   *
   *  - Poll → execute Kafka poll
   *  - Route records to domain-specific actors
   *  - Parse JSON safely and log malformed entries
   */
  override def receive: Receive = {
    case Poll =>
      try {
        val records = consumer.poll(java.time.Duration.ofMillis(500))

        for (rec <- records.asScala) {
          try {
            val json = Json.parse(rec.value())
            log.info(s"[KafkaConsumerActor] Routing by json=$json")

            json match {
              case obj: JsObject =>
                // Extract routing key: record.key OR JSON eventType
                val rawKeyOpt = Option(rec.key()).map(_.trim).filter(_.nonEmpty)

                val routeKey = rawKeyOpt
                  .map(k => k.split(":").headOption.getOrElse(k).toUpperCase)
                  .orElse((obj \ "eventType").asOpt[String].map(_.trim.toUpperCase))
                  .getOrElse("UNKNOWN")

                val ev = models.EquipmentEvent(routeKey, obj)

                log.info(
                  s"[KafkaConsumerActor] Routing by key='$rawKeyOpt' -> routeKey='$routeKey' (offset=${rec.offset()})"
                )

                // Pattern match on routing key
                routeKey match {

                  case "EVENT" =>
                    eventActor ! EventActor.HandleEvent(ev)

                  case "TEAM" =>
                    teamActor ! TeamActor.HandleEvent(ev)

                  case "TASK" =>
                    taskActor ! TaskActor.HandleEvent(ev)

                  case "NOTIFICATION" =>
                    notificationActor ! NotificationActor.HandleEvent(ev)

                  case other =>
                    log.warning(
                      s"[KafkaConsumerActor] Unknown routeKey=$other — skipping event (offset=${rec.offset()})"
                    )
                }

              case _ =>
                log.error(
                  s"[KafkaConsumerActor] Invalid JSON object at offset=${rec.offset()}: ${rec.value()}"
                )
            }

          } catch {
            case ex: Throwable =>
              log.error(
                ex,
                s"[KafkaConsumerActor] Failed to parse/process record at offset=${rec.offset()}: ${rec.value()}"
              )
          }
        }

      } catch {
        case ex: Throwable =>
          log.error(ex, "[KafkaConsumerActor] Polling error")
      } finally {
        // Schedule the next poll tick after 500ms
        context.system.scheduler.scheduleOnce(500.milliseconds, self, Poll)
      }
  }
}
