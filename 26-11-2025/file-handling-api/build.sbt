name := """file-handling-api"""
organization := "file-handling-api"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.17"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test
libraryDependencies ++= Seq(
  "org.playframework" %% "play-slick"            % "6.1.0",
  "org.playframework" %% "play-slick-evolutions" % "6.1.0",
  "com.typesafe.play" %% "play-json" % "2.9.4",
  "mysql" % "mysql-connector-java" % "8.0.26"
)

libraryDependencies += filters