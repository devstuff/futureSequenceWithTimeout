import sbt._
import sbt.Keys._

name := "futureSequenceWithTimeout"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.4"
)
