import scala.collection.Seq

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.13"

lazy val root = (project in file("."))
  .settings(
    name := "equipmentAllocationConsumer"
  )

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.6.20",
  "com.typesafe.akka" %% "akka-stream" % "2.6.20",
  "com.typesafe.akka" %% "akka-stream-kafka" % "4.0.2",
  "com.typesafe.akka" %% "akka-http" % "10.2.10",
  "org.apache.kafka" %% "kafka" % "3.7.0",
  "org.apache.kafka"  %  "kafka-clients" % "3.5.0",
  "com.typesafe.play" %% "play-json" % "2.9.4",
  "org.slf4j" % "jul-to-slf4j" % "2.0.9",
  "com.typesafe.play" %% "play-json" % "2.9.4",
  "org.playframework" %% "play-slick"            % "6.1.0",
  "org.playframework" %% "play-slick-evolutions" % "6.1.0",
  "mysql" % "mysql-connector-java" % "8.0.26",
  "com.sun.mail" % "jakarta.mail" % "2.0.2"
)

fork := true