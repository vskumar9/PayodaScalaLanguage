package Exercise4

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object GenerateLogs {

  def main(args: Array[String]): Unit = {

    // ---------- Load config ----------
    val conf = ConfigFactory.load()

    val logsOutputPath = conf.getString("app.logsInputPath")

    // ---------- S3 Credentials from keyspaces ----------
    val s3         = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ===== Spark Session =====
    val spark = SparkSession.builder()
      .appName("Generate Logs")
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

    // ===== Data for randomization =====
    val levels    = Seq("INFO", "WARN", "ERROR")
    val services  = Seq("auth-service", "payment-service", "orders-service", "cache-service")

    val levelsExpr   = array(levels.map(lit): _*)
    val servicesExpr = array(services.map(lit): _*)

    // ===== Generate log rows =====
    val logsDF = spark.range(1, 10000)
      .withColumn("timestamp", current_timestamp())
      .withColumn("level",   element_at(levelsExpr,   (rand() * levels.length   + 1).cast("int")))
      .withColumn("service", element_at(servicesExpr, (rand() * services.length + 1).cast("int")))
      .withColumn("message", concat(lit("Log message "), col("id")))
      .drop("id")

    println("Sample generated logs:")
    logsDF.show(10, truncate = false)

    // ===== Write logs to S3 using path from config =====
    logsDF
      .coalesce(10)                    // fewer output files
      .write
      .mode("overwrite")
      .option("header", "true")
      .csv(logsOutputPath)

    println(s"Logs written to: $logsOutputPath")

    spark.stop()
  }
}
