package events

import akka.actor.{ActorSystem, Props}
import scala.concurrent.duration._
import scala.concurrent.Await
import events.actor.{AvroEventProducerActor, GenerateEvent}
import events.config.ConfigLoader

/**
 * Application entry point for the Avro-based Kafka event producer.
 *
 * This app initializes an Akka ActorSystem and periodically triggers
 * the generation and publishing of synthetic customer events to a Kafka topic
 * using the [[events.actor.AvroEventProducerActor]].
 *
 * The producer’s behavior (topic, rate, target broker, etc.) is configurable
 * via the `application.conf` file under the `app` namespace.
 *
 * Configuration keys (application.conf):
 *  - `app.kafka.topic`: Kafka topic name (e.g., `"customer_events"`).
 *  - `app.kafka.bootstrap`: Kafka bootstrap servers (e.g., `"localhost:9092"`).
 *  - `app.producer.events-per-second`: Number of events to produce per second.
 *  - `app.producer.debug`: Enables debug logging when true.
 *
 * Example run command:
 * {{{
 *   sbt run
 * }}}
 * or with a custom config on the classpath:
 * {{{
 *   sbt -Dconfig.file=conf/custom.conf run
 * }}}
 */
object AvroEventProducerApp extends App {

  /** Loads runtime configuration from application.conf. */
  private val appConfig = ConfigLoader.load()

  /** Initializes the Akka actor system and dispatcher. */
  implicit val system: ActorSystem = ActorSystem("EventGenSystem")
  implicit val ec = system.dispatcher

  /** Creates the event-producing actor instance using config values. */
  private val producer = system.actorOf(
    Props(new AvroEventProducerActor(
      appConfig.kafka.topic,
      appConfig.kafka.bootstrap,
      appConfig.producer.debug,
      appConfig.avro.schemaFile
    ))
  )

  /** Determines the interval based on desired events per second (EPS). */
  private val interval = (1000.0 / appConfig.producer.eventsPerSecond).toLong.millis

  /**
   * Schedules a recurring task to send [[GenerateEvent]] messages to the producer actor
   * at a fixed rate, effectively controlling the event emission frequency.
   */
  private val cancellable =
    system.scheduler.scheduleAtFixedRate(0.millis, interval)(() => producer ! GenerateEvent())

  /**
   * Adds a shutdown hook to gracefully stop event scheduling and terminate
   * the actor system when the application exits.
   */
  sys.addShutdownHook {
    cancellable.cancel()
    system.terminate()
  }

  /** Keeps the application running until manual termination. */
  Await.result(system.whenTerminated, Duration.Inf)
}
