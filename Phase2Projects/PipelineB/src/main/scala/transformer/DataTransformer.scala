package transformer

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._

import util.Logging

/**
 * Performs business logic transformations on raw transaction and product data
 * to generate daily customer-level summaries for the data lake.
 *
 * This transformer computes key metrics including total spend, item count,
 * product diversity, and top spending category per customer per day.
 */
object DataTransformer extends Logging {

  /**
   * Computes comprehensive daily customer transaction summaries.
   *
   * Transformation pipeline:
   *  1. Prepares transactions with date extraction and null filtering
   *  2. Joins with products (broadcast for performance)
   *  3. Calculates category-level item aggregation and ranks top category per customer
   *  4. Computes customer-level summary metrics
   *  5. Joins top category back to summary
   *
   * @param txnsDF Transaction DataFrame with columns: `txn_id`, `customer_id`, `product_id`, `qty`, `amount`, `txn_timestamp`
   * @param productsDF Product DataFrame with columns: `product_id`, `category`
   * @param spark Implicit SparkSession for DataFrame operations and implicits
   * @return Daily customer summary with metrics: `date`, `customer_id`, `total_amount`, `total_items`, `distinct_products`, `top_category`
   */
  def computeSummary(txnsDF: DataFrame, productsDF: DataFrame)(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._

    logger.info("Computing daily transaction summary")

    /**
     * Pre-processed transactions with date column and basic filtering.
     * Repartitioned by customer_id for optimal grouping performance.
     */
    val txns = txnsDF
      .withColumn("date", to_date($"txn_timestamp"))
      .filter($"product_id".isNotNull)
      .repartition($"customer_id")
      .cache()

    /**
     * Transactions enriched with product category information.
     * Uses broadcast join since products are typically small reference data.
     */
    val joined = txns.join(broadcast(productsDF), Seq("product_id"))

    /**
     * Category-level aggregation for top category determination.
     * Computes total items purchased per category per customer per day.
     */
    val byCat = joined
      .groupBy($"date", $"customer_id", $"category")
      .agg(sum($"qty").as("items_by_cat"))

    /**
     * Window specification for ranking categories by item volume per customer per day.
     * Highest volume category gets rank 1 (top_category).
     */
    val w = Window.partitionBy("date", "customer_id").orderBy(desc("items_by_cat"))

    /**
     * Top spending category per customer per day.
     * Uses row_number() to select the category with highest item volume.
     */
    val topCategoryDF = byCat
      .withColumn("rn", row_number().over(w))
      .filter($"rn" === 1)
      .select($"date", $"customer_id", $"category".as("top_category"))

    /**
     * Final customer-level daily summary with core business metrics.
     * Joins top category information using customer+date composite key.
     */
    val summaryDF = txns
      .groupBy($"date", $"customer_id")
      .agg(
        sum($"amount").as("total_amount"),        // Total monetary spend
        sum($"qty").as("total_items"),            // Total items purchased
        countDistinct($"product_id").as("distinct_products")  // Product diversity
      )
      .join(topCategoryDF, Seq("date", "customer_id"), "left")

    logger.debug(s"TXN count: ${txnsDF.count()}, Products count: ${productsDF.count()}")
    summaryDF
  }
}
