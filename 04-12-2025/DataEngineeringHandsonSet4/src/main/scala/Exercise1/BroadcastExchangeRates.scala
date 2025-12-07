package Exercise1

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

object BroadcastExchangeRates {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // ---------- S3 Input / Output Paths from config ----------
    val transactionsPath    = conf.getString("app.transactionsPath")
    val exchangeRatesPath   = conf.getString("app.exchangeRatesPath")
    val aggOutputPath       = conf.getString("app.broadcastOutputPath")
    val convertedOutputPath = conf.getString("app.broadcastConvertedOutputPath")

    // ---------- S3 Credentials ----------
    val s3          = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ===== Spark Session =====
    val spark = SparkSession.builder()
      .appName("Exercise 1 - Broadcasting Exchange Rates")
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

    // ===== Schemas =====
    val transactionsSchema = StructType(Seq(
      StructField("txn_id", LongType, nullable = true),
      StructField("amount", DoubleType, nullable = true),
      StructField("currency", StringType, nullable = true)
    ))

    val exchangeRatesSchema = StructType(Seq(
      StructField("currency", StringType, nullable = false),
      StructField("rate_to_usd", DoubleType, nullable = false)
    ))

    // ===== 1) Load transactions (large dataset) =====
    val transactions: DataFrame = spark.read
      .format("csv")
      .option("header", "true")
      .schema(transactionsSchema)
      .load(transactionsPath)

    // ===== 2) Load exchange rates (small dataset) =====
    val exchangeRates: DataFrame = spark.read
      .format("csv")
      .option("header", "true")
      .schema(exchangeRatesSchema)
      .load(exchangeRatesPath)

    // ===== 3) Broadcast =====
    import org.apache.spark.sql.functions.broadcast

    val converted = transactions
      .join(
        broadcast(exchangeRates),
        Seq("currency"),
        "left"
      )
      .withColumn("amount_usd", col("amount") * col("rate_to_usd"))

    println("Sample converted transactions:")
    converted.show(10, truncate = false)

    // ===== 4) Count transactions per currency =====
    val txnsPerCurrency = converted
      .groupBy("currency")
      .count()
      .orderBy(desc("count"))

    println("Number of transactions per currency:")
    txnsPerCurrency.show(100, truncate = false)

    // ===== 5) Write aggregated result =====
    txnsPerCurrency
      .write
      .mode("overwrite")
      .option("header", "true")
      .csv(aggOutputPath)

    println(s"Wrote aggregated CSV to: $aggOutputPath")

    // ===== 6) Write full converted dataset =====
    converted
      .write
      .mode("overwrite")
      .option("header", "true")
      .csv(convertedOutputPath)

    println(s"Wrote converted transactions CSV to: $convertedOutputPath")

    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)

    spark.stop()
  }
}
