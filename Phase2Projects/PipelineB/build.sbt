ThisBuild / version := "0.1.0-SNAPSHOT"

scalaVersion := "2.12.15"

val sparkVersion = "3.5.1"

lazy val root = (project in file("."))
  .settings(
    name := "PipelineB"  // Main pipeline project name
  )

libraryDependencies ++= Seq(
  // ===== Testing =====
  "org.scalatest" %% "scalatest" % "3.2.2" % "test",  // Unit and integration testing framework

  // ===== Spark Core + SQL + Streaming =====
  "org.apache.spark" %% "spark-core"              % sparkVersion,  // Core Spark engine and RDD APIs
  "org.apache.spark" %% "spark-sql"               % sparkVersion,  // DataFrame/Dataset API and Catalyst optimizer
  "org.apache.spark" %% "spark-streaming"         % sparkVersion,  // Core Structured Streaming engine
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % sparkVersion,  // Kafka streaming source (legacy)
  "org.apache.spark" %% "spark-sql-kafka-0-10"    % sparkVersion,  // Kafka Structured Streaming source/sink
  "org.apache.spark" %% "spark-protobuf"          % sparkVersion,  // Protobuf serialization support

  // ===== Data Storage Connectors =====
  "com.datastax.spark" %% "spark-cassandra-connector" % "3.5.1",  // Cassandra read/write connector

  // ===== Message Queuing =====
  "org.apache.kafka" % "kafka-clients" % "3.7.0",  // Native Kafka producer/consumer client

  // ===== Utilities =====
  "com.github.jnr" % "jnr-posix" % "3.1.7",     // POSIX file system operations (S3 compatibility)
  "joda-time" % "joda-time" % "2.10.10",       // Date/time handling (Spark SQL compatible)
  "com.typesafe" % "config" % "1.4.2",         // Typesafe Config for application configuration

  // ===== Hadoop + S3 File System =====
  "org.apache.hadoop" % "hadoop-common" % "3.3.1",  // Hadoop core libraries
  "org.apache.hadoop" % "hadoop-aws"    % "3.3.1",  // S3A filesystem implementation
  "com.amazonaws"     % "aws-java-sdk-bundle" % "1.11.375",  // AWS SDK for S3 authentication

  // ===== Databases =====
  "mysql" % "mysql-connector-java" % "8.0.33",  // MySQL JDBC driver for Spark JDBC reads
)
