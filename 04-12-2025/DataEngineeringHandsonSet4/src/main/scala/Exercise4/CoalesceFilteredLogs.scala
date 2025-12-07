package Exercise4

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

object CoalesceFilteredLogs {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // ---------- S3 Input / Output Paths ----------
    val logsInputPath                    = conf.getString("app.logsInputPath")
    val logsFilteredNoCoalesceOutputPath = conf.getString("app.logsFilteredNoCoalesceOutputPath")
    val logsFilteredCoalesceOutputPath   = conf.getString("app.logsFilteredCoalesceOutputPath")

    // ---------- S3 Credentials ----------
    val s3 = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ===== Spark Session =====
    val spark = SparkSession.builder()
      .appName("Exercise 4 - Coalesce Filtered Logs")
      .master("local[*]")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .getOrCreate()

    import spark.implicits._

    // ---------- Configure Hadoop S3A credentials ----------
    val hconf = spark.sparkContext.hadoopConfiguration

    hconf.set("fs.s3a.endpoint", s3endpoint)
    hconf.set("fs.s3a.region", s3region)
    hconf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hconf.set("fs.s3a.path.style.access", "false")
    hconf.set("fs.s3a.connection.maximum", "100")
    hconf.set("fs.s3a.fast.upload", "true")
    hconf.set("fs.s3a.access.key", s3accessKey)
    hconf.set("fs.s3a.secret.key", s3secretKey)
    hconf.set(
      "fs.s3a.aws.credentials.provider",
      "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
    )

    // ===== Helper: timing function =====
    def time[A](label: String)(f: => A): A = {
      val t0  = System.nanoTime()
      val res = f
      val t1  = System.nanoTime()
      val ms  = (t1 - t0) / 1e6
      println(f"⏱  $label took $ms%.2f ms")
      res
    }

    // ===== 1) Load logs dataset from S3 =====
    val logsSchema = StructType(Seq(
      StructField("timestamp", StringType,  nullable = true),
      StructField("level",     StringType,  nullable = true),  // INFO / WARN / ERROR etc.
      StructField("service",   StringType,  nullable = true),
      StructField("message",   StringType,  nullable = true)
    ))

    val logs: DataFrame = spark.read
      .format("csv")
      .option("header", "true")
      .schema(logsSchema)
      .load(logsInputPath)

    println(s"Original number of partitions: ${logs.rdd.getNumPartitions}")
    println(s"Total rows in logs: ${logs.count()}")

    // ===== 2) Filter logs: keep only ERRORs =====
    // Cache the filtered dataset because we will:
    //  - count() it
    //  - write WITHOUT coalesce
    //  - coalesce() and write again
    val filtered = logs
      .filter(col("level") === "ERROR")
      .cache()

    // Materialize cache (so S3 is read once)
    time("Materialize filtered ERROR logs cache (count)") {
      println(s"Filtered rows (ERROR only): ${filtered.count()}")
    }

    println(s"Filtered partitions (before coalesce): ${filtered.rdd.getNumPartitions}")

    // ===== 3) Write WITHOUT coalesce =====
    time("Write filtered logs WITHOUT coalesce") {
      filtered
        .write
        .mode("overwrite")
        .option("header", "true")
        .csv(logsFilteredNoCoalesceOutputPath)
    }

    // Count number of part files
    val fsNoCoalesce = FileSystem.get(new java.net.URI(logsFilteredNoCoalesceOutputPath), hconf)
    val noCoalesceDir = new Path(logsFilteredNoCoalesceOutputPath)
    val noCoalesceFiles =
      fsNoCoalesce
        .listStatus(noCoalesceDir)
        .map(_.getPath)
        .filter(p => p.getName.startsWith("part-"))

    println(s"Files WITHOUT coalesce: ${noCoalesceFiles.length}")

    // ===== 4) Use coalesce to reduce partitions =====
    val targetPartitions = math.max(1, filtered.rdd.getNumPartitions / 4) // 4x fewer
    println(s"Target partitions with coalesce: $targetPartitions")

    val coalesced = filtered.coalesce(targetPartitions)

    println(s"Partitions AFTER coalesce: ${coalesced.rdd.getNumPartitions}")

    time("Write filtered logs WITH coalesce") {
      coalesced
        .write
        .mode("overwrite")
        .option("header", "true")
        .csv(logsFilteredCoalesceOutputPath)
    }

    // Count number of part files for coalesced output
    val fsCoalesce = FileSystem.get(new java.net.URI(logsFilteredCoalesceOutputPath), hconf)
    val coalesceDir = new Path(logsFilteredCoalesceOutputPath)
    val coalesceFiles =
      fsCoalesce
        .listStatus(coalesceDir)
        .map(_.getPath)
        .filter(p => p.getName.startsWith("part-"))

    println(s"Files WITH coalesce: ${coalesceFiles.length}")

    // ===== 5) Summary =====
    println("==== SUMMARY ====")
    println(s"Original partitions:             ${logs.rdd.getNumPartitions}")
    println(s"Filtered partitions (raw):       ${filtered.rdd.getNumPartitions}")
    println(s"Coalesced partitions:            ${coalesced.rdd.getNumPartitions}")
    println(s"Output files WITHOUT coalesce:   ${noCoalesceFiles.length}")
    println(s"Output files WITH coalesce:      ${coalesceFiles.length}")
    println("Note: fewer part files = fewer small files → faster downstream reading.")

    // Unpersist to free memory when done
    filtered.unpersist()

    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
