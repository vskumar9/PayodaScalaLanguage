import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object MiniCaseStudyBenchmark {
  def main(args: Array[String]): Unit = {
    // ===== Configurable params (can override via args) =====
    val custCount   = 2000000    // 2M customers
    val txnCount    = 5000000    // 5M transactions
    val custParts   = 50
    val txnParts    = 80
    val baseOut     = "mini_case_benchmark"

    // ===== Spark session =====
    val spark = SparkSession.builder()
      .appName("MiniCaseStudyBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Parquet compression: snappy (balanced)
    spark.conf.set("spark.sql.parquet.compression.codec", "snappy")

    println(s"Parameters: custCount=$custCount, txnCount=$txnCount, custParts=$custParts, txnParts=$txnParts, baseOut=$baseOut")

    // ===== Helpers =====
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

    // ===== Generate customers RDD -> DF =====
    println("Generating customers RDD...")
    val custRDD = spark.sparkContext.parallelize(1 to custCount, custParts)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val name = rnd.alphanumeric.take(8).mkString
          (id, name)
        }
      }
    val custDF = custRDD.toDF("customerId", "name")
    println("Customer sample:")
    custDF.show(5, truncate = false)

    // ===== Generate transactions RDD -> DF =====
    println("Generating transactions RDD...")
    val txnRDD = spark.sparkContext.parallelize(1 to txnCount, txnParts)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { tid =>
          val cust = rnd.nextInt(custCount) + 1     // 1..custCount
          val amt = rnd.nextDouble() * 1000.0       // 0..1000
          (tid, cust, amt)
        }
      }
    val txnDF = txnRDD.toDF("txnId", "customerId", "amount")
    println("Transaction sample:")
    txnDF.show(5, truncate = false)

    // ===== Optional: persist smaller DF(s) if reused =====
    // We'll persist customer DF since it is reused in join/aggregation path (optional)
    // custDF.cache()

    // ===== Join customers and transactions (DataFrame) =====
    println("\nPerforming join: transactions JOIN customers on customerId ...")
    val (_, joinTime) = timeMs {
      // Use inner join to enrich txn with customer name (this triggers shuffle unless one side is broadcast)
      // For this scale, avoid broadcasting (custDF ~2M rows)
      val joined = txnDF.join(custDF.select("customerId", "name"), Seq("customerId"), "inner")
      // materialize count to measure join time
      joined.count()
    }
    println(s"Join completed in ${joinTime} ms")

    // ===== Aggregate: total spend per customer =====
    println("\nCalculating total spend per customer (groupBy + sum) ...")
    val (aggDfRows, aggTime) = timeMs {
      val totalPerCust = txnDF.groupBy("customerId").sum("amount").withColumnRenamed("sum(amount)", "totalAmount")
      // optionally join names after aggregation to reduce data shuffled during groupBy (choose pattern)
      // For demonstration, we'll join names after aggregation:
      val totalWithName = totalPerCust.join(custDF, Seq("customerId"), "left")
        .select("customerId", "name", "totalAmount")
      // materialize result (count) to measure
      totalWithName.count()
    }
    println(s"Aggregation completed in ${aggTime} ms")

    // ===== Build final result DF and write to Parquet =====
    println("\nBuilding final result DataFrame and writing to Parquet ...")
    val totalPerCustDF = txnDF.groupBy("customerId").sum("amount").withColumnRenamed("sum(amount)", "totalAmount")
    val finalDF = totalPerCustDF.join(custDF, Seq("customerId"), "left").select("customerId", "name", "totalAmount")

    val outPath = s"$baseOut/total_spend_per_customer_parquet"
    rmIfExists(outPath)
    val (_, writeTime) = timeMs {
      finalDF.write.mode(SaveMode.Overwrite).parquet(outPath)
    }
    val outSize = getDirSizeBytes(outPath)
    println(s"Parquet write completed in ${writeTime} ms; total size = ${outSize} bytes; path = $outPath")

    // ===== Show a sample of top customers =====
    println("\nTop 10 customers by totalAmount (sample):")
    val top10 = finalDF.orderBy($"totalAmount".desc).limit(10).collect()
    top10.foreach(row => println(s"${row.getAs[Long]("customerId")} | ${row.getAs[String]("name")} | ${row.getAs[Double]("totalAmount")}"))

    // ===== Summary =====
    println("\n================ SUMMARY ================")
    println(f"Join (materialize count) time       : ${joinTime}%d ms")
    println(f"Aggregation (groupBy sum) time      : ${aggTime}%d ms")
    println(f"Parquet write time                  : ${writeTime}%d ms")
    println(f"Parquet output size (bytes)         : ${outSize}%d")
    println("==========================================")

    /*
     ============================================================
                           OBSERVATIONS & ANSWERS
     ============================================================

      CONTEXT (What this program does)
      --------------------------------
      • Generates:
            – 2 million synthetic customers
            – 5 million synthetic transactions
      • Joins customers with transactions on customerId.
      • Computes total spend per customer (groupBy + sum).
      • Writes the final enriched output to Parquet.
      • Measures:
            – Join time
            – Aggregation time
            – Parquet write time
            – Output size

     ------------------------------------------------------------
      WHY DO JOINS AND GROUPBY CREATE THE LARGEST SHUFFLE?
     ------------------------------------------------------------

      A) groupBy(customerId)
      -----------------------
      • Before aggregation, rows with the same customerId are spread
        across many partitions.
      • To compute sum(amount), Spark needs *all rows for each customer*
        on the same executor.
      • Spark therefore **shuffles** (redistributes) data across the
        entire cluster so that each key is grouped together.
      • This shuffle is expensive because:
            – Large amount of data moves over the network
            – Potential disk spill if memory is insufficient
            – Serialization/deserialization overhead
            – More tasks and more scheduling overhead

      B) join (txnDF JOIN custDF)
      ----------------------------
      • For a join on customerId, Spark must ensure that rows from both
        datasets with the same customerId land on the same partition.
      • With millions of keys (customerId), Spark performs another large
        shuffle for both sides of the join.
      • Unless one side is very small (broadcast join), both tables get
        shuffled → making join one of the heaviest operations.

      Key point:
      Joins + groupBys are the **top sources of shuffle** in distributed
      systems. Shuffles dominate runtime because they combine network cost,
      disk cost, and CPU serialization overhead.

     ------------------------------------------------------------
      WHY IS PARQUET THE BEST FORMAT FOR ANALYTICAL OUTPUT?
     ------------------------------------------------------------

      • Parquet stores data **column-wise** (not row-wise).
          → reading a few columns requires reading only those columns.

      • Parquet uses **advanced compression** (snappy, dictionary,
        run-length encoding), giving:
          – Smaller files
          – Less disk I/O
          – Faster reads

      • Parquet stores **schema + statistics** (min/max) in metadata:
          → Spark can skip irrelevant data (predicate pushdown)
          → Faster filtering and scanning

      • Spark performs **vectorized reads** for Parquet:
          → Processes batches of rows at once
          → Far faster than JSON/CSV's row-by-row parsing

      • Parquet writes take slightly longer vs CSV/JSON due to encoding,
        but the benefits in read-time performance and storage reduction
        are huge.

      Practical result:
      Parquet is the preferred output format for:
          ✓ Analytics
          ✓ Data lake storage
          ✓ Repeated queries
          ✓ Large-scale processing

     ------------------------------------------------------------

      • To reduce shuffle load:
            – Aggregate first (groupBy) -> join small result vs joining large raw tables.
            – Repartition both DataFrames by the join key before join.
            – Avoid skewed keys or use salting if needed.

      • Consider broadcast join ONLY if customer DF is small enough
        (< 50–100MB typically).

      • Tune shuffle partitions:
            spark.conf.set("spark.sql.shuffle.partitions", 200)
        (or match this to cluster size)

     ------------------------------------------------------------
      • Joins and groupBy are the most shuffle-heavy operations.
      • Parquet is the best format for analytical workloads.
      • Shuffle cost dominates runtime for large datasets.
      • DataFrame API leverages Catalyst + Tungsten for performance.
      • Execution time improves if you aggregate before joining.

     ============================================================
    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    // Stop Spark
    spark.stop()
  }
}
