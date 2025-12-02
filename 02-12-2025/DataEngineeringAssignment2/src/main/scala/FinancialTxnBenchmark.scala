import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object FinancialTxnBenchmark {
  def main(args: Array[String]): Unit = {
    // ===== Configurable parameters =====
    val numTxns    = 3000000   // 3M default
    val partitions  = 40
    val baseOut     = "financial_txn_benchmark"

    // ===== Spark session =====
    val spark = SparkSession.builder()
      .appName("FinancialTxnBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Use snappy for Parquet
    spark.conf.set("spark.sql.parquet.compression.codec", "snappy")

    println(s"Parameters: numTxns=$numTxns, partitions=$partitions, baseOut=$baseOut")

    // ===== Helpers =====
    def timeMs[T](block: => T): (T, Long) = {
      val t0 = System.nanoTime()
      val r  = block
      val t1 = System.nanoTime()
      (r, (t1 - t0)/1000000L)
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

    // ===== Data generation (RDD) =====
    val txnRDD = spark.sparkContext.parallelize(1 to numTxns, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val acc = "ACC_" + rnd.nextInt(100000) // ~100k accounts
          val amt = rnd.nextDouble() * 10000.0
          (acc, amt)
        }
      }

    println("Sample transactions:")
    txnRDD.take(5).foreach(println)

    // ===== RDD path: reduceByKey -> sortBy to get top 10 =====
    println("\n--- RDD approach: reduceByKey -> sortBy (top 10 accounts) ---")
    val (rddTop10, rddTime) = timeMs {
      // reduceByKey gives (account, total)
      val totals = txnRDD.reduceByKey(_ + _)
      // sortBy on totals descending; collectTop10 locally
      // sortBy is wide (shuffle) because it needs global ordering
      val top10 = totals.sortBy(_._2, ascending = false).take(10)
      top10
    }
    println(s"RDD computed top-10 in ${rddTime} ms; results:")
    rddTop10.foreach(println)

    // ===== DataFrame path: groupBy -> sum -> orderBy =====
    println("\n--- DataFrame approach: groupBy -> sum -> orderBy (top 10) ---")
    val txnDF = txnRDD.toDF("accountId", "amount")

    // Build aggregated DF (keep it as DF so we can write it safely)
    val aggDF = txnDF.groupBy($"accountId").sum("amount").withColumnRenamed("sum(amount)", "total")

    // Prepare top-10 as a DataFrame (no collect for writing)
    val top10DF = aggDF.orderBy($"total".desc).limit(10)

    // Materialize for timing & also collect for printing (collect is optional — small result)
    val (dfTop10Collected, dfAggTime) = timeMs {
      val rows = top10DF.collect() // small (10 rows), safe to collect for printing
      rows
    }

    println(s"DataFrame computed top-10 in ${dfAggTime} ms; results:")
    dfTop10Collected.foreach(row => println(s"${row.getAs[String]("accountId")} -> ${row.getAs[Double]("total")}"))

    // ===== Write DataFrame top-10 to Parquet (fixed: write the DF directly) =====
    val outPath = s"$baseOut/top10_accounts_parquet"
    rmIfExists(outPath)

    val (_, writeTime) = timeMs {
      top10DF.write.mode(SaveMode.Overwrite).parquet(outPath)
    }
    val outSize = getDirSizeBytes(outPath)
    println(s"\nWrote top-10 to Parquet in ${writeTime} ms, size = ${outSize} bytes, path = $outPath")

    // ===== Summary =====
    println("\n================ SUMMARY ================")
    println(f"RDD (reduceByKey -> sortBy) time : ${rddTime}%d ms")
    println(f"DF  (groupBy -> sum -> orderBy) time: ${dfAggTime}%d ms")
    println(f"Parquet write time: ${writeTime}%d ms; size: ${outSize}%d bytes")
    println("==========================================")

    /*
     ============================================================
       OBSERVATIONS & ANSWERS
     ============================================================

       CONTEXT (What this program is doing)
       ------------------------------------
       • Generates 3 million random financial transactions.
       • Computes top-10 accounts with the highest total spending
           – RDD approach: reduceByKey → sortBy → take(10)
           – DataFrame approach: groupBy → sum → orderBy → limit(10)
       • Writes the top-10 result to Parquet.
       • Compares execution time for both approaches.

       ------------------------------------------------------------
       WHY groupBy AND sort ARE BROAD (WIDE) TRANSFORMATIONS?
       ------------------------------------------------------------
       1. groupBy(accountId):
          • Each accountId may exist in many partitions.
          • Spark must SHUFFLE all rows for the same key to the same reducer.
          • This data movement across partitions = WIDE transformation.

       2. Global sort (orderBy / sortBy):
          • Spark must produce a single globally sorted order.
          • Requires redistributing rows to create globally correct ranges.
          • This redistribution ALSO triggers a SHUFFLE.

          Summary:
          groupBy = wide
          orderBy = wide
          Two shuffles → expensive operations.

       ------------------------------------------------------------
       WHY REDUCEBYKEY IS FASTER THAN GROUPBYKEY (RDD)?
       ------------------------------------------------------------
       reduceByKey:
          • First performs local “map-side combine.”
          • Partially aggregates values inside each partition.
          • Only PARTIAL SUMS are shuffled.
          • Much lower network IO + memory pressure.

       groupByKey:
          • Shuffles *every* value for a key.
          • Builds a huge Iterable on reducers.
          • High memory usage, higher GC overhead.

          Summary:
          reduceByKey = narrow → broad (efficient)
          groupByKey  = only broad (inefficient)

       ------------------------------------------------------------
       WHY DATAFRAME IS USUALLY FASTER THAN RDD?
       ------------------------------------------------------------
       • Catalyst Optimizer:
           Automatically rewrites queries and picks efficient plans.
       • Whole-Stage Codegen:
           Generates optimized JVM bytecode → fewer virtual function calls.
       • Tungsten Engine:
           Optimized memory layout, off-heap processing, low allocation.
       • Vectorized execution:
           Processes batches of rows → better CPU cache usage.
       • Optimized shuffle writers/readers.

          In short:
          DataFrames = optimized engine + codegen
          RDDs       = manual logic + object overhead + no optimizer

       ------------------------------------------------------------
       • RDD path:
           reduceByKey is efficient, but sortBy still triggers a full shuffle.
       • DataFrame path:
           groupBy + sum + orderBy is internally optimized.
           Only the final 10 rows are collected — safe for the driver.
       • Parquet write:
           Columnar format → compressed (snappy) → smaller output.
       • Avoid coalesce(1) for large datasets because:
           It forces a single executor to write everything → slow.

       ------------------------------------------------------------
       • For massive top-K computations:
           – Do map-side top-K per partition, then merge results.
       • Avoid collecting large datasets; only collect small results.
       • Monitor Spark UI → look at shuffle read/write sizes.
       • Prefer Parquet for analytics (fast reads, smaller files).
       • Tune spark.sql.shuffle.partitions based on dataset size.

       ------------------------------------------------------------
       – wide transformations (groupBy, join, sort) always cause shuffles.
       – reduceByKey is a more efficient RDD aggregation than groupByKey.
       – DataFrames generally outperform RDDs because Spark can optimize them.

     ============================================================
    */


    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
