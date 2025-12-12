ThisBuild / version := "0.1.0-SNAPSHOT"

scalaVersion := "2.12.15"  // Spark 3.5.1 compatible Scala version

val sparkVersion = "3.5.1"  // Primary Spark version for Structured Streaming

lazy val root = (project in file("."))
  .settings(
    name := "PipelineC"  // Kafka Avro Events → S3 Lake Pipeline
  )

libraryDependencies ++= Seq(
  // ===== TESTING =====
  "org.scalatest" %% "scalatest" % "3.2.2" % "test",  // Unit/integration tests

  // ===== SPARK CORE + STREAMING (mark as "provided" for spark-submit) =====
  "org.apache.spark" %% "spark-core"              % sparkVersion,  // Core Spark APIs
  "org.apache.spark" %% "spark-sql"               % sparkVersion,  // DataFrame/Dataset APIs
  "org.apache.spark" %% "spark-streaming"         % sparkVersion,  // Structured Streaming base
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % sparkVersion,  // Kafka source integration
  "org.apache.spark" %% "spark-sql-kafka-0-10"    % sparkVersion,  // Kafka Structured Streaming source
  "org.apache.spark" %% "spark-avro"             % sparkVersion,  // from_avro() SQL function

  // ===== DATA STORAGE =====
  "com.datastax.spark" %% "spark-cassandra-connector" % "3.5.1",  // Cassandra integration (Keyspaces)
  "mysql" % "mysql-connector-java" % "8.0.33",  // MySQL JDBC driver

  // ===== MESSAGE QUEUE =====
  "org.apache.kafka" % "kafka-clients" % "3.7.0",  // Kafka producer/consumer (GenericRecord handling)

  // ===== AVRO SERIALIZATION =====
  "org.apache.avro" % "avro" % "1.11.2",  // GenericDatumReader, Schema parsing for MalformedHandler

  // ===== CONFIGURATION & UTILITIES =====
  "com.typesafe" % "config" % "1.4.2",  // Typesafe Config (application.conf)
  "joda-time" % "joda-time" % "2.10.10",  // Legacy date/time handling

  // ===== S3 STORAGE (Hadoop AWS integration) =====
  "org.apache.hadoop" % "hadoop-common" % "3.3.1",  // Hadoop filesystem base
  "org.apache.hadoop" % "hadoop-aws"    % "3.3.1",  // S3AFileSystem implementation
  "com.amazonaws"     % "aws-java-sdk-bundle" % "1.11.375",  // AWS credentials/S3 client

  // ===== PROTOBUF SUPPORT (schema registry?) =====
  "com.google.protobuf" % "protobuf-java" % "3.25.3",  // Protobuf serialization
  "com.github.jnr" % "jnr-posix" % "3.1.7",  // Native POSIX for Protobuf

  // ===== LOGGING STACK =====
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",  // SLF4J Scala extensions
  "org.slf4j" % "slf4j-api" % "2.0.9",  // SLF4J logging facade
  "ch.qos.logback" % "logback-classic" % "1.4.11"  // Logback implementation
)
