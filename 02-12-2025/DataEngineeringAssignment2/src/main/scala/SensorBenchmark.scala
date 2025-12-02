import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode, functions}
import org.apache.hadoop.fs.{FileSystem, Path, FileStatus}

object SensorBenchmark {
  def main(args: Array[String]): Unit = {
    // ====== Configurable params ======
    val numSensors = 3000000   // 3M
    val partitions = 40
    val baseOut    = "sensor_benchmark"

    // ====== Spark session ======
    val spark = SparkSession.builder()
      .appName("SensorBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Parquet compression
    spark.conf.set("spark.sql.parquet.compression.codec", "snappy")

    println(s"Parameters: numSensors=$numSensors, partitions=$partitions, baseOut=$baseOut")

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

    def listPartitionDirs(pathStr: String): Array[FileStatus] = {
      val p = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (!fs.exists(p)) Array.empty[FileStatus]
      else fs.listStatus(p).filter(_.isDirectory)
    }

    def getDirSizeBytes(pathStr: String): Long = {
      val p = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (!fs.exists(p)) 0L else fs.getContentSummary(p).getLength
    }

    // ====== Generate sensor data (RDD) ======
    val sensorRDD = spark.sparkContext.parallelize(1 to numSensors, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { _ =>
          val dev = "DEV_" + rnd.nextInt(5000)
          val temp = 20.0 + rnd.nextDouble() * 15.0
          val hum = 40.0 + rnd.nextDouble() * 20.0
          val hour = rnd.nextInt(24)
          (dev, temp, hum, hour)
        }
      }

    println("Sample sensor rows:")
    sensorRDD.take(5).foreach(println)

    // ====== Convert to DataFrame ======
    val sensorDF = sensorRDD.toDF("deviceId", "temperature", "humidity", "hour")
    sensorDF.createOrReplaceTempView("sensors")

    // ====== Compute average temperature per hour (DataFrame) ======
    println("\nComputing average temperature per hour...")
    val (avgDFrows, aggTime) = timeMs {
      val aggDF = sensorDF.groupBy("hour").avg("temperature").orderBy("hour")
      // collect to materialize and measure time
      aggDF.collect()
    }
    println(s"Aggregation completed in ${aggTime} ms; sample result:")
    avgDFrows.foreach(println)

    // ====== Prepare output DF (name column) ======
    val resultDF = sensorDF.groupBy("hour")
      .agg(functions.avg($"temperature").alias("avgTemperature"))
      .orderBy("hour")

    // ====== Write result as Parquet partitioned by hour ======
    val outPath = s"$baseOut/avg_temp_by_hour_partitioned"
    rmIfExists(outPath)

    println(s"\nWriting average temperature per hour to Parquet partitioned by hour at: $outPath")
    val (_, writeTime) = timeMs {
      // write partitioned by 'hour' column
      resultDF.write.mode(SaveMode.Overwrite).partitionBy("hour").parquet(outPath)
    }
    val outSize = getDirSizeBytes(outPath)
    println(s"Parquet partitioned write time = ${writeTime} ms; total size = ${outSize} bytes")

    // ====== Count partition directories created under output path ======
    val partitionDirs = listPartitionDirs(outPath)
    println(s"Number of top-level items under $outPath = ${partitionDirs.length}")
    // Usually partition directories look like: hour=0, hour=1, ..., hour=23
    // Let's list folder names and how many files inside each:
    partitionDirs.foreach { fs =>
      val name = fs.getPath.getName
      val files = FileSystem.get(spark.sparkContext.hadoopConfiguration).listStatus(fs.getPath)
      val fileCount = files.count(f => f.isFile)
      println(s"  - $name -> files: $fileCount")
    }

    // ====== Summary ======
    println("\n================ SUMMARY ================")
    println(s"Aggregation (groupBy hour) time: ${aggTime} ms")
    println(s"Partitioned Parquet write time  : ${writeTime} ms")
    println(s"Total output size (bytes)       : ${outSize} bytes")
    println(s"Number of partition directories  : ${partitionDirs.length}")
    println("==========================================")

    /*
     ===============================================
      OBSERVATIONS & ANSWERS
     ===============================================

      Context (what this program does)
     --------------------------------
     - Generates 3 million synthetic IoT sensor rows (deviceId, temperature, humidity, hour).
     - Computes the average temperature for each hour using DataFrame groupBy.
     - Writes the results as a Parquet dataset partitioned by the column "hour".
     - Counts how many partition folders are generated and how many files are inside each.
     - Measures aggregation time and Parquet write time.

     Q: How many output folders get created?
     ---------------------------------------
     - The partition column is "hour", which ranges from 0 to 23.
     - Therefore, Spark will create up to 24 folders:
         hour=0, hour=1, hour=2, ..., hour=23
     - Because the input data is uniformly random across 3 million rows,
       all 24 hour values appear, so all 24 folders will be generated.
     - Inside each hour folder, Spark writes 1 or more Parquet files.
       The number of files depends on:
         * the number of shuffle output tasks that handled that hour,
         * how the data was distributed across partitions,
         * whether repartition or coalesce was used before writing.

     Key understanding:
     - partitionBy creates one folder per distinct key value.
     - More keys = more folders. Low-cardinality columns (like 24-hour values) are ideal.

     Q: Why is groupBy(hour) a broad (wide) transformation?
     ------------------------------------------------------
     - To compute avg(temperature) for each hour, Spark must gather all rows
       with the same hour value together.
     - Those rows are originally spread across many partitions.
     - Spark must perform a shuffle so each reducer gets all rows for specific hour keys.
     - Shuffling involves:
         * data movement across the network,
         * serialization and deserialization,
         * disk spill if data is large,
         * shuffle read/write stages.
     - Any transformation that requires data for the same key to be colocated
       triggers a wide dependency — this is why groupBy is a broad transformation.

     -------------------------
     - Aggregations based on keys (like hour) always require shuffle unless the data is already
       perfectly partitioned by that key.
     - Partitioning output by hour makes reading analytics workloads faster because Spark
       scans only the required partitions (partition pruning).
     - Avoid partitioning by high-cardinality fields such as deviceId because this produces
       thousands of folders and many small files.
     - Parquet is optimal for analytical results:
         * compressed,
         * columnar,
         * enables predicate and partition pruning for faster reads.
     - groupBy + partitionBy is common in IoT, clickstream, and time-based datasets.

     -----------------
     - partitionBy creates one folder for each key value.
     - groupBy on a key leads to a shuffle, which is a wide transformation.
     - Parquet + partitioning is great for analytics but must be used with low-cardinality columns.
     - Understanding narrow vs broad transformations helps with performance tuning and cluster efficiency.


     ===============================================
    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
