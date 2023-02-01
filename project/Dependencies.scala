/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
import sbt._

object Dependencies {
  val Scala212 = "2.12.17"
  val Scala213 = "2.13.10"
  val AkkaVersion = "2.7.0"
  val AkkaVersionInDocs = AkkaVersion.take(3)
  val AkkaHttpVersionInDocs = "10.4.0"
  val buildScalaVersion = System.getProperty("akka.build.scalaVersion", Scala213)
  val commonsLang = "org.apache.commons" % "commons-lang3" % "3.12.0" // ApacheV2

  object Compile {
    val org = "com.typesafe.akka"
    val akkaRemoting = "com.typesafe.akka" %% "akka-remote" % AkkaVersion
    val akkaClusterMetrics = "com.typesafe.akka" %% "akka-cluster-metrics" % AkkaVersion
    val akkaStreamTestKit = "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion
  }

  object TestDeps {
    val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8"
    val junit = "junit" % "junit" % "4.13.2" // Common Public License 1.0
    val all = Seq(
      scalaTest % Test, // ApacheV2
      junit % Test // Common Public License 1.0
    )
  }

  import Compile._

  val akkaDiagnostics = Seq(
    commonsLang, // for levenshtein distance impl
    akkaRemoting % Provided,
    akkaClusterMetrics % Test,
    akkaStreamTestKit % Test) ++ TestDeps.all
}
