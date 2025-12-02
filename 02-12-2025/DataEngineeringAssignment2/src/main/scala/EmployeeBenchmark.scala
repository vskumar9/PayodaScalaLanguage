import scala.util.Random
import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.hadoop.fs.{FileSystem, Path}

object EmployeeBenchmark {
  def main(args: Array[String]): Unit = {
    // ====== Config ======
    val numEmp     = 1000000
    val partitions = 20
    val baseOut    = "employee_benchmark"

    // ====== Spark ======
    val spark = SparkSession.builder()
      .appName("EmployeeBenchmark")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      .getOrCreate()
    import spark.implicits._

    // Helpers
    def timeMs[T](block: => T): (T, Long) = {
      val t0 = System.nanoTime()
      val r  = block
      val t1 = System.nanoTime()
      (r, (t1 - t0) / 1000000L)
    }
    def rmIfExists(pathStr: String): Unit = {
      val p = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (fs.exists(p)) fs.delete(p, true)
    }
    def getDirSizeBytes(pathStr: String): Long = {
      val p = new Path(pathStr)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      if (!fs.exists(p)) 0L else fs.getContentSummary(p).getLength
    }

    println(s"Parameters: numEmp=$numEmp, partitions=$partitions, baseOut=$baseOut")

    // ====== Generate employee RDD -> DF ======
    val departments = Array("HR","IT","Sales","Finance")
    val empRDD = spark.sparkContext.parallelize(1 to numEmp, partitions)
      .mapPartitions { iter =>
        val rnd = new Random()
        iter.map { id =>
          val name = rnd.alphanumeric.take(7).mkString
          val dept = departments(rnd.nextInt(departments.length))
          val salary = 30000 + rnd.nextInt(70000) // 30k .. 99,999
          (id, name, dept, salary)
        }
      }
    val empDF = empRDD.toDF("empId", "name", "department", "salary")
    println("Sample employees:")
    empDF.show(5, truncate = false)

    // ====== Compute average salary per department ======
    println("\nComputing average salary per department...")
    val (aggRows, aggTime) = timeMs {
      val agg = empDF.groupBy("department")
        .avg("salary")
        .withColumnRenamed("avg(salary)", "avgSalary")
        .orderBy("department")
      agg.collect()
    }
    println(s"Aggregation completed in ${aggTime} ms; results:")
    aggRows.foreach(println)

    // ====== Prepare result DF and write CSV & Parquet ======
    val resultDF = empDF.groupBy("department")
      .avg("salary")
      .withColumnRenamed("avg(salary)", "avgSalary")
      .orderBy("department")

    val csvPath = s"$baseOut/avg_salary_csv"
    val parquetPath = s"$baseOut/avg_salary_parquet"
    rmIfExists(csvPath); rmIfExists(parquetPath)

    println("\nWriting aggregated result to CSV (header=true)...")
    val (_, csvTime) = timeMs {
      resultDF.write.mode(SaveMode.Overwrite).option("header", "true").csv(csvPath)
    }
    val csvSize = getDirSizeBytes(csvPath)
    println(s"CSV write time = ${csvTime} ms; size = ${csvSize} bytes; path=$csvPath")

    println("\nWriting aggregated result to Parquet...")
    val (_, parquetTime) = timeMs {
      resultDF.write.mode(SaveMode.Overwrite).parquet(parquetPath)
    }
    val parquetSize = getDirSizeBytes(parquetPath)
    println(s"Parquet write time = ${parquetTime} ms; size = ${parquetSize} bytes; path=$parquetPath")

    // ====== Read CSV back WITHOUT inferSchema (default) ======
    println("\nReading CSV back WITHOUT inferSchema (default) ...")
    val (csvNoInferDF, csvNoInferTime) = timeMs {
      val df = spark.read
        .option("header", "true")
        // .option("inferSchema", "false") // default is false
        .csv(csvPath)
      df.cache()
      df.count() // materialize
      df
    }
    println(s"Read CSV (no infer) in ${csvNoInferTime} ms. Schema:")
    csvNoInferDF.printSchema()
    csvNoInferDF.show(5, truncate = false)

    // ====== Read CSV back WITH inferSchema = true ======
    println("\nReading CSV back WITH inferSchema = true ...")
    val (csvInferDF, csvInferTime) = timeMs {
      val df = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .csv(csvPath)
      df.cache()
      df.count()
      df
    }
    println(s"Read CSV (inferSchema=true) in ${csvInferTime} ms. Schema:")
    csvInferDF.printSchema()
    csvInferDF.show(5, truncate = false)

    // ====== Read Parquet back and show schema (typed) ======
    println("\nReading Parquet back (types preserved) ...")
    val (pDF, pTime) = timeMs {
      val df = spark.read.parquet(parquetPath)
      df.cache()
      df.count()
      df
    }
    println(s"Read Parquet in ${pTime} ms. Schema:")
    pDF.printSchema()
    pDF.show(5, truncate = false)

    // ====== Summary ======
    println("\n================ SUMMARY ================")
    println(f"Aggregation time         : ${aggTime}%d ms")
    println(f"CSV write time           : ${csvTime}%d ms; size = ${csvSize}%d bytes")
    println(f"Parquet write time       : ${parquetTime}%d ms; size = ${parquetSize}%d bytes")
    println(f"CSV read (no infer) time : ${csvNoInferTime}%d ms")
    println(f"CSV read (inferSchema)   : ${csvInferTime}%d ms")
    println(f"Parquet read time        : ${pTime}%d ms")
    println("==========================================")

    /*
     ============================================================
                     OBSERVATIONS & ANSWERS
     ============================================================

      CONTEXT (What this program does)
      --------------------------------
      • Generates 1 million random employees: (empId, name, department, salary).
      • Computes average salary per department using DataFrame groupBy.
      • Writes the aggregated output both as:
            (a) CSV   — human-readable text format
            (b) Parquet — compressed, columnar format
      • Reads the CSV twice:
            – WITHOUT inferSchema (default: everything becomes STRING)
            – WITH inferSchema=true (Spark tries to detect column types)
      • Reads Parquet once (typed read, no inference required)
      • Compares timings and schema differences.

      ------------------------------------------------------------
      WHY CSV READ (NO inferSchema) TREATS ALL COLUMNS AS STRING?
      ------------------------------------------------------------
      • CSV files contain no metadata about column types.
      • Spark’s default CSV reader does NOT infer types automatically.
      • Therefore:
            department -> String
            avgSalary  -> String
            (even though avgSalary is numeric in the DataFrame before writing)
      • This is why you see StringType unless inferSchema is enabled.

      ------------------------------------------------------------
      WHAT HAPPENS WITH inferSchema=true?
      ------------------------------------------------------------
      • Spark samples the CSV data to guess correct data types.
      • It correctly identifies avgSalary as DoubleType.
      • But:
          - Type inference requires scanning extra data = slower.
          - Schema inference can be wrong in edge cases.
          - Not recommended for production pipelines.

      ------------------------------------------------------------
      WHY PARQUET READ IS FAST AND TYPED?
      ------------------------------------------------------------
      • Parquet stores schema in the file footer (metadata).
      • When you read Parquet:
            – Spark already knows column names AND data types.
            – No need for schema inference.
      • As a result:
            – Fast reads
            – Low storage size
            – Works best for analytics workloads

      ------------------------------------------------------------
      • CSV write:
            – Simple text output
            – Larger file size
            – Slow to read when inferSchema=true
            – No column types preserved
      • Parquet write:
            – Slightly slower write (compression + encoding)
            – Much smaller file size
            – Much faster read
            – Schema is fully preserved

      ------------------------------------------------------------
      • Use CSV only when human readability or interoperability is needed.
      • For analytical workloads, ALWAYS prefer Parquet:
            – Smaller size
            – Faster read
            – Schema preserved
            – Enables predicate pushdown & column pruning
      • Avoid inferSchema=true on large CSV files:
            – Use explicit .schema()
            – OR use typed formats like Parquet/Avro instead

      ------------------------------------------------------------
      CSV = readable but slow, untyped, large
      Parquet = compressed, fast, typed, analytical format

     ============================================================
    */

    // ---------- 5 MINUTE SLEEP ADDED HERE ----------
    println("\nSleeping for 5 minutes before stopping Spark...")
    Thread.sleep(5 * 60 * 1000)  // 5 minutes (300,000 ms)

    spark.stop()
  }
}
