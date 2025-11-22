package models

import play.api.libs.json._
import java.time.Instant

/**
 * Represents a domain event related to equipment operations.
 *
 * @param eventType  The type/category of the event (e.g., "ALLOCATED", "RETURNED", "CREATED").
 * @param payload    A JSON object containing event-specific data.
 * @param receivedAt The timestamp when the event was received or created. Defaults to the current instant.
 */
case class EquipmentEvent(
                           eventType: String,
                           payload: JsObject,
                           receivedAt: Instant = Instant.now()
                         )

object EquipmentEvent {

  /**
   * Custom JSON reader for [[EquipmentEvent]].
   *
   * This reader performs the following:
   *   - Extracts and trims the `eventType` field.
   *   - Attempts to parse the entire JSON object as the event `payload`.
   *     If parsing fails, it falls back to wrapping the raw JSON inside a `"payload"` field.
   *   - Automatically sets `receivedAt` to the current timestamp.
   *
   * Example accepted JSON formats:
   * {{{
   * {
   *   "eventType": "ALLOCATED",
   *   "equipmentId": 123,
   *   "employeeId": 45
   * }
   *
   * {
   *   "eventType": "UPDATED",
   *   "payload": { "field": "value" }
   * }
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
   * JSON writer for [[EquipmentEvent]].
   *
   * Serializes the event into a JSON object with fields:
   *   - `eventType`
   *   - `payload`
   *   - `receivedAt`
   */
  implicit val writes: OWrites[EquipmentEvent] = Json.writes[EquipmentEvent]
}
