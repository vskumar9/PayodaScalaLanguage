package Exercise2

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object GenerateSalesData {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // Output to S3
    val salesOutputPath = conf.getString("app.salesInputPath")

    // ---------- S3 Credentials ----------
    val s3 = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ===== Spark Session =====
    val spark = SparkSession.builder()
      .appName("Generate 1M Sales Records")
      .master("local[*]")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .getOrCreate()

    import spark.implicits._

    // ---------- Configure S3A credentials ----------
    val hconf = spark.sparkContext.hadoopConfiguration
    hconf.set("fs.s3a.endpoint", s3endpoint)
    hconf.set("fs.s3a.region", s3region)
    hconf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hconf.set("fs.s3a.path.style.access", "false")
    hconf.set("fs.s3a.connection.maximum", "100")
    hconf.set("fs.s3a.fast.upload", "true")
    hconf.set("fs.s3a.access.key", s3accessKey)
    hconf.set("fs.s3a.secret.key", s3secretKey)
    hconf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")

    // ======================================================
    // Generate Synthetic Sales Records
    // ======================================================
    val totalRows = 10000

    val df = spark.range(totalRows)
      .withColumn("customerId", (rand() * 100000).cast("int"))  // 100k customers
      .withColumn("productId", (rand() * 5000).cast("int"))     // 5000 products
      .withColumn("quantity", (rand() * 10 + 1).cast("int"))    // 1–10 units
      .withColumn("amount", (rand() * 500 + 10))                // $10–$510
      .select(
        col("customerId"),
        col("productId"),
        col("quantity"),
        col("amount")
      )

    println("Sample generated data:")
    df.show(20, truncate = false)

    // ======================================================
    // Write 1M records to S3 as CSV
    // ======================================================
    df.write
      .mode("overwrite")
      .option("header", "true")
      .csv(salesOutputPath)

    println(s"Successfully created 1M rows at: $salesOutputPath")

    spark.stop()
  }
}
