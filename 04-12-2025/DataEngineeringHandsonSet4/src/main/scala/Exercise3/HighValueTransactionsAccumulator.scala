package Exercise3

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.types._

object HighValueTransactionsAccumulator {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // ---------- Input path (reuse 1M sales data) ----------
    val salesInputPath = conf.getString("app.salesInputPath")

    // ---------- S3 Credentials ----------
    val s3 = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ---------- Threshold ----------
    val threshold = 500.0  // “high value” transaction amount

    // ===== Spark Session =====
    val spark = SparkSession.builder()
      .appName("Exercise 3 - Accumulators for High-Value Transactions")
      .master("local[*]")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .getOrCreate()

    import spark.implicits._

    // ---------- Configure Hadoop S3A ----------
    val hconf = spark.sparkContext.hadoopConfiguration
    hconf.set("fs.s3a.endpoint", s3endpoint)
    hconf.set("fs.s3a.region",  s3region)
    hconf.set("fs.s3a.impl",    "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hconf.set("fs.s3a.path.style.access", "false")
    hconf.set("fs.s3a.connection.maximum", "100")
    hconf.set("fs.s3a.fast.upload", "true")
    hconf.set("fs.s3a.access.key",  s3accessKey)
    hconf.set("fs.s3a.secret.key",  s3secretKey)
    hconf.set(
      "fs.s3a.aws.credentials.provider",
      "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
    )

    // ===== 1) Load transactions =====
    // customerId, productId, quantity, amount
    val transactions: DataFrame = spark.read
      .format("csv")
      .option("header", "true")
      .schema(
        StructType(Seq(
          StructField("customerId", IntegerType, nullable = true),
          StructField("productId", IntegerType, nullable = true),
          StructField("quantity",  IntegerType, nullable = true),
          StructField("amount",    DoubleType,  nullable = true)
        ))
      )
      .load(salesInputPath)

    println("Sample transactions:")
    transactions.show(10, truncate = false)

    // ===== 2) Create accumulator for high-value transactions =====
    val highValueAcc = spark.sparkContext.longAccumulator("highValueTransactions")

    // ===== 3) Process in parallel & update accumulator =====
    transactions
      .select($"amount")
      .as[Double]
      .foreach { amt =>
        if (amt > threshold) {
          highValueAcc.add(1L)
        }
      }

    // ===== 4) Read accumulator value on the driver =====
    println(s"Total number of high-value transactions (amount > $threshold): ${highValueAcc.value}")

    // (cross-check using pure DataFrame API)
    val dfCount = transactions.filter($"amount" > threshold).count()
    println(s"(DataFrame count for verification) High-value transactions: $dfCount")

    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
