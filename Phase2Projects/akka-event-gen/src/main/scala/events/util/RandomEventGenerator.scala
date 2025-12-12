package events.util

import java.util.UUID
import scala.util.Random

/**
 * Utility object for generating random event data for testing or simulation.
 *
 * This generator produces synthetic event information such as customer IDs,
 * event types, and optional product IDs. It is mainly used by event producer
 * actors (e.g., [[events.actor.AvroEventProducerActor]]) to simulate real-world
 * user activity before sending events to Kafka.
 *
 * Typical use case:
 * {{{
 *   val eventId = RandomEventGenerator.eventId()
 *   val eventType = RandomEventGenerator.eventType()
 *   val productId = RandomEventGenerator.maybeProductId(eventType)
 * }}}
 */
object RandomEventGenerator {

  /** Shared random number generator instance. */
  private val rand = new Random()

  /**
   * Generates a random or fixed customer ID.
   *
   * @return An integer representing a customer ID.
   */
  def customerId(): Int = rand.nextInt(5000) + 1

  /**
   * Generates a unique event ID (UUID).
   *
   * @return A string representation of a new UUID.
   */
  def eventId(): String = UUID.randomUUID().toString

  /**
   * Randomly selects an event type from a fixed set of user actions.
   *
   * The event types simulate typical e-commerce interactions:
   *  - `"LIKE"`
   *  - `"WISHLIST"`
   *  - `"CART_ADD"`
   *
   * @return A string representing the selected event type.
   */
  def eventType(): String =
    rand.nextInt(3) match {
      case 0 => "LIKE"
      case 1 => "WISHLIST"
      case _ => "CART_ADD"
    }

  /**
   * Optionally generates a product ID depending on the event type.
   *
   * - For `"LIKE"` and `"WISHLIST"`, there’s a 10% chance of not having
   *   an associated product ID.
   * - For `"CART_ADD"`, a product ID is always generated.
   *
   * @param eventType The event type used to determine if a product ID applies.
   * @return An optional integer representing a product ID.
   */
  def maybeProductId(eventType: String): Option[Int] = eventType match {
    case "LIKE" | "WISHLIST" =>
      if (rand.nextDouble() < 0.1) None else Some(rand.nextInt(500) + 1)
    case "CART_ADD" => Some(rand.nextInt(500) + 1)
  }
}
