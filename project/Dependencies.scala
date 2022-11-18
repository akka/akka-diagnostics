/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import Keys._

object Dependencies {
  val Scala211 = "2.11.12"
  val Scala212 = "2.12.15"
  val Scala213 = "2.13.8"

  val buildScalaVersion = System.getProperty("akka.build.scalaVersion", Scala212)

  val AkkaPersistenceCassandraVersion = "0.105"
  val AkkaPersistenceJdbcVersion = "3.4.0"
  val AkkaManagementVersion = "1.0.5"
  val JacksonVersion = "2.9.9"
  val JacksonDatabindVersion = "2.9.9.3"
  // play 2.6 requires 10.0.15
  val AkkaHttpVersion_10_0 = "10.0.15"
  val AkkaHttpVersion_10_1 = "10.1.11"

  val commonsLang = "org.apache.commons" % "commons-lang3" % "3.5" // ApacheV2

  val persistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion // ApacheV2
  val persistenceJdbc      = "com.github.dnvriend" %% "akka-persistence-jdbc"      % AkkaPersistenceJdbcVersion // ApacheV2

  val persistenceCassandraLauncher = "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion // ApacheV2
  val streamContrib =                "com.typesafe.akka" %% "akka-stream-contrib"                 % "0.10"                          // ApacheV2

  val jacksonCore =           "com.fasterxml.jackson.core" % "jackson-core" % JacksonVersion // ApacheV2
  val jacksonAnnotations =    "com.fasterxml.jackson.core" % "jackson-annotations" % JacksonVersion // ApacheV2
  val jacksonDatabind =       "com.fasterxml.jackson.core" % "jackson-databind" % JacksonDatabindVersion // ApacheV2
  val jacksonJdk8 =           "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % JacksonVersion // ApacheV2
  val jacksonJsr310 =         "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % JacksonVersion // ApacheV2
  val jacksonScala =          "com.fasterxml.jackson.module" %% "jackson-module-scala" % JacksonVersion // ApacheV2
  val jacksonParameterNames = "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % JacksonVersion // ApacheV2
  val jacksonAfterburner =    "com.fasterxml.jackson.module" % "jackson-module-afterburner" % JacksonVersion // ApacheV2
  val jacksonCbor =           "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % JacksonVersion // ApacheV2
  val jacksonSmile =          "com.fasterxml.jackson.dataformat" % "jackson-dataformat-smile" % JacksonVersion // ApacheV2

  lazy val akkaVersion = settingKey[String]("The version of Akka to use.")
  lazy val akkaHttpVersion = settingKey[String]("The version of Akka HTTP to use.")

  val Versions = Seq(
    akkaVersion := version213(Akka.version26, Akka.version).value,
    akkaHttpVersion := version213(AkkaHttpVersion_10_1, AkkaHttpVersion_10_0).value,
  )

  private def version213(when213: String, default: String) =
    Def.setting {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n >= 13 => when213
        case _ => default
      }
    }

  private def akkaVersion26OrHigher() =
    Def.setting {
      if(akkaVersion.value.split('.')(1).toInt >= 6) true
      else false
    }


  object Akka {
    val version = "2.5.26"
    val version26 = "2.6.4"
    val partialVersion = CrossVersion.partialVersion(version).map(_.productIterator.mkString(".")).getOrElse("current")
    val org = "com.typesafe.akka"
    val http = Def.setting { "com.typesafe.akka" %% "akka-http"   % akkaHttpVersion.value }
    val httpCore = Def.setting { "com.typesafe.akka" %% "akka-http-core"   % akkaHttpVersion.value }
    val httpSprayJson = Def.setting { "com.typesafe.akka" %% "akka-http-spray-json"               % akkaHttpVersion.value }
    val httpTestKit = Def.setting { "com.typesafe.akka" %% "akka-http-testkit"   % akkaHttpVersion.value }
    val management    = "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion
    val akkaStreamTestKit =  "com.typesafe.akka" %% "akka-stream-testkit" % version
    val serializationJackson = Def.setting{ org %% "akka-serialization-jackson" % akkaVersion.value }
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

  val akkaSplitBrainResolver = libraryDependencies ++= Seq(
    TestDeps.levelDB,
    TestDeps.commonsIo) ++ TestDeps.commonTestDeps

  val akkaGdprJackson = libraryDependencies ++= Seq(
    jacksonCore,
    jacksonDatabind,
    jacksonAnnotations % "test",
    jacksonScala % "test",
    jacksonJdk8 % "test",
    jacksonJsr310 % "test",
    jacksonParameterNames % "test",
    jacksonAfterburner % "test",
    jacksonSmile % "test",
    jacksonCbor % "test") ++ TestDeps.commonTestDeps

  val akkaPersistenceMultiDcTestkit = libraryDependencies ++= Seq(
    persistenceCassandraLauncher,
    streamContrib
  ) ++ TestDeps.commonTestDeps

  val akkaPersistenceMultiDcTests = libraryDependencies ++=
    TestDeps.commonTestDeps

  val akkaDiagnostics = libraryDependencies ++= Seq(
    commonsLang, // for levenshtein distance impl
    Akka.management % Provided,
    Akka.httpSprayJson.value % Provided,
    Akka.http.value.withRevision("10.1.10") % Test, // just needed to tie the versions down, management pulls newer version in
    Akka.httpCore.value.withRevision("10.1.10") % Test,
    Akka.httpTestKit.value.withRevision("10.1.10") % Test,
    Akka.akkaStreamTestKit % Test
  ) ++ TestDeps.commonTestDeps ++ {if(akkaVersion26OrHigher.value)Seq(Akka.serializationJackson.value % Provided) else Seq()}

  val akkaFastFailover = libraryDependencies ++= TestDeps.commonTestDeps

  val akkaCommercialCommon = libraryDependencies ++= TestDeps.commonTestDeps

  val testkit = libraryDependencies ++= Seq(
    TestDeps.scalaTest,
    TestDeps.junit,
  )
}