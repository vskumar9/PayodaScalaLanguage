name := """eventManagement"""
organization := "eventManagement"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.17"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test
libraryDependencies ++= Seq(
  "org.playframework" %% "play-slick"            % "6.1.0",
  "org.playframework" %% "play-slick-evolutions" % "6.1.0",
  "mysql" % "mysql-connector-java" % "8.0.26"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.6.20",
  "com.typesafe.akka" %% "akka-actor" % "2.6.20",
  "com.typesafe.akka" %% "akka-slf4j" % "2.6.20",
  "org.apache.pekko" %% "pekko-stream" % "1.0.1",
  "com.auth0" % "java-jwt" % "4.3.0",
  "com.typesafe.play" %% "play-json" % "2.9.4",
  "org.mindrot" % "jbcrypt" % "0.4",
  "com.github.jwt-scala" %% "jwt-core" % "9.4.5",
  "org.apache.kafka" % "kafka-clients" % "3.5.0",
)


libraryDependencies += filters

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "eventManagement.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "eventManagement.binders._"
