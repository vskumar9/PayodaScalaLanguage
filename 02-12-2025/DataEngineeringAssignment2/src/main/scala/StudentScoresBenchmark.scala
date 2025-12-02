import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object StudentScoresBenchmark {
  def main(args: Array[String]): Unit = {

    // ========== Config ==========
    val numStudents = 1500000
    val partitions  = 20
    val baseOut     = "student_scores_benchmark"

    // ========== Spark session ==========
    val spark = SparkSession.builder()
      .appName("StudentScoresBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Helpers
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

    println(s"Generating $numStudents students ...")

    // ========== Data generation (RDD → DF) ==========
    val studentRDD = spark.sparkContext.parallelize(1 to numStudents, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val name = rnd.alphanumeric.take(6).mkString
          val score = rnd.nextInt(100)
          (id, name, score)
        }
      }

    val studentDF = studentRDD.toDF("studentId", "name", "score")
    println("Sample:")
    studentDF.show(5, truncate = false)

    // ========== Sort by score DESC (broad shuffle) ==========
    println("\nSorting all students by score (descending)...")
    val (sortedTop10, sortTime) = timeMs {
      val sorted = studentDF.orderBy($"score".desc)  // global sort = wide shuffle
      sorted.take(10)  // triggers shuffle + global ordering
    }

    println(s"Sort completed in ${sortTime} ms; Top 10:")
    sortedTop10.foreach(println)

    // ========== Write sorted output to JSON ==========
    val outPath = s"$baseOut/sorted_json"
    rmIfExists(outPath)

    val sortedDF = studentDF.orderBy($"score".desc)

    println("\nWriting sorted data to JSON...")
    val (_, jsonTime) = timeMs {
      sortedDF.write.mode(SaveMode.Overwrite).json(outPath)
    }
    val jsonSize = getDirSizeBytes(outPath)

    println(s"JSON write time: ${jsonTime} ms; size = ${jsonSize} bytes")

    // ========== Summary ==========
    println("\n================ SUMMARY ================")
    println(f"Sorting time (broad shuffle): ${sortTime}%d ms")
    println(f"JSON write time             : ${jsonTime}%d ms")
    println(f"Output size (bytes)         : ${jsonSize}%d")
    println("==========================================")

    /*
     ============================================================
                       OBSERVATIONS & ANSWERS
     ============================================================

      CONTEXT (What this program does)
      --------------------------------
      • Generates 1.5 million synthetic student records:
            (studentId, name, score)
      • Sorts all students by score in descending order.
      • Writes the globally sorted data to JSON format.
      • Measures:
            – Sorting time
            – JSON write time
            – Output size on disk

      ------------------------------------------------------------
      WHY IS SORTING A BROAD (WIDE) SHUFFLE?
      ------------------------------------------------------------
      • A global sort requires Spark to order ALL rows across ALL partitions.
      • This means Spark must move data between executors so that:
            Partition 0 → highest scores
            Partition 1 → next highest
            Partition N → lowest scores
      • This re-arrangement happens through a SHUFFLE:
            – Data moves across partitions
            – Network + disk I/O occurs
            – New partition boundaries are computed
      • Any operation that requires grouping, joining, or global ordering
        will trigger a wide transformation.

      Key point:
      Global sorting is expensive because Spark must coordinate and move
      records across the entire cluster to achieve correct ordering.

      ------------------------------------------------------------
      WHY IS JSON USUALLY THE SLOWEST FORMAT TO WRITE?
      ------------------------------------------------------------
      JSON is slower because:

      1. **Verbose Format**
            • Each row becomes a full JSON object with field names, quotes,
              braces, etc.
            • Much larger than CSV or Parquet.

      2. **Row-by-Row Serialization**
            • JSON writers serialize one record at a time
            • No columnar compression or batching

      3. **Bigger File Sizes**
            • Larger output means more disk I/O and more network I/O

      4. **No Type Optimization**
            • JSON stores everything as text
            • Spark must encode each value individually

      Comparison:
            CSV → simpler text, slightly faster
            Parquet → binary, columnar, compressed → MUCH faster & smaller
            JSON → slowest and largest

      ------------------------------------------------------------
      • Use JSON only for debugging or data transfer (API or logs).
      • Do NOT use JSON for analytical pipelines.
      • For large datasets always prefer:
            ✓ Parquet — for analytics, compression, fast read/write
            ✓ ORC — similar benefits
      • Remember:
            – orderBy  → wide shuffle
            – map/filter → narrow, no shuffle

      ------------------------------------------------------------
      • Global sort triggers a wide shuffle → expensive
      • JSON writing is slow due to verbosity and lack of compression
      • Partition count affects parallelism and shuffle scaling
      • Always measure performance due to shuffle-heavy operations

     ============================================================
    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
