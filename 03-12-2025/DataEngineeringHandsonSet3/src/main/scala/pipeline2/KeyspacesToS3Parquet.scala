package pipeline2

import com.typesafe.config.ConfigFactory
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object KeyspacesToS3Parquet {
  def main(args: Array[String]): Unit = {

    val config = ConfigFactory.load()

    // ---------------- Keyspaces (from config)
    val ks = config.getConfig("keyspaces")
    val cassandraHost    = ks.getString("host")
    val cassandraPort    = ks.getInt("port").toString
    val cassandraKeyspace= ks.getString("keyspace")
    val cassandraTable   = ks.getString("table")
    val accessKey        = ks.getString("username")
    val accessKeyPass    = ks.getString("password")

    // ---------------- S3 keys
    val s3accesskey = ks.getString("accesskey")
    val s3secretkey = ks.getString("secretkey")
    val s3region    = ks.getString("region")
    val s3endpoint  = ks.getString("endpoint")

    // ---------------- Truststore Options (for Keyspaces TLS)
    val sparkConf = config.getConfig("spark")
    val consistency = if (sparkConf.hasPath("consistency")) sparkConf.getString("consistency") else "LOCAL_QUORUM"
    val trustStorePath = if (sparkConf.hasPath("truststore.path")) sparkConf.getString("truststore.path") else ""
    val trustStorePassword = if (sparkConf.hasPath("truststore.password")) sparkConf.getString("truststore.password") else ""

    // S3 output path
    val outputPath = if (config.hasPath("app.parquetPath")) config.getString("app.parquetPath") else "s3a://sanjeev-scala-s3/sales/parquet/"

    // ---------------- Spark session
    val spark = SparkSession.builder()
      .appName("Keyspaces -> Parquet on S3")
      .master("local[*]")
      // Cassandra / Keyspaces config
      .config("spark.cassandra.connection.host", cassandraHost)
      .config("spark.cassandra.connection.port", cassandraPort)
      .config("spark.cassandra.connection.ssl.enabled", "true")
      .config("spark.cassandra.input.consistency.level", consistency)
      // Only set username/password if provided
      .config("spark.cassandra.auth.username", accessKey)
      .config("spark.cassandra.auth.password", accessKeyPass)
      // Truststore if provided
      .config("spark.cassandra.connection.ssl.trustStore.path", trustStorePath)
      .config("spark.cassandra.connection.ssl.trustStore.password", trustStorePassword)
      // Tell Spark/Hadoop to use S3A
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .getOrCreate()

    // ---------------- Hadoop S3A configuration
    val hconf = spark.sparkContext.hadoopConfiguration

    // Basic S3A properties
    hconf.set("fs.s3a.endpoint", s3endpoint)
    hconf.set("fs.s3a.region", s3region)
    hconf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hconf.set("fs.s3a.connection.maximum", "100")
    hconf.set("fs.s3a.path.style.access", "false")
    hconf.set("fs.s3a.fast.upload", "true")
    hconf.set("fs.s3a.access.key", s3accesskey)
    hconf.set("fs.s3a.secret.key", s3secretkey)
    hconf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider")

    try {
      // ---------------- 1. Read from Amazon Keyspaces
      val salesDF = spark.read
        .format("org.apache.spark.sql.cassandra")
        .option("keyspace", cassandraKeyspace)
        .option("table", cassandraTable)
        .load()

      // ---------------- 2. Select required columns
      val selectedDF = salesDF.select(
        col("customer_id"),
        col("order_id"),
        col("amount"),
        col("product_name"),
        col("quantity")
      )

      println("Sample from sales_data:")
      selectedDF.show(10, truncate = false)

      // quick connectivity test: check bucket root exists
      val testPath = new Path(outputPath)
      val fs = testPath.getFileSystem(hconf)
      val canWrite = try {
        // don't list contents; just check existence of parent path
        fs.exists(testPath) || fs.exists(testPath.getParent)
      } catch {
        case ex: Throwable =>
          println(s"[WARN] S3 connectivity test failed: ${ex.getMessage}")
          false
      }
      println(s"S3 connectivity test (exists parent or path): $canWrite")

      // ---------------- 3. Write as partitioned Parquet to S3 (partitionBy customer_id)
      selectedDF
        .repartition(col("customer_id"))
        .write
        .mode("overwrite")
        .partitionBy("customer_id")
        .parquet(outputPath)

      println(s"Wrote Parquet files to $outputPath partitioned by customer_id")
    } catch {
      case e: Exception =>
        System.err.println("Job failed: " + e.getMessage)
        e.printStackTrace()
    } finally {
      spark.stop()
    }
  }
}
