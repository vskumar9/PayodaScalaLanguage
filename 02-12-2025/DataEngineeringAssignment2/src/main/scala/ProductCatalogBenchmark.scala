import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object ProductCatalogBenchmark {
  def main(args: Array[String]): Unit = {
    // ====== Configurable params ======
    val numProducts = 2000000
    val partitions  = 40
    val baseOut     = "product_catalog_benchmark"

    // ====== Spark session ======
    val spark = SparkSession.builder()
      .appName("ProductCatalogBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Use snappy for Parquet compression (common)
    spark.conf.set("spark.sql.parquet.compression.codec", "snappy")

    println(s"Parameters: numProducts=$numProducts, partitions=$partitions, baseOut=$baseOut")

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

    // ====== Data generation (RDD) ======
    val categories = Array("Electronics", "Clothes", "Books")
    val productRDD = spark.sparkContext.parallelize(1 to numProducts, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val cat = categories(rnd.nextInt(categories.length))
          val price = rnd.nextDouble() * 2000.0
          val desc = rnd.alphanumeric.take(50).mkString
          (id.toLong, cat, price, desc)
        }
      }

    println("Sample products:")
    productRDD.take(5).foreach(println)

    // Convert to DataFrame
    val productDF = productRDD.toDF("productId", "category", "price", "description")
    productDF.createOrReplaceTempView("products")

    // ====== Filter with DataFrame: price > 1000 ======
    println("\nFiltering products with price > 1000...")
    val (filteredDF_any, filterTime) = timeMs {
      // lazy until action — do an aggregate count to materialize and measure time
      val c = productDF.filter($"price" > 1000.0).count()
      c
    }
    println(s"Filter completed: count = $filteredDF_any (time = ${filterTime} ms)")

    // ====== Sort by price (global) ======
    println("\nSorting filtered products by price (this triggers a wide shuffle)...")
    // get the filtered DF as a variable
    val filteredDF = productDF.filter($"price" > 1000.0)
    // ORDER BY globally -> wide shuffle (sort across partitions)
    val (sortedSample, sortTime) = timeMs {
      // we persist sorted DF to measure time to compute and collect a small sample
      val sorted = filteredDF.orderBy($"price".asc)
      // trigger action: take(10) to both compute sort and fetch sample
      val sample = sorted.take(10)
      sample
    }
    println(s"Sort triggered; sample of top 10 by price (ascending). time = ${sortTime} ms")
    sortedSample.foreach(println)

    // ====== Prepare to write sorted data ======
    val sortedDF = filteredDF.orderBy($"price".asc)

    // Paths
    val csvPath = s"$baseOut/csv_sorted"
    val parquetPath = s"$baseOut/parquet_sorted"

    // cleanup previous runs
    rmIfExists(csvPath); rmIfExists(parquetPath)

    // ====== Write CSV ======
    println("\nWriting sorted data to CSV...")
    val (_, csvTime) = timeMs {
      sortedDF.write.mode(SaveMode.Overwrite).option("header", "true").csv(csvPath)
    }
    val csvSize = getDirSizeBytes(csvPath)
    println(s"CSV write done: time = ${csvTime} ms; size = ${csvSize} bytes; path = $csvPath")

    // ====== Write Parquet ======
    println("\nWriting sorted data to Parquet...")
    val (_, parquetTime) = timeMs {
      sortedDF.write.mode(SaveMode.Overwrite).parquet(parquetPath)
    }
    val parquetSize = getDirSizeBytes(parquetPath)
    println(s"Parquet write done: time = ${parquetTime} ms; size = ${parquetSize} bytes; path = $parquetPath")

    // ====== Summary ======
    println("\n================ SUMMARY ================")
    println(f"Filter time (materialized count) : ${filterTime}%d ms")
    println(f"Sort sample time (take 10)       : ${sortTime}%d ms (shows compute+shuffle cost)")
    println("")
    println(f"CSV write time    : ${csvTime}%d ms, size = ${csvSize}%d bytes")
    println(f"Parquet write time: ${parquetTime}%d ms, size = ${parquetSize}%d bytes")
    println("==========================================")

    /*
     ================================================
      OBSERVATIONS & ANSWERS
     ================================================

     Context (what this program does)
     --------------------------------
     - Generates 2,000,000 product rows across categories: Electronics, Clothes, Books.
     - Filters products with price > 1000 using DataFrame API.
     - Performs a global sort by price (orderBy) and writes the sorted results to CSV and Parquet.
     - Measures times and file sizes.

    Q: Compare CSV vs Parquet write performance
    --------------------------------

    Typical behavior:

    CSV is simple row-by-row text serialization. It may appear faster to write in some cases
    because it does minimal encoding work, but CSV files are larger and slower to read for analytics.

    Parquet does columnar encoding, builds row-groups and metadata, and applies compression.
    That encoding work makes Parquet writes usually slower than raw CSV writes, but Parquet files
    are much smaller and significantly faster for subsequent analytical reads (column pruning,
    predicate pushdown, vectorized IO).


    CSV: easier to inspect with text tools, but wastes disk and IO; not optimal for analytics.
    Parquet: more CPU during write, but far better for storage, I/O, and query performance.
    Absolute performance depends on environment (local vs HDFS vs S3), compression codec, and whether
    outputs are coalesced into fewer files.

    Q: Why does sorting (orderBy) cause a broad shuffle?
    --------------------------------
    Reason:
    - A global sort must produce a total ordering across all partitions. To do this Spark:
    - samples the data to determine partition boundaries,
    - shuffles records so each target partition receives the records for its range,
    - sorts records locally inside each partition.
    - The crucial step is step 2: moving records between executors over the network — that is the shuffle.
    - Shuffles are expensive because they involve network transfer, serialization, and possible disk spill.

    --------------------------------
    If you only need sorted data inside each partition (not globally), use sortWithinPartitions to avoid the shuffle.
    Reduce the amount of data before sorting (filter, aggregate, or sample) to reduce shuffle volume.
    Tune spark.sql.shuffle.partitions to a value appropriate to your cluster (default 200 is often too high on local runs).

    --------------------------------
    If your pipeline only needs analytics-readable data, prefer writing Parquet over CSV.
    Avoid global orderBy on very large datasets unless necessary. Think about whether partition-level ordering suffices.
    If you must sort globally, try to:
    reduce input size first (filter/aggregate),
    set spark.sql.shuffle.partitions to a reasonable number (e.g., match core count),
    ensure enough executor memory to avoid disk spills.
    For local experiments, be aware that coalescing to a single file (coalesce(1)) makes inspection easy but destroys parallelism.
    Measure both write time and file size — Parquet often wins on storage and read performance even if write time is higher.

    --------------------------------
    CSV is simple but inefficient for analytics; Parquet is optimized for analytical workloads.
    Global sorting forces a wide shuffle; avoid it unless you truly need records globally ordered.
    Tuning shuffle partitions and reducing input size before shuffle are the main levers to improve sort performance.

    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
