name := """dashboard-api"""
organization := "dashboard-api"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.18"

libraryDependencies += guice

libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test

/**
 * Core Dependencies
 */
libraryDependencies ++= Seq(
  // DI Framework
  guice,

  // ==============================================
  // Caching Layers (L1 + L2)
  // ==============================================
  "com.github.ben-manes.caffeine" % "caffeine" % "3.1.8",         // L1: In-memory JVM-local cache

  "net.debasishg" %% "redisclient" % "3.42",                      // L2: Distributed Redis cache

  // ==============================================
  // Data Sources
  // ==============================================
  "org.apache.cassandra" % "java-driver-core" % "4.18.0",         // Amazon Keyspaces (Cassandra) client

  "software.aws.mcs" % "aws-sigv4-auth-cassandra-java-driver-plugin" % "4.0.9",  // IAM/SigV4 auth for Keyspaces

  "software.amazon.awssdk" % "s3" % "2.20.25",                    // AWS S3 AsyncClient (lakehouse Parquet)

  // ==============================================
  // Parquet Processing (S3 Lakehouse)
  // ==============================================
  "com.github.mjakubowski84" %% "parquet4s-core" % "2.22.0",      // Parquet read/write for Scala case classes

  "org.apache.hadoop"        %  "hadoop-client" % "3.3.6",         // Hadoop Parquet support + filesystem
  "org.apache.hadoop"        %  "hadoop-aws"    % "3.3.6",         // S3A filesystem connector

// ==============================================
// JSON Processing
// ==============================================
"com.typesafe.play" %% "play-json" % "2.9.4"                    // Play JSON serialization
)

// ==============================================
// Twirl Template Configuration (disabled)
// ==============================================
//TwirlKeys.templateImports += "dashboard-api.controllers._"

// ==============================================
// Routes Configuration (disabled)
// ==============================================
// play.sbt.routes.RoutesKeys.routesImport += "dashboard-api.binders._"
