package Exercise1

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession

object GenerateBroadcastExerciseData {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // ---------- Paths from application.conf ----------
    val transactionsPath   = conf.getString("app.transactionsPath")     // e.g. s3a://your-bucket/transactions/
    val exchangeRatesPath  = conf.getString("app.exchangeRatesPath")   // e.g. s3a://your-bucket/exchange_rates/

    // ---------- S3 Credentials ----------
    val s3 = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ---------- Spark Session ----------
    val spark = SparkSession.builder()
      .appName("Generate Data - Exercise 1 Broadcasting")
      .master("local[*]")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .getOrCreate()

    import spark.implicits._

    // ---------- Configure Hadoop S3A ----------
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

    // ==============================
    // 1) Create transactions dataset
    // ==============================
    val transactionsDf = Seq(
      (1L, 100.50, "USD"),
      (2L, 75.20,  "EUR"),
      (3L, 5600.0, "INR"),
      (4L, 350.0,  "GBP"),
      (5L, 999.99, "USD"),
      (6L, 120.00, "EUR"),
      (7L, 4500.0, "INR"),
      (8L, 50.0,   "JPY"),
      (9L, 75.55,  "EUR"),
      (10L, 820.40,"GBP"),
      (11L,1000.0, "AUD"),
      (12L,2300.0, "INR"),
      (13L,63.50,  "EUR"),
      (14L,180.0,  "USD"),
      (15L,450.0,  "GBP")
    ).toDF("txn_id", "amount", "currency")

    println("Sample transactions:")
    transactionsDf.show(false)

    // Write as CSV with header to S3
    transactionsDf
      .write
      .mode("overwrite")
      .option("header", "true")
      .csv(transactionsPath)  // e.g. s3a://your-bucket/transactions/

    println(s"✅ Wrote transactions CSV to: $transactionsPath")

    // ==============================
    // 2) Create exchange rates dataset (small → broadcast later)
    // ==============================
    val exchangeRatesDf = Seq(
      ("USD", 1.0),
      ("EUR", 1.10),
      ("INR", 0.012),
      ("GBP", 1.25),
      ("JPY", 0.007),
      ("AUD", 0.66),
      ("CAD", 0.75),
      ("CHF", 1.08)
    ).toDF("currency", "rate_to_usd")

    println("Exchange rates:")
    exchangeRatesDf.show(false)

    exchangeRatesDf
      .write
      .mode("overwrite")
      .option("header", "true")
      .csv(exchangeRatesPath)  // e.g. s3a://your-bucket/exchange_rates/

    println(s"✅ Wrote exchange_rates CSV to: $exchangeRatesPath")

    spark.stop()
  }
}
