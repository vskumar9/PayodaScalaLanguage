ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.18"

lazy val root = (project in file("."))
  .settings(
    name := "UrbanMobilityDataEngineeringChallenge",

    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % "3.5.1",
      "org.apache.spark" %% "spark-sql" % "3.5.1",
//      "org.apache.hadoop" % "hadoop-client" % "3.3.4",
      "mysql" % "mysql-connector-java" % "8.0.19",
      "com.typesafe" % "config" % "1.4.2"
    )
  )
