import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.spark.sql.functions._
import org.apache.hadoop.fs.{FileSystem, Path}
import scala.util.Try
import org.apache.spark.sql.Column
import com.typesafe.config.ConfigFactory


// Define the Trip case class
case class Trip(
                 tripId: Long,
                 driverId: Int,
                 vehicleType: String,
                 startTime: String,
                 endTime: String,
                 startLocation: String,
                 endLocation: String,
                 distanceKm: Double,
                 fareAmount: Double,
                 paymentMethod: String,
                 customerRating: Double
               )

object UrbanMovePipelines extends App {

  def arg(key: String, default: String) = {
    val i = args.indexWhere(_.equalsIgnoreCase(key))
    if (i >= 0 && i + 1 < args.length) args(i + 1) else default
  }

  val appConf = ConfigFactory.load()

  // helper to read from config safely
  def confGet(path: String, default: String): String =
    if (appConf.hasPath(path)) {
      val v = appConf.getString(path)
      if (v != null && v.trim.nonEmpty) v.trim else default
    } else default

  val csvPath = arg("--csv", confGet("app.csvPath", "urbanmove_trips.csv"))
  val rddOutput = arg("--rddOutput", confGet("app.rddOutput", "output/rdd_distance_gt_10"))
  val parquetPath = arg("--parquet", confGet("app.parquetPath", "parquet/trips_clean.parquet"))
  val reportPath = arg("--report", confGet("app.reportPath", "reports"))

  val jdbcUrl = arg("--jdbcUrl", confGet("slick.dbs.default.db.url", "jdbc:mysql://****************:3306/kumar"))
  val jdbcUser = arg("--jdbcUser", confGet("slick.dbs.default.db.user", "***********"))
  val jdbcPass = arg("--jdbcPass", confGet("slick.dbs.default.db.password", "***************"))

  val master = arg("--master", confGet("spark.master", "local[*]"))

  val spark = SparkSession.builder()
    .appName("UrbanMovePipelines")
    .master(master)
    .config("spark.sql.shuffle.partitions", "8")
    .getOrCreate()

  import spark.implicits._
  spark.sparkContext.setLogLevel("WARN")

  // small helper to delete a path if it exists
  def deleteIfExists(pathStr: String): Unit = {
    val path = new Path(pathStr)
    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    if (fs.exists(path)) {
      fs.delete(path, true)
      println(s"Deleted existing path: $pathStr")
    }
  }

  try {
    println(s"Loading CSV as RDD from: $csvPath")
    val rawRdd = spark.sparkContext.textFile(csvPath)

    val maybeHeader: Option[String] = Try(rawRdd.first()).toOption

    val headerLower = maybeHeader.map(_.toLowerCase).getOrElse("")

    val dataRdd =
      if (headerLower.contains("tripid")) {
        rawRdd.filter(line =>
          line.trim.nonEmpty &&
            !line.trim.toLowerCase.startsWith("tripid")
        )
      } else {
        rawRdd.filter(_.trim.nonEmpty)
      }

    // ---------- Pipeline 1: RDD processing (distance > 10)
    val distanceFilteredRdd = dataRdd
      .map(_.split(",", -1).map(_.trim))
      .filter(cols => cols.length >= 11)
      .flatMap { cols =>
        Try(cols(7).toDouble).toOption.map(d => (cols(2), d))
      }
      .filter { case (_, d) => d > 10.0 }

    // delete output if exists (so saveAsTextFile won't fail)
    deleteIfExists(rddOutput)
    distanceFilteredRdd
      .map { case (vt, d) => s"$vt,$d" }
      .saveAsTextFile(rddOutput)

    println(s"Pipeline1 done: saved distance>10 data to $rddOutput")

    // ---------- Pipeline 2: RDD -> Dataset using Trip case class
    val tripRdd = dataRdd
      .map(_.split(",", -1).map(_.trim))
      .filter(cols => cols.length >= 11)
      .flatMap { cols =>
        Try {
          Trip(
            tripId = cols(0).toLong,
            driverId = cols(1).toInt,
            vehicleType = cols(2),
            startTime = cols(3),
            endTime = cols(4),
            startLocation = cols(5),
            endLocation = cols(6),
            distanceKm = cols(7).toDouble,
            fareAmount = cols(8).toDouble,
            paymentMethod = cols(9),
            customerRating = cols(10).toDouble
          )
        }.toOption
      }

    val ds = tripRdd.toDS().cache()
    val df = ds.toDF()
    println(s"Pipeline2 done: DataFrame created with ${df.count()} rows")

    // ---------- Pipeline 3: clean with tolerant timestamps (trim microseconds -> millis)
    // convert microseconds to milliseconds (e.g. .268891 -> .268) so Spark's parser accepts it
    def trimToMillis(c: Column): Column =
      regexp_replace(trim(c), "\\.(\\d{3})\\d+", ".$1")

    val toTs = (colName: String) =>
      coalesce(
        // parse ISO with milliseconds after trimming to millis
        to_timestamp(trimToMillis(col(colName)), "yyyy-MM-dd'T'HH:mm:ss.SSS"),
        // fallback to space-separated pattern
        to_timestamp(trim(col(colName)), "yyyy-MM-dd HH:mm:ss"),
        // final fallback: let spark try default parsing (safe-guard)
        to_timestamp(trim(col(colName)))
      )

    val dfWithTs = df
      .withColumn("startTimeTs", toTs("startTime"))
      .withColumn("endTimeTs", toTs("endTime"))

    val dfClean = dfWithTs
      .filter($"distanceKm" > 0)
      .filter($"fareAmount" >= 0)
      .filter($"startTimeTs".isNotNull && $"endTimeTs".isNotNull && $"startTimeTs" < $"endTimeTs")
      .cache()

    println(s"Pipeline3 done: cleaned -> ${dfClean.count()} rows")

    // ---------- Pipeline 4: duration (minutes)
    val dfWithDuration = dfClean.withColumn(
      "tripDurationMinutes",
      (unix_timestamp($"endTimeTs") - unix_timestamp($"startTimeTs")) / lit(60.0)
    ).cache()

    println("Pipeline4 done: tripDurationMinutes derived")

    // ---------- Pipeline 5: aggregates
    val avgDistanceByVehicle = dfWithDuration
      .groupBy("vehicleType")
      .agg(avg("distanceKm").alias("avgDistanceKm"))

    val dfWithDate = dfWithDuration.withColumn("tripDate", to_date($"startTimeTs"))
    val revenuePerDay = dfWithDate
      .groupBy("tripDate")
      .agg(sum("fareAmount").alias("totalRevenue"))

    val routeUsage = dfWithDuration
      .groupBy("startLocation", "endLocation")
      .count()
      .orderBy(desc("count"))
      .limit(5)

    println("Pipeline5 previews:")
    avgDistanceByVehicle.show(false)
    revenuePerDay.orderBy($"tripDate".desc).show(5, false)
    routeUsage.show(false)

    // ---------- Pipeline 6: SQL queries
    dfWithDuration.createOrReplaceTempView("trips")

    val vehicleTypeCounts = spark.sql(
      """SELECT vehicleType, COUNT(*) AS tripCount FROM trips GROUP BY vehicleType ORDER BY tripCount DESC"""
    )

    val paymentMethodCounts = spark.sql(
      """SELECT paymentMethod, COUNT(*) AS cnt FROM trips GROUP BY paymentMethod"""
    )

    println("Pipeline6 previews:")
    vehicleTypeCounts.show(false)
    paymentMethodCounts.show(false)

    // ---------- Pipeline 7: write parquet partitioned by tripDate
    val finalDfForWrite = dfWithDuration.withColumn("tripDate", to_date($"startTimeTs"))
    deleteIfExists(parquetPath)
    finalDfForWrite
      .write
      .mode(SaveMode.Overwrite)
      .partitionBy("tripDate")
      .parquet(parquetPath)

    println(s"Pipeline7 done: parquet written to $parquetPath (partitioned by tripDate)")

    // ---------- Pipeline 8: read parquet & re-aggregate
    val pq = spark.read.parquet(parquetPath)
    val avgFareByVehicleFromParquet = pq.groupBy("vehicleType").agg(avg("fareAmount").alias("avgFareAmount"))
    println("Pipeline8 preview:")
    avgFareByVehicleFromParquet.show(false)

    // ---------- Pipeline 9: write to MySQL (best-effort)
    Try {
      finalDfForWrite
        .write
        .format("jdbc")
        .option("url", jdbcUrl)
        .option("dbtable", "trip_summary")
        .option("user", jdbcUser)
        .option("password", jdbcPass)
        .option("driver", "com.mysql.cj.jdbc.Driver")
        .option("rewriteBatchedStatements", "true")
        .option("sslMode", "DISABLED")
        .mode(SaveMode.Append)
        .save()
      println(s"Pipeline9 done: data appended to MySQL at $jdbcUrl")
    }.recover { case ex =>
      println(s"Pipeline9 skipped (jdbc write failed): ${ex.getMessage}")
    }

    // ---------- Pipeline 10: export report to single CSV (coalesce(1))
    // Use DataFrame writer with overwrite semantics instead of rdd.saveAsTextFile
    deleteIfExists(reportPath)
    avgFareByVehicleFromParquet
      .coalesce(1)
      .write
      .mode(SaveMode.Overwrite)
      .option("header", "false")
      .csv(reportPath) // creates a directory with one part-*.csv file

    println(s"Pipeline10 done: report exported to $reportPath (coalesced CSV)")

    println("All pipelines completed successfully.")
  } catch {
    case t: Throwable =>
      println("Job failed: " + t.getMessage)
      t.printStackTrace()
  } finally {
    spark.stop()
  }
}
