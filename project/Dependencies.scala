/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
import sbt._

object Dependencies {
  // Java Platform version for JavaDoc creation
  lazy val JavaDocLinkVersion = scala.util.Properties.javaSpecVersion

  val Scala213 = "2.13.17"
  val Scala3 = "3.3.7"
  val CrossScalaVersions = Seq(Scala213, Scala3)

  val AkkaVersion = "2.10.11"
  val AkkaVersionInDocs = VersionNumber(AkkaVersion).numbers match { case Seq(major, minor, _*) => s"$major.$minor" }
  val AkkaHttpVersionInDocs = "10.7"
  val ScalaTestVersion = "3.2.18"

  val commonsText = "org.apache.commons" % "commons-text" % "1.14.0" // ApacheV2

  object Compile {
    val akkaActor = "com.typesafe.akka" %% "akka-actor" % AkkaVersion
  }

  object TestDeps {
    val akkaRemoting = "com.typesafe.akka" %% "akka-remote" % AkkaVersion
    val akkaClusterMetrics = "com.typesafe.akka" %% "akka-cluster-metrics" % AkkaVersion
    val akkaStreamTestKit = "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion
    val scalaTest = "org.scalatest" %% "scalatest" % ScalaTestVersion
    val akkaPersistenceTestKit = "com.typesafe.akka" %% "akka-persistence-testkit" % AkkaVersion
    val junit = "junit" % "junit" % "4.13.2" // Common Public License 1.0
    val all = Seq(
      akkaRemoting % Test,
      akkaClusterMetrics % Test,
      akkaStreamTestKit % Test,
      akkaPersistenceTestKit % Test,
      scalaTest % Test, // ApacheV2
      junit % Test // Common Public License 1.0
    )
  }

  import Compile._

  val akkaDiagnostics = Seq(
    commonsText, // for levenshtein distance impl
    akkaActor) ++ TestDeps.all
}
