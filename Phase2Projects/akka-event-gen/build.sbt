ThisBuild / version := "0.1.0-SNAPSHOT"

scalaVersion := "2.12.10"

lazy val root = (project in file("."))
  .settings(
    name := "akka-event-gen",
    // optional: increase forked JVM memory if you run heavy Spark locally
    // javaOptions += "-Xmx2g"
  )

val sparkVersion = "3.2.1"

libraryDependencies ++= Seq(
  // ---------------- Spark / streaming / kafka connectors ----------------
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-streaming" % sparkVersion,
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % sparkVersion,
  "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion,
  "org.apache.spark" %% "spark-avro" % sparkVersion,

  "com.typesafe" % "config" % "1.4.2",

  // ---------------- Hadoop + S3 ----------------
  "org.apache.hadoop" % "hadoop-common" % "3.3.1",
  "org.apache.hadoop" % "hadoop-aws" % "3.3.1",
  "com.amazonaws" % "aws-java-sdk-bundle" % "1.11.375",

  // ---------------- Utility / time ----------------
  "joda-time" % "joda-time" % "2.10.10",

  // ---------------- Kafka client (producer) ----------------
  // prefer kafka-clients (lightweight) instead of the full "kafka" server artifact
  "org.apache.kafka" % "kafka-clients" % "3.7.0",

  // ---------------- Akka (actors) ----------------
  "com.typesafe.akka" %% "akka-actor" % "2.6.20",

  // ---------------- Avro / avro4s ----------------
  "org.apache.avro" % "avro" % "1.11.2",
  "com.sksamuel.avro4s" %% "avro4s-core" % "4.1.0",

  // ---------------- jnr-posix (native clock support for DataStax driver) ----------------
  // keep this (you had it) — helps the DataStax driver to optionally use native impls
  "com.github.jnr" % "jnr-posix" % "3.1.7",

  // ---------------- Testing & logging ----------------
  "org.scalatest" %% "scalatest" % "3.2.2" % Test,
  "ch.qos.logback" % "logback-classic" % "1.4.11"
)
