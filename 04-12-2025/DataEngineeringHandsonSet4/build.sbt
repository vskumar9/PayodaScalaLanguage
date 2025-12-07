ThisBuild / version := "0.1.0-SNAPSHOT"

scalaVersion := "2.12.15"

val sparkVersion = "3.5.1"

lazy val root = (project in file("."))
  .settings(
    name := "DataEngineeringHandsonSet4"
  )

libraryDependencies ++= Seq(
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.2" % "test",

  // Spark core + SQL + Streaming
  "org.apache.spark" %% "spark-core"              % sparkVersion,
  "org.apache.spark" %% "spark-sql"               % sparkVersion,
  "org.apache.spark" %% "spark-streaming"         % sparkVersion,
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % sparkVersion,
  "org.apache.spark" %% "spark-sql-kafka-0-10"    % sparkVersion,
  "org.apache.spark" %% "spark-protobuf"          % sparkVersion,

  // Cassandra connector
  "com.datastax.spark" %% "spark-cassandra-connector" % "3.2.0",

  // Kafka client
  "org.apache.kafka" % "kafka-clients" % "3.7.0",

  // Other libs
  "com.github.jnr" % "jnr-posix" % "3.1.7",
  "joda-time" % "joda-time" % "2.10.10",
  "com.typesafe" % "config" % "1.4.2",

  // Hadoop + S3
  "org.apache.hadoop" % "hadoop-common" % "3.3.1",
  "org.apache.hadoop" % "hadoop-aws"    % "3.3.1",
  "com.amazonaws"     % "aws-java-sdk-bundle" % "1.11.375",

  // Protobuf Java runtime
  "com.google.protobuf" % "protobuf-java" % "3.25.3"
)
