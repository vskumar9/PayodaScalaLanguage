import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object LogFilesBenchmark {
  def main(args: Array[String]): Unit = {
    // ====== Config ======
    val numLogs    = 5000000
    val partitions = 40
    val baseOut    = "logs_benchmark"

    // ====== Spark ======
    val spark = SparkSession.builder()
      .appName("LogFilesBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Helpers
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

    println(s"Parameters: numLogs=$numLogs, partitions=$partitions, baseOut=$baseOut")

    // ====== Generate logs RDD ======
    val levels = Array("INFO", "WARN", "ERROR")
    val logsRDD = spark.sparkContext.parallelize(1 to numLogs, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { _ =>
          val ts = System.currentTimeMillis() - rnd.nextInt(10000000)
          val level = levels(rnd.nextInt(levels.length))
          val msg = rnd.alphanumeric.take(15).mkString
          val user = rnd.nextInt(10000)
          s"$ts|$level|$msg|$user"
        }
      }

    println("Sample log lines:")
    logsRDD.take(5).foreach(println)

    // ====== Count ERROR using RDD filter ======
    println("\n--- RDD: filter for ERROR ---")
    val (rddErrorCount, rddFilterTime) = timeMs {
      val c = logsRDD.filter(line => line.contains("|ERROR|")).count()
      c
    }
    println(s"RDD filter -> ERROR count = $rddErrorCount (time ${rddFilterTime} ms)")

    // ====== Parse to DataFrame ======
    val logsDF = logsRDD
      .map(_.split("\\|"))
      .map(a => (a(0), a(1), a(2), a(3)))
      .toDF("timestamp", "level", "message", "userId")
    logsDF.createOrReplaceTempView("logs")

    println("\nSample parsed DataFrame rows:")
    logsDF.show(5, truncate = false)

    // ====== Count ERROR using DataFrame filter ======
    println("\n--- DataFrame: filter for ERROR ---")
    val (dfErrorCountAndRows, dfFilterTime) = timeMs {
      // we use count action to measure
      val c = logsDF.filter($"level" === "ERROR").count()
      c
    }
    println(s"DataFrame filter -> ERROR count = $dfErrorCountAndRows (time ${dfFilterTime} ms)")

    // ====== Write ERROR logs to plain text ======
    val errorTextOut = s"$baseOut/error_text"
    rmIfExists(errorTextOut)
    println(s"\nWriting ERROR logs to plain text at: $errorTextOut")
    val (writeTextRes, writeTextTime) = timeMs {
      // Use RDD of ERROR lines for plain text write
      val errorRDD = logsRDD.filter(line => line.contains("|ERROR|"))
      errorRDD.saveAsTextFile(errorTextOut)
    }
    val errorTextSize = getDirSizeBytes(errorTextOut)
    println(s"Plain text write time = ${writeTextTime} ms; size = ${errorTextSize} bytes")

    // ====== Write full logs to JSON ======
    val fullJsonOut = s"$baseOut/full_json"
    rmIfExists(fullJsonOut)
    println(s"\nWriting full logs to JSON at: $fullJsonOut")
    val (_, writeJsonTime) = timeMs {
      logsDF.write.mode(SaveMode.Overwrite).json(fullJsonOut)
    }
    val fullJsonSize = getDirSizeBytes(fullJsonOut)
    println(s"Full JSON write time = ${writeJsonTime} ms; size = ${fullJsonSize} bytes")

    // ====== Summary ======
    println("\n================ SUMMARY ================")
    println(f"RDD filter ERROR count : $rddErrorCount%,d (time ${rddFilterTime} ms)")
    println(f"DF filter ERROR count  : $dfErrorCountAndRows%,d (time ${dfFilterTime} ms)")
    println("")
    println(f"ERROR plain text write : ${writeTextTime} ms, size = $errorTextSize bytes")
    println(f"Full JSON write        : ${writeJsonTime} ms, size = $fullJsonSize bytes")
    println("==========================================")

    /*
     ===============================================
      OBSERVATIONS & ANSWERS
     ===============================================

      Context (what this program does)
     --------------------------------
     - Generates 5 million synthetic log lines: "timestamp|level|message|userId".
     - Counts ERROR logs using both RDD filter and DataFrame filter.
     - Writes ERROR logs to plain text.
     - Writes full parsed logs to JSON.
     - Compares timing and file sizes.

     Q: Why is plain text slow to write?
     -----------------------------------
     Plain text output (saveAsTextFile) is usually slower for several reasons:
       1. Each record is written as a full string. There is no binary or columnar encoding.
       2. Text output is not compressed by default, so much more data is written to disk.
       3. For each partition Spark opens a new file, writes a few MB, and closes it.
          File open/close operations are slow, especially on distributed storage.
       4. Writing text involves per-line string formatting, which is CPU-heavy.
       5. JSON, Parquet, and other structured formats use better serialization and batching.
       6. Parquet especially reduces IO by storing data by column and applying compression.

     Summary:
       Plain text is simple but not optimized. It generates larger files and slower writes.

     Q: Which operations are narrow and which are broad?
     ---------------------------------------------------
     Narrow operations:
       - filter: Each record is checked independently; no data moves between partitions.
       - map or split: Each record is transformed locally within the same partition.
       These operations do not trigger a shuffle.

     Broad (wide) operations:
       - sort or orderBy: Requires a global ordering; Spark must shuffle data so all
         records are arranged in correct sorted ranges.
       - groupBy, reduceByKey, join (not used here but common examples): require
         records with the same key to be moved to the same partition.

     In this code:
       - RDD filter is narrow.
       - DataFrame filter is narrow.
       - Writing text or JSON does not require shuffling but does create one output
         file per partition unless you coalesce first.

     ------------------------------
       - RDD filter vs DataFrame filter both produce the same result; performance differences
         may come from Tungsten/Codegen optimizations in DataFrames.
       - saveAsTextFile writes multiple files (one per task). To get a single file for
         easy inspection you can use coalesce(1), but avoid this for large data because
         it forces a single executor to do all writing.
       - JSON output is larger than Parquet but often smaller and faster than raw text.
       - When dealing with massive log data in production, prefer Parquet or ORC because
         they compress better and are much faster to scan and query.

     ------------------------
       - Use text when human readability is important.
       - Use JSON when structure matters but readability is still useful.
       - Use Parquet for analytics, compression, and performance.
       - Remember: filter, map → narrow; sort, groupBy → broad (shuffle).

    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
