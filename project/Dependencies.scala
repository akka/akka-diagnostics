/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import Keys._

object Dependencies {
  val Scala212 = "2.12.17"
  val Scala213 = "2.13.10"
  val AkkaVersion = "2.7.0"
  val AkkaVersionInDocs = AkkaVersion.take(3)
  val buildScalaVersion = System.getProperty("akka.build.scalaVersion", Scala213)
  val AkkaManagementVersion = "1.2.0"
  val AkkaHttpVersion = "10.4.0"
  val commonsLang = "org.apache.commons" % "commons-lang3" % "3.5" // ApacheV2
  val streamContrib = "com.typesafe.akka" %% "akka-stream-contrib" % "0.10"// ApacheV2

  object Akka {
    val org = "com.typesafe.akka"
    val http = Def.setting { "com.typesafe.akka" %% "akka-http"   % AkkaHttpVersion }
    val httpCore = Def.setting { "com.typesafe.akka" %% "akka-http-core"   % AkkaHttpVersion }
    val httpSprayJson = Def.setting { "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion }
    val httpTestKit = Def.setting { "com.typesafe.akka" %% "akka-http-testkit"   % AkkaHttpVersion }
    val management    = "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion
    val akkaStreamTestKit =  "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion
    val serializationJackson = Def.setting{ org %% "akka-serialization-jackson" % AkkaVersion }
  }



  object TestDeps {
    val scalaTest = "org.scalatest"     %% "scalatest"     % "3.0.8"
    val junit      = "junit"                      %  "junit"          % "4.12" // Common Public License 1.0

    val commonsIo  = "commons-io"                 %  "commons-io"     % "2.4"     % "test" // ApacheV2
    val levelDB    = "org.iq80.leveldb"           %  "leveldb"        % "0.7"     % "test" // ApacheV2
    val levelDBAll = "org.fusesource.leveldbjni"  %  "leveldbjni-all" % "1.8"     % "test" // ApacheV2
    val h2         = "com.h2database"             %  "h2"             % "1.4.197" % "test" // MPL 2.0 / EPL 1.0
    val scalaTestIt = scalaTest  % "it,test" // ApacheV2
    val wiremock = "com.github.tomakehurst" % "wiremock-jre8" % "2.21.0" % "test" // ApacheV2

    val commonTestDeps = Seq(
      scalaTest  % "test", // ApacheV2
      junit      % "test"  // Common Public License 1.0
    )
  }

  // == dependencies for individual modules ==
  // Akka dependencies are added on each project using `addAkkaModuleDependency` to depend on akka sources more easily


  val akkaDiagnostics = libraryDependencies ++= Seq(
    commonsLang, // for levenshtein distance impl
    Akka.management % Provided,
    Akka.httpSprayJson.value % Provided,
    Akka.http.value % Test, // just needed to tie the versions down, management pulls newer version in
    Akka.httpCore.value % Test,
    Akka.httpTestKit.value % Test,
    Akka.akkaStreamTestKit % Test
  ) ++ TestDeps.commonTestDeps ++ Seq(Akka.serializationJackson.value % Provided)

  val testkit = libraryDependencies ++= Seq(
    TestDeps.scalaTest,
    TestDeps.junit,
  )
}