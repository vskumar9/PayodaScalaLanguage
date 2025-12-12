ThisBuild / version := "0.1.0-SNAPSHOT"

scalaVersion := "2.12.15" // Spark 3.5.1 compatible Scala version

val sparkVersion = "3.5.1" // Primary Spark version for all Spark dependencies

lazy val root = (project in file("."))
  .settings(
    name := "PipelineA" // Incremental MySQL → Cassandra profile consolidation pipeline
  )

libraryDependencies ++= Seq(
  // === TESTING ===
  "org.scalatest" %% "scalatest" % "3.2.2" % "test", // Unit/integration tests

  // === SPARK CORE + PROCESSING ===
  "org.apache.spark" %% "spark-core"              % sparkVersion, // Core Spark engine
  "org.apache.spark" %% "spark-sql"               % sparkVersion, // DataFrames, JDBC, Catalyst optimizer
  "org.apache.spark" %% "spark-streaming"         % sparkVersion, // Micro-batch streaming (rate source trigger)
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % sparkVersion, // Kafka integration (future use)
  "org.apache.spark" %% "spark-sql-kafka-0-10"    % sparkVersion, // Structured Streaming Kafka source/sink

  // === CASSANDRA KEYSPACES ===
  "com.datastax.spark" %% "spark-cassandra-connector" % "3.5.1", // Spark-Cassandra integration (matches Spark 3.5.1)

  // === MESSAGE QUEUES ===
  "org.apache.kafka" % "kafka-clients" % "3.7.0", // Native Kafka producer/consumer client

  // === UTILITIES ===
  "com.github.jnr" % "jnr-posix" % "3.1.7",     // POSIX file/system calls (Spark connector dependency)
  "joda-time" % "joda-time" % "2.10.10",       // Legacy date/time handling
  "com.typesafe" % "config" % "1.4.2",         // Typesafe Config (HOCON) for pipeline configuration

  // === HADOOP + CLOUD STORAGE ===
  "org.apache.hadoop" % "hadoop-common" % "3.3.1", // Hadoop core (Spark dependency)
  "org.apache.hadoop" % "hadoop-aws"    % "3.3.1", // S3 file system support
  "com.amazonaws"     % "aws-java-sdk-bundle" % "1.11.375", // AWS SDK (S3 access, credentials)

  // === DATABASES ===
  "mysql" % "mysql-connector-java" % "8.0.33", // MySQL 8 JDBC driver (JDBC partitioning, CDC offsets)
)

// === NOTES ===
// - All Spark deps use unified sparkVersion to avoid version conflicts
// - Cassandra connector 3.5.1 matches Spark 3.5.1 for binary compatibility
// - MySQL connector 8.0.33 supports cursor fetch + partitioning features
// - Kafka deps prepared for future streaming expansions
// - AWS/S3 support for potential checkpointing or intermediate storage
