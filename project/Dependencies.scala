/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
import sbt._

object Dependencies {
  val Scala212 = "2.12.17"
  val Scala213 = "2.13.10"
  val AkkaVersion = "2.7.0"
  val AkkaVersionInDocs = AkkaVersion.take(3)
  val buildScalaVersion = System.getProperty("akka.build.scalaVersion", Scala213)
  val AkkaManagementVersion = "1.2.0"
  val AkkaHttpVersion = "10.4.0"
  val commonsLang = "org.apache.commons" % "commons-lang3" % "3.5" // ApacheV2
  val streamContrib = "com.typesafe.akka" %% "akka-stream-contrib" % "0.10" // ApacheV2

  object Compile {
    val org = "com.typesafe.akka"
    val http = "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion
    val httpCore = "com.typesafe.akka" %% "akka-http-core" % AkkaHttpVersion
    val httpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
    val httpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % AkkaHttpVersion
    val management = "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion
    val akkaActorTestKit = "com.typesafe.akka" %% "akka-actor" % AkkaVersion % Test
    val akkaStreamTestKit = "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion
    val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % AkkaVersion
    val serializationJackson = org %% "akka-serialization-jackson" % AkkaVersion
  }

  object TestDeps {
    val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8"
    val junit = "junit" % "junit" % "4.12" // Common Public License 1.0
    val commonTestDeps = Seq(
      scalaTest % Test, // ApacheV2
      junit % Test // Common Public License 1.0
    )
  }

  // == dependencies for individual modules ==
  // Akka dependencies are added on each project using `addAkkaModuleDependency` to depend on akka sources more easily
  import Compile._

  val akkaDiagnostics = Seq(
    commonsLang, // for levenshtein distance impl
    management % Provided,
    httpSprayJson % Provided,
    http % Test, // just needed to tie the versions down, management pulls newer version in
    httpCore % Test,
    httpTestKit % Test,
    akkaStreamTestKit % Test) ++ TestDeps.commonTestDeps ++ Seq(serializationJackson % Provided)
}
