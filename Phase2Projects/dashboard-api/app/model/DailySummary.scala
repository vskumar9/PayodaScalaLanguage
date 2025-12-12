package model

/**
 * Daily transaction summary for a single customer, aggregated from raw events.
 *
 * Represents one row from the S3 lakehouse `lake/txn_summary/date=YYYY-MM-DD/` Parquet files.
 * Used for both single-customer lookups and bulk daily summaries.
 *
 * Field mapping matches Parquet schema exactly (case-sensitive):
 * - customer_id:       required INT32
 * - total_amount:      DECIMAL(20,2)
 * - total_items:       INT64
 * - distinct_products: required INT64
 * - top_category:      STRING
 */
case class DailySummary(
                         /**
                          * Customer identifier.
                          * Required field in Parquet (INT32).
                          */
                         customer_id:       Int,

                         /**
                          * Total transaction amount for the day.
                          * DECIMAL(20,2) in Parquet, maps to Scala BigDecimal.
                          */
                         total_amount:      BigDecimal,

                         /**
                          * Total number of items purchased.
                          * Optional INT64 in Parquet.
                          */
                         total_items:       Long,

                         /**
                          * Number of unique products purchased.
                          * Required field in Parquet (INT64).
                          */
                         distinct_products: Long,

                         /**
                          * Most frequently purchased category for the day.
                          * Optional STRING in Parquet.
                          */
                         top_category:      String
                       )
