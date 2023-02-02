/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
import sbt._

object Dependencies {
  val Scala212 = "2.12.17"
  val Scala213 = "2.13.10"
  val Scala3 = "3.1.3"
  val CrossScalaVersions = Seq(Scala213, Scala212, Scala3)

  val AkkaVersion = "2.7.0"
  val AkkaVersionInDocs = AkkaVersion.take(3)
  val AkkaHttpVersionInDocs = "10.4.0"
  val ScalaTestVersion = "3.2.15"

  val commonsText = "org.apache.commons" % "commons-text" % "1.10.0" // ApacheV2

  object Compile {
    val akkaActor = "com.typesafe.akka" %% "akka-actor" % AkkaVersion
  }

  object TestDeps {
    val akkaRemoting = "com.typesafe.akka" %% "akka-remote" % AkkaVersion
    val akkaClusterMetrics = "com.typesafe.akka" %% "akka-cluster-metrics" % AkkaVersion
    val akkaStreamTestKit = "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion
    val scalaTest = "org.scalatest" %% "scalatest" % ScalaTestVersion
    val junit = "junit" % "junit" % "4.13.2" // Common Public License 1.0
    val all = Seq(
      akkaRemoting % Test,
      akkaClusterMetrics % Test,
      akkaStreamTestKit % Test,
      scalaTest % Test, // ApacheV2
      junit % Test // Common Public License 1.0
    )
  }

  import Compile._

  val akkaDiagnostics = Seq(
    commonsText, // for levenshtein distance impl
    akkaActor) ++ TestDeps.all
}
