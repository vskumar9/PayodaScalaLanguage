package pipeline3

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object ParquetToProductAggregates {
  def main(args: Array[String]): Unit = {
    val conf = ConfigFactory.load()
    val appConf = conf.getConfig("app")

    // S3 / app config
    val parquetInput = appConf.getString("parquetPath")
    val outputDir = appConf.getString("aggregatesPath")
    val finalFileName = "products.json"

    // Key/secret
    val s3conf = conf.getConfig("keyspaces")
    val s3accesskey = s3conf.getString("accesskey")
    val s3secretkey = s3conf.getString("secretkey")
    val s3region = if (s3conf.hasPath("region")) s3conf.getString("region") else "us-east-1"
    val s3endpoint = if (s3conf.hasPath("endpoint")) s3conf.getString("endpoint") else ""

    // Build Spark session early with S3A provider set
    val builder = SparkSession.builder()
      .appName("Parquet -> Product Aggregates -> JSON")
      .master("local[*]")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .config("spark.hadoop.fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")
      .config("spark.hadoop.fs.s3a.access.key", s3accesskey)
      .config("spark.hadoop.fs.s3a.secret.key", s3secretkey)
      .config("spark.hadoop.fs.s3a.region", s3region)

    if (s3endpoint.nonEmpty) builder.config("spark.hadoop.fs.s3a.endpoint", s3endpoint)

    val spark = builder.getOrCreate()
    import spark.implicits._

    // ensure Hadoop conf also has same s3a settings
    val hconf = spark.sparkContext.hadoopConfiguration
    hconf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hconf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")
    hconf.set("fs.s3a.access.key", s3accesskey)
    hconf.set("fs.s3a.secret.key", s3secretkey)
    if (s3endpoint.nonEmpty) hconf.set("fs.s3a.endpoint", s3endpoint) else hconf.set("fs.s3a.region", s3region)
    hconf.set("fs.s3a.fast.upload", "true")
    hconf.set("fs.s3a.connection.maximum", "100")

    try {
      // 1) Read partitioned Parquet dataset
      val sales = spark.read.parquet(parquetInput)

      // 2) Compute aggregates per product_name
      val agg = sales
        .filter(col("product_name").isNotNull)
        .groupBy(col("product_name"))
        .agg(
          sum(col("quantity").cast("long")).alias("total_quantity"),
          round(sum(col("amount").cast("double")), 2).alias("total_revenue")
        )
        .orderBy(desc("total_revenue"))

      // show a sample in logs
      println("Top products by revenue:")
      agg.show(20, truncate = false)

      // 3) Write as single JSON file to S3:
      //    strategy: write to temporary folder, coalesce(1), then find the part-*.json and rename to final path
      val tmpOutput = outputDir.stripSuffix("/") + "/_tmp_products_json_" + System.currentTimeMillis() + "/"
      val finalPathDir = outputDir.stripSuffix("/") + "/"
      val finalPathFile = finalPathDir + finalFileName

      // coalesce to 1 file for single JSON (be careful for large datasets)
      agg.coalesce(1)
        .write
        .mode("overwrite")
        .json(tmpOutput) // Spark writes part-00000-... .json files inside tmpOutput

      // Use Hadoop FileSystem to move/rename the created part file to desired final path
      val fs = FileSystem.get(new java.net.URI(finalPathDir), hconf)
      val tmpPath = new Path(tmpOutput)
      val files = fs.listStatus(tmpPath).map(_.getPath).filter(p => p.getName.startsWith("part-") && p.getName.endsWith(".json"))
      if (files.nonEmpty) {
        val src = files(0)
        val dst = new Path(finalPathFile)
        // delete existing final file if present
        if (fs.exists(dst)) fs.delete(dst, false)
        val moved = fs.rename(src, dst)
        if (!moved) {
          throw new RuntimeException(s"Failed to rename ${src.toString} to ${dst.toString}")
        } else {
          println(s"Wrote single JSON file to: ${dst.toString}")
        }
      } else {
        throw new RuntimeException(s"No part-*.json file found in tmp output $tmpOutput")
      }

      // cleanup temporary folder
      fs.delete(tmpPath, true)
      println("Temporary files cleaned up.")
    } catch {
      case ex: Throwable =>
        System.err.println("Job failed: " + ex.getMessage)
        ex.printStackTrace()
    } finally {
      spark.stop()
    }
  }
}

