import akka._
import akka.AkkaDependency.RichProject
import Dependencies.{ Scala212, Scala213, buildScalaVersion }


lazy val root = (project in file("."))
  .settings(
    name := "akka-diagnostics-root"
  )
  .settings(dontPublish)
  .aggregate(`akka-diagnostics`, docs)

lazy val `akka-diagnostics` = akkaAddonsModule("akka-diagnostics")
  .settings(Dependencies.akkaDiagnostics)
  .dependsOn(addonsTestkit % "test")

// Internal testkit
lazy val addonsTestkit = akkaAddonsModule("addons-testkit")
  .settings(
    Dependencies.testkit
  )
  .addAkkaModuleDependency("akka-testkit")

lazy val docs = akkaAddonsModule("docs")
  .enablePlugins(AkkaParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, PublishRsyncPlugin)
  .settings(dontPublish)
  .settings(
      name := "Akka Diagnostics",
//      makeSite := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,  //FIXME to be added
      Preprocess / siteSubdirName := s"api/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
      Preprocess / sourceDirectory := (LocalRootProject / ScalaUnidoc / unidoc / target).value,
      previewPath := (Paradox / siteSubdirName).value,
      paradoxGroups := Map("Languages" -> Seq("Java", "Scala")),
      Paradox / siteSubdirName := s"docs/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
      Compile / paradoxProperties ++= Map(
          "version" -> version.value,
          "project.url" -> "https://doc.akka.io/docs/akka-diagnostics/current/",
          "canonical.base_url" -> "https://doc.akka.io/docs/akka-diagnostics/current",
          "akka.version27" -> Dependencies.AkkaVersion,
          "scala.binaryVersion" -> scalaBinaryVersion.value,
          "extref.scaladoc.base_url" -> s"/${(Preprocess / siteSubdirName).value}/",
          "extref.javadoc.base_url" -> s"/japi/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
          "scaladoc.akka.persistence.gdpr.base_url" -> s"/api/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
          "extref.akka.base_url" -> s"https://doc.akka.io/docs/akka/${Dependencies.AkkaVersionInDocs}/%s",
          "scaladoc.akka.base_url" -> s"https://doc.akka.io/api/akka/${Dependencies.AkkaVersionInDocs}",
          "extref.akka-http.base_url" -> s"https://doc.akka.io/docs/akka-http/${Dependencies.AkkaHttpVersion}/%s",
          "scaladoc.akka.http.base_url" -> s"https://doc.akka.io/api/akka-http/${Dependencies.AkkaHttpVersion}/",
          "snip.github_link" -> "false"
      ),
      ApidocPlugin.autoImport.apidocRootPackage := "akka",
      apidocRootPackage := "akka",
      resolvers += Resolver.jcenterRepo, // required to resolve paradox-theme-akka
      publishRsyncArtifacts += makeSite.value -> "www/",
      publishRsyncHost := "akkarepo@gustav.akka.io"
  )

def akkaAddonsModule(name: String): Project =
    Project(id = name.replace("/", "-"), base = file(name))
      .settings(defaultSettings)

lazy val dontPublish = Seq(publish / skip := true, Compile / publishArtifact := false)


// settings
lazy val silencerVersion = "1.7.8"
lazy val defaultSettings: Seq[Setting[_]] =
  Seq(
    crossScalaVersions := Seq(Dependencies.Scala213, Dependencies.Scala212),
    scalaVersion := Dependencies.Scala213,
    crossVersion := CrossVersion.binary,
    scalafmtOnCompile := true,
    //sonatypeProfileName := "com.lightbend",
    // Setting javac options in common allows IntelliJ IDEA to import them automatically
    Compile / javacOptions ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8"),
    //headerLicense := Some(HeaderLicense.Custom("""Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>""")),
    Test / logBuffered := false,
    Test / parallelExecution := false,
    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument("-oDF"),
    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -a Show stack traces and exception class name for AssertionErrors.
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    Test / fork := true, // some non-heap memory is leaking
    Test / javaOptions ++= {
      import scala.collection.JavaConverters._
      // include all passed -Dakka. properties to the javaOptions for forked tests
      // useful to switch DB dialects for example
      val akkaProperties = System.getProperties.stringPropertyNames.asScala.toList.collect {
        case key: String if key.startsWith("akka.") => "-D" + key + "=" + System.getProperty(key)
      }
      "-Xms1G" :: "-Xmx1G" :: "-XX:MaxDirectMemorySize=256M" :: akkaProperties
    },
    projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
    Global / excludeLintKeys += projectInfoVersion)