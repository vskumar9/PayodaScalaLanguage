package Exercise2

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

object CacheSalesAggregations {

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.load()

    // ---------- S3 Input / Output Paths ----------
    val salesInputPath        = conf.getString("app.salesInputPath")
    val customerAggOutputPath = conf.getString("app.customerAggOutputPath")
    val productAggOutputPath  = conf.getString("app.productAggOutputPath")

    // ---------- S3 Credentials ----------
    val s3 = conf.getConfig("keyspaces")
    val s3accessKey = s3.getString("accesskey")
    val s3secretKey = s3.getString("secretkey")
    val s3region    = s3.getString("region")
    val s3endpoint  = s3.getString("endpoint")

    // ===== Spark Session =====
    val spark = SparkSession.builder()
      .appName("Exercise 2 - Caching Sales Dataset")
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

    // ===== 1) Load sales dataset from S3 =====
    // Schema: customerId, productId, quantity, amount
    val salesSchema = StructType(Seq(
      StructField("customerId", StringType, nullable = true),
      StructField("productId", StringType, nullable = true),
      StructField("quantity", IntegerType, nullable = true),
      StructField("amount", DoubleType, nullable = true)
    ))

    val salesRaw: DataFrame = spark.read
      .format("csv")
      .option("header", "true")
      .schema(salesSchema)
      .load(salesInputPath)

    // ===== use only a subset of data =====
    // use 5% sample
    val sales: DataFrame = salesRaw.sample(withReplacement = false, fraction = 0.05)

    // 1M rows
    // val sales: DataFrame = salesRaw.limit(1000000)

    println(s"Total rows in full dataset: ${salesRaw.count()}")
    println(s"Rows used for dev run (sample/limit): ${sales.count()}")

    println("Sample sales data:")
    sales.show(5, truncate = false)

    // ==============================
    // RUN WITHOUT CACHING
    // ==============================
    println("=== Running aggregations WITHOUT cache ===")

    val customerAggNoCache = time("Total amount per customer (NO CACHE)") {
      sales
        .groupBy("customerId")
        .agg(
          sum(col("amount")).alias("total_amount_spent")
        )
        .orderBy(desc("total_amount_spent"))
        .cache()  // cache result so show() + write don’t recompute
    }

    customerAggNoCache.show(10, truncate = false)

    val productAggNoCache = time("Total quantity per product (NO CACHE)") {
      sales
        .groupBy("productId")
        .agg(
          sum(col("quantity")).alias("total_quantity_sold")
        )
        .orderBy(desc("total_quantity_sold"))
        .cache()
    }

    productAggNoCache.show(10, truncate = false)

    // ==============================
    // RUN WITH CACHING (on same subset)
    // ==============================
    println("=== Caching base sales dataset and re-running aggregations ===")

    val cachedSales = sales.cache()

    // Materialize cache once
    time("Cached sales.count()") {
      cachedSales.count()
    }

    val customerAggCached = time("Total amount per customer (WITH CACHE)") {
      cachedSales
        .groupBy("customerId")
        .agg(
          sum(col("amount")).alias("total_amount_spent")
        )
        .orderBy(desc("total_amount_spent"))
    }

    val productAggCached = time("Total quantity per product (WITH CACHE)") {
      cachedSales
        .groupBy("productId")
        .agg(
          sum(col("quantity")).alias("total_quantity_sold")
        )
        .orderBy(desc("total_quantity_sold"))
    }

    println("Top customers by amount (WITH CACHE):")
    customerAggCached.show(10, truncate = false)

    println("Top products by quantity (WITH CACHE):")
    productAggCached.show(10, truncate = false)

    // ==============================
    // WRITE RESULTS TO S3 (from cached run)
    // ==============================
    time("Write customerAggCached to S3") {
      customerAggCached
        .write
        .mode("overwrite")
        .option("header", "true")
        .csv(customerAggOutputPath)
    }

    time("Write productAggCached to S3") {
      productAggCached
        .write
        .mode("overwrite")
        .option("header", "true")
        .csv(productAggOutputPath)
    }

    println(s"Wrote customer aggregates to: $customerAggOutputPath")
    println(s"Wrote product aggregates to:  $productAggOutputPath")

    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    cachedSales.unpersist()

    spark.stop()
  }
}
