import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object CityCountsBenchmark {
  def main(args: Array[String]): Unit = {
    // ====== Configurable parameters ======
    val numRecords = 5000000
    val numCities  = 50
    val partitions = 50
    val baseOut = "city_counts_benchmark"

    // ====== Spark session ======
    val spark = SparkSession.builder()
      .appName("CityCountsBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // set Parquet compression to snappy (common default)
    spark.conf.set("spark.sql.parquet.compression.codec", "snappy")

    println(s"Parameters: numRecords=$numRecords, numCities=$numCities, partitions=$partitions, baseOut=$baseOut")
    println("Generating synthetic customer data...")

    // ====== Data generation (RDD) ======
    val cities = (1 to numCities).map(i => s"City_$i").toArray

    // parallelize IDs so generation is parallel; keep Random per partition
    val customersRDD = spark.sparkContext.parallelize(1 to numRecords, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val name = rnd.alphanumeric.take(10).mkString
          val age = 18 + rnd.nextInt(53)
          val city = cities(rnd.nextInt(cities.length))
          // tuple: (customerId, name, age, city)
          (id.toLong, name, age, city)
        }
      }

    println("Done generating RDD. Sample:")
    customersRDD.take(5).foreach(println)

    // ====== RDD approach: reduceByKey ======
    println("\n--- RDD: reduceByKey (city counts) ---")
    val startRdd = System.nanoTime()
    val cityCountsRDD = customersRDD
      .map { case (_, _, _, city) => (city, 1L) } // narrow
      .reduceByKey(_ + _)                         // wide (shuffle)
    val rddCountsCollected = cityCountsRDD.collect().sortBy(_._1)
    val elapsedRdd = (System.nanoTime() - startRdd) / 1000000L
    println(s"RDD reduceByKey finished in ${elapsedRdd} ms. Result sample:")
    rddCountsCollected.foreach(println)

    // ====== Convert to DataFrame and DataFrame approach ======
    val customersDF = customersRDD.toDF("customerId", "name", "age", "city")
    println("\n--- DataFrame: groupBy city and count ---")
    val startDfAgg = System.nanoTime()
    val dfCityCounts = customersDF.groupBy("city").count() // wide (shuffle)
    val dfCounts = dfCityCounts.orderBy("city").collect()
    val elapsedDfAgg = (System.nanoTime() - startDfAgg) / 1000000L
    println(s"DataFrame groupBy finished in ${elapsedDfAgg} ms. Result sample:")
    dfCounts.foreach(row => println(row))

    // ====== Helpers: time write and get directory size ======
    def timeMs[T](block: => T): (T, Long) = {
      val t0 = System.nanoTime()
      val result = block
      val t1 = System.nanoTime()
      (result, (t1 - t0) / 1000000L)
    }

    def getDirSizeBytes(pathStr: String): Long = {
      val path = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (!fs.exists(path)) 0L else fs.getContentSummary(path).getLength
    }

    def rmIfExists(pathStr: String): Unit = {
      val p = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (fs.exists(p)) fs.delete(p, true)
    }

    // Paths
    val csvPath = s"$baseOut/csv"
    val jsonPath = s"$baseOut/json"
    val parquetPath = s"$baseOut/parquet"

    // Remove previous runs
    rmIfExists(csvPath); rmIfExists(jsonPath); rmIfExists(parquetPath)

    // Write CSV
    println("\nWriting CSV...")
    val (_, csvTime) = timeMs {
      dfCityCounts.write.mode(SaveMode.Overwrite).option("header", "true").csv(csvPath)
    }
    val csvSize = getDirSizeBytes(csvPath)
    println(s"CSV write time = ${csvTime} ms; size = ${csvSize} bytes; path=$csvPath")

    // Write JSON
    println("\nWriting JSON...")
    val (_, jsonTime) = timeMs {
      dfCityCounts.write.mode(SaveMode.Overwrite).json(jsonPath)
    }
    val jsonSize = getDirSizeBytes(jsonPath)
    println(s"JSON write time = ${jsonTime} ms; size = ${jsonSize} bytes; path=$jsonPath")

    // Write Parquet
    println("\nWriting Parquet...")
    val (_, parquetTime) = timeMs {
      dfCityCounts.write.mode(SaveMode.Overwrite).parquet(parquetPath)
    }
    val parquetSize = getDirSizeBytes(parquetPath)
    println(s"Parquet write time = ${parquetTime} ms; size = ${parquetSize} bytes; path=$parquetPath")

    // ====== Print summary & observations ======
    println("\n================ SUMMARY ================")
    println(f"RDD reduceByKey time: ${elapsedRdd} ms")
    println(f"DF groupBy time:      ${elapsedDfAgg} ms")
    println("")
    println("Write times (ms):")
    println(f"  CSV    : $csvTime%12d ms")
    println(f"  JSON   : $jsonTime%12d ms")
    println(f"  Parquet: $parquetTime%12d ms")
    println("")
    println("Output sizes (bytes):")
    println(f"  CSV    : $csvSize%12d bytes")
    println(f"  JSON   : $jsonSize%12d bytes")
    println(f"  Parquet: $parquetSize%12d bytes")
    println("")

    /*
     ===============================================
      Typical Observations
     ===============================================

      • CSV often writes fastest because it is simple text
        serialization with no column encoding or compression.

      • JSON output is usually larger than CSV because it includes
        braces, quotes, and field names for each row — very verbose.

      • Parquet produces the smallest files because it is columnar,
        uses dictionary encoding, and compresses data (Snappy).

      • Parquet may write slower due to metadata + encoding work,
        but it reads *much faster* for analytics (column pruning,
        predicate pushdown).

     ===============================================
      Narrow vs Wide Operations in This Pipeline
     ===============================================

      • Narrow operations:
          - map (extracting city)
          - mapPartitions (during synthetic data generation)
          - toDF (local conversion, no shuffle)

      • Wide operations:
          - reduceByKey (shuffle required to group keys globally)
          - groupBy (shuffle across partitions)

    */

    println("==========================================")

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
