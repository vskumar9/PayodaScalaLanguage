import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode, DataFrame}
import org.apache.hadoop.fs.{FileSystem, Path}

object SalesBenchmark {
  def main(args: Array[String]): Unit = {
    // ====== Configurable ======
    val numSales   = 10000000
    val numStores  = 100
    val partitions = 50
    val baseOut    = "sales_benchmark"

    // ====== Spark ======
    val spark = SparkSession.builder()
      .appName("SalesBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // set parquet compression
    spark.conf.set("spark.sql.parquet.compression.codec", "snappy")

    println(s"Parameters: numSales=$numSales, numStores=$numStores, partitions=$partitions, baseOut=$baseOut")

    // ====== Data generation (RDD) ======
    val stores = (1 to numStores).map(i => s"Store_$i").toArray
    val salesRDD = spark.sparkContext.parallelize(1 to numSales, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val store = stores(rnd.nextInt(stores.length))
          val amt = rnd.nextDouble() * 500.0
          (store, amt)
        }
      }

    println("Sample sales rows:")
    salesRDD.take(5).foreach(println)

    // ====== Helpers ======
    def timeMs[T](block: => T): (T, Long) = {
      val t0 = System.nanoTime()
      val r  = block
      val t1 = System.nanoTime()
      (r, (t1 - t0) / 1000000L)
    }

    def rmIfExists(pathStr: String): Unit = {
      val p = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (fs.exists(p)) fs.delete(p, true)
    }

    def getDirSizeBytes(pathStr: String): Long = {
      val p = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (!fs.exists(p)) 0L else fs.getContentSummary(p).getLength
    }

    // ====== RDD: groupByKey (sum per store) ======
    println("\n--- RDD: groupByKey -> sum amounts (WARNING: memory heavy) ---")
    val (groupedResult, groupByTime) = timeMs {
      salesRDD.groupByKey().mapValues(iter => iter.sum).collect()
    }
    println(s"groupByKey total time = ${groupByTime} ms; sample result:")
    groupedResult.take(10).foreach(println)

    // ====== RDD: reduceByKey (sum amounts) ======
    println("\n--- RDD: reduceByKey -> sum amounts (preferred) ---")
    val (reducedResult, reduceByTime) = timeMs {
      salesRDD.reduceByKey(_ + _).collect()
    }
    println(s"reduceByKey total time = ${reduceByTime} ms; sample result:")
    reducedResult.take(10).foreach(println)

    // ====== DataFrame: convert and compute aggregation ======
    println("\n--- DataFrame: groupBy store and sum(amount) ---")
    val salesDF = salesRDD.toDF("storeId", "amount")
    val (dfAgg, dfAggTime) = timeMs {
      salesDF.groupBy("storeId").sum("amount").orderBy("storeId").collect()
    }
    println(s"DataFrame groupBy total time = ${dfAggTime} ms; sample:")
    dfAgg.take(10).foreach(println)

    // ====== Write DF result to Parquet ======
    val resultDF = salesDF.groupBy("storeId").sum("amount").withColumnRenamed("sum(amount)", "totalAmount")

    val parquetPath = s"$baseOut/parquet"
    rmIfExists(parquetPath)

    println("\nWriting result DataFrame to Parquet...")
    val (_, parquetWriteTime) = timeMs {
      resultDF.write.mode(SaveMode.Overwrite).parquet(parquetPath)
    }
    val parquetSize = getDirSizeBytes(parquetPath)
    println(s"Parquet write time = ${parquetWriteTime} ms; size = ${parquetSize} bytes; path=$parquetPath")

    // ====== Summary ======
    println("\n============== SUMMARY ==============")
    println(f"groupByKey (RDD) time = $groupByTime%,d ms")
    println(f"reduceByKey (RDD) time = $reduceByTime%,d ms")
    println(f"DataFrame groupBy time = $dfAggTime%,d ms")
    println(f"Parquet write time = $parquetWriteTime%,d ms")
    println("=====================================")

    /*
     ============================
      Observations & Answers
     ============================

     Context (from this program)
     ---------------------------
     - numSales = 10,000,000 rows generated across 100 stores.
     - Three paths were compared: RDD groupByKey, RDD reduceByKey, and DataFrame groupBy.
     - A Parquet write was performed at the end.

     Q: Using RDD, compare groupByKey vs reduceByKey. Which one is slower for 10M rows? Why?
     -------------------------------------------------------------------------------
     groupByKey:
       - Shuffles all values for each key across the cluster.
       - Builds a full Iterable of values for each key on the reducer.
       - High memory pressure, heavy GC, and potentially OutOfMemory for large groups.
       - Very slow for large datasets.

     reduceByKey:
       - Performs map-side combine; each partition partially aggregates values first.
       - Only the partial aggregates are shuffled.
       - Much smaller shuffle size and lower memory usage.
       - Typically far faster for large-scale aggregations.

     Conclusion:
       - reduceByKey is much faster and safer than groupByKey for aggregation.
       - groupByKey should be avoided for large numeric aggregations.

     Q: Which operations in this code cause shuffle?
     ----------------------------------------------
     Wide (shuffle) operations:
       - RDD groupByKey (full shuffle of all values).
       - RDD reduceByKey (shuffle in the final stage).
       - DataFrame groupBy("storeId") (shuffle by key).
       - orderBy or global sort (if used).
       - repartition(n) with shuffle.

     Narrow (non-shuffle) operations:
       - map, filter, mapPartitions.
       - coalesce(n) when reducing partitions without shuffle.

     Explanation:
       - A shuffle happens when Spark must move data across partitions so that records with the same key can be grouped together.

     Q: Why is reduceByKey described as a "narrow → broad" transformation chain?
     ---------------------------------------------------------------------------
       - Step 1: Local combine within each partition (narrow, no shuffle).
       - Step 2: Shuffled reduce stage, where partial aggregates are merged (broad).
       - This two-step design reduces the amount of data shuffled.

     Q: Why does the DataFrame groupBy often perform faster?
     -------------------------------------------------------
       - Catalyst optimizer rewrites and optimizes the query.
       - Whole-stage code generation produces efficient bytecode.
       - Tungsten engine reduces object creation and uses better memory layout.
       - DataFrame aggregation operators use optimized hash or sort-based aggregators.
       - Result: DataFrame operations tend to run faster and handle memory better.

     ------------------------------------------
       - Prefer reduceByKey over groupByKey for aggregations.
       - In DataFrames, prefer groupBy + agg for analytics.
       - Adjust spark.sql.shuffle.partitions for performance (default 200 is high for local).
       - Avoid coalesce(1) for big output; it reduces parallelism and can slow writes.
       - If keys are skewed (some stores appear much more), consider key salting.
       - For storage, Parquet is the best choice: compressed, columnar, and fast to read.

     --------------------------
       - groupByKey moves too much data and is slow for big datasets.
       - reduceByKey is efficient because it aggregates locally before shuffling.
       - DataFrame APIs offer built-in optimizations and usually run fastest.
       - Use Parquet for analytics because it saves space and speeds up queries.

    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    // Stop Spark
    spark.stop()
  }
}
