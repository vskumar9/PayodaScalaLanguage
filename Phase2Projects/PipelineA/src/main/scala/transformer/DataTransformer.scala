package transformer

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.functions.broadcast

/**
 * Customer profile aggregation and transformation logic.
 * Computes consolidated profiles from transaction history, customer demographics,
 * and product category data for Cassandra storage.
 */
object DataTransformer {

  /**
   * Computes comprehensive customer profiles for affected customers.
   * Aggregates lifetime transaction metrics, favorite category, and purchase patterns.
   *
   * @param txnsDF             Complete transaction history for affected customers
   * @param customersDF        Customer demographics (name, email, gender)
   * @param productsDF         Product dimension with category mapping
   * @param writeParallelism   Maximum partitions for downstream Cassandra write
   * @param affectedCountHint  Estimated number of unique customers (for partition sizing)
   * @return Final profiles DataFrame optimized for Cassandra write
   */
  def computeProfilesForCustomers(txnsDF: DataFrame, customersDF: DataFrame, productsDF: DataFrame,
                                  writeParallelism: Int, affectedCountHint: Int): DataFrame = {

    /**
     * Optimal repartition count balancing parallelism and skew:
     * - Minimum 8 partitions for small datasets
     * - Scales with affected customers (4x heuristic for aggregation)
     * - Capped by Cassandra write parallelism
     */
    val repartitions = Math.max(8, Math.min(writeParallelism, Math.max(1, affectedCountHint * 4)))
    import txnsDF.sparkSession.implicits._

    /**
     * Preprocess transactions:
     * - Filter invalid/null records
     * - Remove duplicates by txn_id
     * - Repartition by customer_id for optimal groupBy/colocation
     */
    val txns = txnsDF
      .filter($"product_id".isNotNull && $"customer_id".isNotNull)
      .filter($"qty" > 0 && $"amount" > 0)
      .dropDuplicates("txn_id")
      .repartition($"customer_id")
      .cache()

    /**
     * Enrich transactions with customer demographics and product categories.
     * Uses broadcast joins for small dimension tables.
     */
    val joined = txns
      .join(broadcast(customersDF), Seq("customer_id"), "inner")
      .join(broadcast(productsDF), Seq("product_id"), "inner")

    /**
     * Compute favorite category per customer:
     * - Count transactions by category
     * - Rank by descending count
     * - Select top category (rank=1)
     */
    val catCounts = joined.groupBy($"customer_id", $"category").agg(count(lit(1)).as("cnt"))
    val w = Window.partitionBy("customer_id").orderBy(desc("cnt"))
    val favoriteCategoryDF = catCounts
      .withColumn("rn", row_number().over(w))
      .filter($"rn" === 1)
      .select($"customer_id", $"category".as("favorite_category"))

    /**
     * Core profile aggregation with lifetime metrics:
     * - Monetary: total_spend, avg_order_value
     * - Frequency: total_transactions
     * - Recency: first_purchase, last_purchase
     * - Demographics: name, email, gender
     * - Behavior: favorite_category
     */
    val agg = joined
      .groupBy($"customer_id", $"name".as("customer_name"), $"email", $"gender")
      .agg(
        sum($"amount").cast("decimal(18,2)").as("total_spend"),
        count(lit(1)).as("total_transactions"),
        min($"txn_timestamp").as("first_purchase"),
        max($"txn_timestamp").as("last_purchase")
      )
      .withColumn("avg_order_value", (col("total_spend") / col("total_transactions")).cast("decimal(18,2)"))
      .join(favoriteCategoryDF, Seq("customer_id"), "left")
      .select(
        $"customer_id",
        $"customer_name".as("name"),
        $"email",
        $"gender",
        $"total_spend",
        $"total_transactions",
        $"avg_order_value",
        $"first_purchase",
        $"last_purchase",
        $"favorite_category"
      )
      .repartition(repartitions, $"customer_id")
      .cache()

    /**
     * Cleanup intermediate transaction cache to control memory usage.
     * Final profiles cached for Cassandra write optimization.
     */
    txns.unpersist(false)
    agg
  }
}
