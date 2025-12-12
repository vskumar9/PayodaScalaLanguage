package loader

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SaveMode
import config.PipelineConfiguration

/**
 * Cassandra Keyspaces loader for customer profile data.
 * Handles partitioned writes from Spark DataFrames to profile consolidation table.
 * Provides success/failure status for upstream offset management coordination.
 */
object CassandraLoader {

  /** Cassandra Keyspaces configuration from centralized config. */
  private val ks = PipelineConfiguration.keyspaces

  /** Application parallelism settings for write optimization. */
  private val app = PipelineConfiguration.app

  /**
   * Writes customer profiles to Cassandra profile table.
   * Repartitions by customer_id for optimal distribution and uses configured parallelism.
   *
   * @param profilesDF DataFrame containing computed customer profiles
   *                    (customer_id, total_spend, txn_count, avg_order, last_txn, category_stats, etc.)
   * @return true if write succeeds, false on any failure
   */
  def writeProfiles(profilesDF: DataFrame): Boolean = {
    try {
      /**
       * Optimal partition count for Cassandra writes:
       * - Bounds between 1 and configured writeParallelism
       * - Matches current DataFrame partition count if smaller
       */
      val finalPartitions = Math.max(1, Math.min(app.writeParallelism, profilesDF.rdd.getNumPartitions))

      /**
       * Repartition by customer_id for:
       * - Even distribution across Cassandra nodes (token-aware routing)
       * - Coalescing multiple updates per customer into single writes
       */
      val profilesToWrite = profilesDF.repartition(finalPartitions, profilesDF("customer_id"))

      /**
       * Cassandra connector write with append mode.
       * Uses Keyspaces native protocol with configured table and keyspace.
       */
      profilesToWrite.write
        .format("org.apache.spark.sql.cassandra")
        .option("keyspace", ks.keyspace)
        .option("table", ks.profileTable)
        .mode(SaveMode.Append)
        .save()

      true
    } catch {
      case t: Throwable =>
        /**
         * Comprehensive error logging with stack trace.
         * Returns false to prevent offset advancement on write failure.
         */
        t.printStackTrace()
        false
    }
  }
}
