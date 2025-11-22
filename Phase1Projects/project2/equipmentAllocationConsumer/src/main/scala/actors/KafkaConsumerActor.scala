package actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.kafka.clients.consumer.KafkaConsumer
import play.api.libs.json._
import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Companion object for [[KafkaConsumerActor]].
 * Provides factory method for creating the actor along with messages it handles.
 */
object KafkaConsumerActor {

  /**
   * Creates a Props instance for the [[KafkaConsumerActor]].
   *
   * @param allocationActor  Actor responsible for handling allocation-related events.
   * @param inventoryActor   Actor responsible for inventory and equipment update events.
   * @param maintenanceActor Actor for handling maintenance/damage events.
   * @param reminderActor    Actor responsible for reminder/notification events.
   * @return Props for initializing the actor.
   */
  def props(
             allocationActor: ActorRef,
             inventoryActor: ActorRef,
             maintenanceActor: ActorRef,
             reminderActor: ActorRef
           ): Props =
    Props(new KafkaConsumerActor(
      allocationActor,
      inventoryActor,
      maintenanceActor,
      reminderActor
    ))

  /** Message sent internally to trigger a Kafka poll cycle. */
  case object Poll
}

/**
 * Kafka consumer actor responsible for:
 *   - Polling the `equipment-events` Kafka topic
 *   - Parsing event messages as JSON
 *   - Determining routing keys from either Kafka key or JSON `eventType`
 *   - Forwarding events to domain-specific actors (Allocation, Inventory, Maintenance, Reminder)
 *
 * The actor continuously schedules itself to poll Kafka every 500ms.
 *
 * @param allocationActor  ActorRef for handling `ALLOCATION` events.
 * @param inventoryActor   ActorRef for `INVENTORY` or `EQUIPMENT` events.
 * @param maintenanceActor ActorRef for `MAINTENANCE` or `DAMAGED` events.
 * @param reminderActor    ActorRef for `REMINDER` events.
 */
class KafkaConsumerActor(
                          allocationActor: ActorRef,
                          inventoryActor: ActorRef,
                          maintenanceActor: ActorRef,
                          reminderActor: ActorRef
                        ) extends Actor
  with ActorLogging {

  import KafkaConsumerActor._

  implicit val ec: ExecutionContext = context.dispatcher

  /**
   * Kafka consumer configuration:
   *   - Connects to localhost:9092
   *   - Uses console-consumer-like group ID
   *   - Deserializes key/value as strings
   *   - Starts reading from earliest offset if no committed offsets exist
   */
  private val props = new java.util.Properties()
  props.put("bootstrap.servers", "localhost:9092")
  props.put("group.id", "console-consumer-93068")
  props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  props.put("auto.offset.reset", "earliest")

  /** The underlying KafkaConsumer instance. */
  private val consumer = new KafkaConsumer[String, String](props)

  /** Subscribes to `equipment-events` topic on actor startup. */
  consumer.subscribe(java.util.Collections.singletonList("equipment-events"))

  /** Starts polling loop by sending itself a Poll message. */
  override def preStart(): Unit = self ! Poll

  /** Ensures Kafka consumer is closed when actor stops. */
  override def postStop(): Unit = consumer.close()

  /**
   * Primary message handler.
   *
   * Handles:
   *   - `Poll`: Fetches messages from Kafka, parses JSON,
   *             determines event routing key, and forwards events.
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
                // Extract Kafka key if present and non-empty
                val rawKeyOpt = Option(rec.key()).map(_.trim).filter(_.nonEmpty)

                // Determine routing key from Kafka key or JSON eventType
                val routeKey = rawKeyOpt
                  .map(k => k.split(":").headOption.getOrElse(k).toUpperCase)
                  .orElse((obj \ "eventType").asOpt[String].map(_.trim.toUpperCase))
                  .getOrElse("UNKNOWN")

                val ev = models.EquipmentEvent(routeKey, obj)

                log.info(
                  s"[KafkaConsumerActor] Routing by key='$rawKeyOpt' -> routeKey='$routeKey' (offset=${rec.offset()})"
                )

                // Route event to appropriate actor
                routeKey match {
                  case "ALLOCATION" =>
                    allocationActor ! AllocationActor.HandleEvent(ev)

                  case "INVENTORY" | "EQUIPMENT" =>
                    inventoryActor ! InventoryActor.HandleEvent(ev)

                  case "MAINTENANCE" | "DAMAGED" =>
                    maintenanceActor ! MaintenanceActor.HandleEvent(ev)

                  case "REMINDER" =>
                    reminderActor ! ReminderActor.HandleEvent(ev)

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
        // Schedule next polling cycle
        context.system.scheduler.scheduleOnce(500.milliseconds, self, Poll)
      }
  }
}
