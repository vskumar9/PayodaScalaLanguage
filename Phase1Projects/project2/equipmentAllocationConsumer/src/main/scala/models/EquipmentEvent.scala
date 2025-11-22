package models

import play.api.libs.json._
import java.time.Instant

/**
 * Represents a domain-level event emitted by the equipment system and consumed by various actors.
 *
 * An `EquipmentEvent` bundles:
 *   - `eventType` : A high-level type used for routing (e.g., ALLOCATION, INVENTORY, MAINTENANCE, REMINDER).
 *   - `payload`   : The raw JSON object containing event-specific fields.
 *   - `receivedAt`: Timestamp when the event model instance was created/parsed.
 *
 * This model is used by:
 *   - `KafkaConsumerActor` to wrap Kafka messages.
 *   - Domain actors (`AllocationActor`, `InventoryActor`, `MaintenanceActor`, `ReminderActor`) that route internal
 *     logic based on `eventType` and parse additional details from `payload`.
 *
 * Typical JSON structure consumed from Kafka:
 * {{{
 * {
 *   "eventType": "ALLOCATION",
 *   "allocationId": 42,
 *   "employeeId": 10,
 *   ...
 * }
 * }}}
 *
 * @param eventType  String identifying the event category (case-insensitive, typically uppercased by consumers).
 * @param payload    Raw JSON payload for this event; may contain nested fields.
 * @param receivedAt Timestamp when the event was instantiated (defaults to `Instant.now()`).
 */
case class EquipmentEvent(
                           eventType: String,
                           payload: JsObject,
                           receivedAt: Instant = Instant.now()
                         )

object EquipmentEvent {

  /**
   * Custom JSON deserializer for [[EquipmentEvent]].
   *
   * Behavior:
   *  - Extracts `eventType` from the top-level JSON.
   *  - Attempts to interpret the incoming JSON as a full `JsObject`.
   *  - If the incoming JSON is not a pure object, wraps it inside:
   *      `{ "payload": <original json> }`
   *  - Produces a new `EquipmentEvent` with `receivedAt = Instant.now()`.
   *
   * Example accepted inputs:
   * {{{
   * { "eventType": "REMINDER", "allocationId": 55 }
   *
   * { "eventType": "MAINTENANCE", "ticketId": 99, "status": "OPEN" }
   * }}}
   */
  implicit val reads: Reads[EquipmentEvent] = new Reads[EquipmentEvent] {
    def reads(json: JsValue): JsResult[EquipmentEvent] = {
      for {
        et <- (json \ "eventType").validate[String].map(_.trim)
        payload <- json.validate[JsObject].orElse(JsSuccess(Json.obj("payload" -> json)))
      } yield EquipmentEvent(et, payload.as[JsObject], Instant.now())
    }
  }

  /**
   * JSON serializer for [[EquipmentEvent]].
   *
   * Produces JSON of the form:
   * {{{
   * {
   *   "eventType": "...",
   *   "payload": { ... },
   *   "receivedAt": "2025-11-22T12:45:54Z"
   * }
   * }}}
   */
  implicit val writes: OWrites[EquipmentEvent] = Json.writes[EquipmentEvent]
}
