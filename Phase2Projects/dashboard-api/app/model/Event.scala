package model

/**
 * Single behavioral event record from the customer analytics stream.
 *
 * Represents one row from the S3 lakehouse `lake/events/event_date=YYYY-MM-DD/` Parquet files.
 * Captures user interactions like views, cart adds, purchases, etc.
 *
 * Field mapping matches Parquet schema exactly:
 * - event_id:            STRING (UUID)
 * - customer_id:         INT32
 * - event_type:          STRING (e.g. "CART_ADD", "VIEW", "PURCHASE")
 * - product_id:          INT32 (optional)
 * - event_timestamp:     INT96 timestamp (12-byte binary)
 * - ingestion_timestamp: INT96 timestamp (12-byte binary)
 */
case class Event(
                  /**
                   * Unique event identifier (UUID as string).
                   */
                  event_id:            String,

                  /**
                   * Customer identifier.
                   * INT32 from Parquet.
                   */
                  customer_id:         Int,

                  /**
                   * Type of behavioral event.
                   * Examples: "VIEW", "CART_ADD", "PURCHASE", "CART_REMOVE".
                   */
                  event_type:          String,

                  /**
                   * Product identifier associated with the event (optional).
                   * Null for events without product context (e.g. login events).
                   */
                  product_id:          Option[Int],

                  /**
                   * Event occurrence timestamp as Parquet INT96 (12-byte binary).
                   *
                   * Layout (little-endian):
                   * - bytes 0-7: nanoseconds since midnight
                   * - bytes 8-11: Julian day number
                   *
                   * Decode using `int96ToInstant()` from S3Repository.
                   */
                  event_timestamp:     Array[Byte],

                  /**
                   * Pipeline ingestion timestamp as Parquet INT96 (12-byte binary).
                   * When this event record was processed by Spark streaming.
                   */
                  ingestion_timestamp: Array[Byte]
                )
