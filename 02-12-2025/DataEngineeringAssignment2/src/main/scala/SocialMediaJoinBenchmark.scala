import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object SocialMediaJoinBenchmark {
  def main(args: Array[String]): Unit = {
    // ====== Configurable parameters ======
    val userCount   = 1000000    // 1M users
    val postCount   = 2000000    // 2M posts
    val userParts   = 30
    val postParts   = 40
    val baseOut     = "social_join_benchmark"

    // ====== Spark session ======
    val spark = SparkSession.builder()
      .appName("SocialMediaJoinBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Parquet/JSON compression default (optional)
    spark.conf.set("spark.sql.parquet.compression.codec", "snappy")

    println(s"Parameters: userCount=$userCount, postCount=$postCount, userParts=$userParts, postParts=$postParts, baseOut=$baseOut")

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

    // ====== Generate Users RDD -> DF ======
    println("\nGenerating Users RDD...")
    val userRDD = spark.sparkContext.parallelize(1 to userCount, userParts)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val name = rnd.alphanumeric.take(8).mkString
          val age = 15 + rnd.nextInt(60) // 15..74
          (id, name, age)
        }
      }
    val userDF = userRDD.toDF("userId", "name", "age")
    println("Users sample:")
    userDF.show(5, truncate = false)

    // ====== Generate Posts RDD -> DF ======
    println("\nGenerating Posts RDD...")
    val postRDD = spark.sparkContext.parallelize(1 to postCount, postParts)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { pid =>
          val user = rnd.nextInt(userCount) + 1
          val txt = rnd.alphanumeric.take(20).mkString
          (pid, user, txt)
        }
      }
    val postDF = postRDD.toDF("postId", "userId", "text")
    println("Posts sample:")
    postDF.show(5, truncate = false)

    // ====== Join Users and Posts (DataFrame) ======
    println("\nPerforming DataFrame join (posts JOIN users on userId)...")
    val (joinedRows, joinTime) = timeMs {
      // inner join; result has posts enriched with user age/name
      val j = postDF.join(userDF, Seq("userId"), "inner")
      // materialize by counting
      j.count()
    }
    println(s"Join produced ${joinedRows} rows (should be <= postCount). Time = ${joinTime} ms")

    // ====== Compute posts per age-group ======
    println("\nComputing posts per age-group...")
    // Define age-groups: 15-24, 25-34, 35-44, 45-54, 55-64, 65+
    import org.apache.spark.sql.functions._
    val bucketUdf = udf { age: Int =>
      if (age < 25) "15-24"
      else if (age < 35) "25-34"
      else if (age < 45) "35-44"
      else if (age < 55) "45-54"
      else if (age < 65) "55-64"
      else "65+"
    }

    val (aggRows, aggTime) = timeMs {
      val joined = postDF.join(userDF.select("userId", "age"), Seq("userId"), "inner")
      val withBucket = joined.withColumn("ageGroup", bucketUdf(col("age")))
      val agg = withBucket.groupBy("ageGroup").count().orderBy("ageGroup")
      agg.collect()
    }
    println(s"Aggregation (posts per age-group) completed in ${aggTime} ms; sample:")
    aggRows.foreach(println)

    // ====== Write results to JSON ======
    val resultDF = {
      val joined = postDF.join(userDF.select("userId", "age"), Seq("userId"), "inner")
      val withBucket = joined.withColumn("ageGroup", bucketUdf(col("age")))
      withBucket.groupBy("ageGroup").count().orderBy("ageGroup")
    }

    val jsonOut = s"$baseOut/posts_per_agegroup_json"
    rmIfExists(jsonOut)
    println(s"\nWriting posts-per-age-group to JSON at: $jsonOut")
    val (_, jsonWriteTime) = timeMs {
      resultDF.write.mode(SaveMode.Overwrite).json(jsonOut)
    }
    val jsonSize = getDirSizeBytes(jsonOut)
    println(s"JSON write time = ${jsonWriteTime} ms; size = ${jsonSize} bytes; path=$jsonOut")

    // ====== Summary ======
    println("\n================ SUMMARY ================")
    println(s"Join time (count materialize)           : ${joinTime} ms")
    println(s"Aggregation time (posts per age-group)   : ${aggTime} ms")
    println(s"JSON write time                          : ${jsonWriteTime} ms")
    println(s"JSON output size (bytes)                 : ${jsonSize}")
    println("==========================================")

    /*
     ===============================================
      OBSERVATIONS & ANSWERS
     ===============================================

      Context (what this program does)
     --------------------------------
     - Generates 1,000,000 synthetic users and 2,000,000 posts.
     - Joins posts with users on userId to enrich posts with user age.
     - Buckets ages into groups (15-24, 25-34, ...), counts posts per age-group.
     - Writes the aggregated result to JSON and prints timings and output size.

     Q: Why does the join cause a shuffle (broad)?
     ---------------------------------------------
     - A distributed join must bring matching keys from both sides (posts and users)
       to the same executor so rows can be paired. If rows for the same userId live
       on different partitions, Spark redistributes data by the join key -> this is a shuffle.
     - Shuffle moves bytes over the network, writes temp files, and can trigger disk spill,
       so it is the most expensive part of many distributed jobs.
     - If one side is small enough to broadcast, Spark can avoid the shuffle by sending
       that side to all executors (broadcast join). With 1M users and 2M posts, broadcasting
       the users DF is usually not feasible.

     Q: Why is DataFrame join easier and often faster than RDD join?
     --------------------------------------------------------------
     - Declarative API: DataFrames let Spark's Catalyst optimizer plan and optimize the query
       (choose broadcast vs shuffle-hash vs sort-merge, push down projections/filters).
     - Efficient execution: DataFrames benefit from whole-stage codegen, optimized serializers,
       and vectorized/batch processing that reduce CPU and memory overhead.
     - RDD joins are low-level: you must create pair RDDs, choose a partitioner, and handle
       combiners manually — more error-prone and typically slower.

     Q: How to reduce shuffle or speed up this join?
     -----------------------------------------------
     - Broadcast small side: if users DF becomes small enough (< configured threshold),
       use broadcast(userDF) or set spark.sql.autoBroadcastJoinThreshold appropriately.
     - Repartition by join key: repartition(postDF, userDF) by userId before join so rows
       for the same key are colocated (reduces unnecessary shuffle work).
     - Aggregate before join: if you only need aggregates from posts, compute totals per user
       first, then join with users to reduce data volume.
     - Handle skew: if a few userIds are extremely hot, apply salting or custom partitioning
       to avoid reducer hotspots.

     Q: Why did we compute and write the aggregated age-groups instead of raw join output?
     ------------------------------------------------------------------------------------
     - Aggregating reduces data volume (one row per age group vs many posts), making the result
       smaller and easier to store/inspect.
     - Writing aggregated results (JSON/Parquet) is common in analytics pipelines that produce
       summarized datasets for downstream reporting.

     -------------------------------------------------------
     - For local testing, set spark.sql.shuffle.partitions to a reasonable number (not the default 200):
         spark.conf.set("spark.sql.shuffle.partitions", "40")
     - Prefer DataFrame/Dataset API for joins and aggregations in production pipelines.
     - When producing final analytic output, prefer Parquet over JSON for speed and storage efficiency.
     - Only collect small results to the driver (e.g., .limit(10).collect()); avoid collecting full joins.
     - Monitor the Spark UI (Stages / SQL tab) to inspect shuffle read/write sizes, task skew, and stage durations.
     - When benchmarking, run multiple iterations and use medians; warm up the JVM and cache reused small tables if appropriate.

     ----------------
     - Joins and aggregations are shuffle-heavy operations; minimizing data shuffled (via broadcast, pre-aggregation,
       or partitioning) is the single best lever to improve performance.
     - DataFrame APIs provide optimizer-driven performance advantages over raw RDD operations.
     - Choose storage format (JSON vs Parquet) based on downstream needs: Parquet for analytics, JSON for interoperability.
     ===============================================

    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
