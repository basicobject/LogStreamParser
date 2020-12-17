name := "JsonLogParser"

version := "0.1"

scalaVersion := "2.13.4"
val AkkaVersion = "2.5.31"
val circeVersion = "0.12.3"

libraryDependencies ++= Seq(
  "com.lightbend.akka" %% "akka-stream-alpakka-file" % "2.0.2",
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.apache.datasketches" % "datasketches-java" % "1.3.0-incubating"
)
