
GlobalScope / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

inThisBuild(
  Seq(
    organization := "com.lightbend.akka",
    organizationName := "Lightbend Inc.",
    homepage := Some(url("https://doc.akka.io/docs/akka-diagnostics/current")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/akka/akka-diagnostics"),
        "https://github.com/akka/akka-diagnostics.git")),
    startYear := Some(2022),
    developers += Developer(
      "contributors",
      "Contributors",
      "https://gitter.im/akka/dev",
      url("https://github.com/akka/akka-diagnostics/graphs/contributors")),
    licenses := Seq(
      ("BUSL-1.1", url("https://raw.githubusercontent.com/akka/akka-diagnostics/main/LICENSE"))
    ), // FIXME change s/main/v1.1.0/ before releasing 1.1.0
    description := "Akka diagnostics tools and utilities",
    // add snapshot repo when Akka version overriden
    resolvers ++=
      (if (System.getProperty("override.akka.version") != null)
        Seq("Akka Snapshots".at("https://oss.sonatype.org/content/repositories/snapshots/"))
      else Seq.empty)))

lazy val root = (project in file("."))
  .settings(
    name := "akka-diagnostics-root",
    publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo"))))
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(SitePlugin, MimaPlugin)
  .settings(dontPublish)
  .aggregate(`akka-diagnostics`, docs)

lazy val `akka-diagnostics` = (project in file("akka-diagnostics"))
  .settings(common)
  .settings(libraryDependencies ++= Dependencies.akkaDiagnostics)


lazy val docs = (project in file("docs"))
  .enablePlugins(AkkaParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, PublishRsyncPlugin)
  .settings(common)
  .settings(dontPublish)
  .settings(
    name := "Akka Diagnostics",
//      makeSite := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,  //FIXME to  added
    Preprocess / siteSubdirName := s"api/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
    Preprocess / sourceDirectory := (LocalRootProject / ScalaUnidoc / unidoc / target).value,
    previewPath := (Paradox / siteSubdirName).value,
    paradoxGroups := Map("Languages" -> Seq("Java", "Scala")),
    Paradox / siteSubdirName := s"docs/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
    Compile / paradoxProperties ++= Map(
      "version" -> version.value,
      "project.url" -> "https://doc.akka.io/docs/akka-diagnostics/current/",
      "canonical.base_url" -> "https://doc.akka.io/docs/akka-diagnostics/current",
      "akka.version" -> Dependencies.AkkaVersion,
      "scala.version" -> scalaVersion.value,
      "scala.binaryVersion" -> scalaBinaryVersion.value,
      "extref.scaladoc.base_url" -> s"/${(Preprocess / siteSubdirName).value}/",
      "extref.javadoc.base_url" -> s"/japi/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
      "scaladoc.akka.persistence.gdpr.base_url" -> s"/api/akka-diagnostics/${if (isSnapshot.value) "snapshot"
      else version.value}",
      "extref.akka.base_url" -> s"https://doc.akka.io/docs/akka/${Dependencies.AkkaVersionInDocs}/%s",
      "scaladoc.akka.base_url" -> s"https://doc.akka.io/api/akka/${Dependencies.AkkaVersionInDocs}",
      "extref.akka-http.base_url" -> s"https://doc.akka.io/docs/akka-http/${Dependencies.AkkaHttpVersion}/%s",
      "scaladoc.akka.http.base_url" -> s"https://doc.akka.io/api/akka-http/${Dependencies.AkkaHttpVersion}/",
      "snip.github_link" -> "false"),
    ApidocPlugin.autoImport.apidocRootPackage := "akka",
    apidocRootPackage := "akka",
    resolvers += Resolver.jcenterRepo, // required to resolve paradox-theme-akka
    publishRsyncArtifacts += makeSite.value -> "www/",
    publishRsyncHost := "akkarepo@gustav.akka.io")

lazy val dontPublish = Seq(publish / skip := true, Compile / publishArtifact := false)

// settings
lazy val common: Seq[Setting[_]] =
  Seq(
    crossScalaVersions := Seq(Dependencies.Scala213, Dependencies.Scala212),
    scalaVersion := Dependencies.Scala213,
    crossVersion := CrossVersion.binary,
    scalafmtOnCompile := true,
    //sonatypeProfileName := "com.lightbend", //FIXME to  added
    // Setting javac options in common allows IntelliJ IDEA to import them automatically
    Compile / javacOptions ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8"),
    //headerLicense := Some(HeaderLicense.Custom("""Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>""")), //FIXME to  added
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
      val akkaProperties = System.getProperties.stringPropertyNames.asScala.toList.collect {
        case key: String if key.startsWith("akka.") => "-D" + key + "=" + System.getProperty(key)
      }
      "-Xms1G" :: "-Xmx1G" :: "-XX:MaxDirectMemorySize=256M" :: akkaProperties
    },
    projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
    Global / excludeLintKeys += projectInfoVersion)
//    Global / excludeLintKeys += mimaReportSignatureProblems,    //TODO to add
//    Global / excludeLintKeys += mimaPreviousArtifacts,
//    mimaReportSignatureProblems := true,
//    mimaPreviousArtifacts :=
//      Set(
//        organization.value %% moduleName.value % previousStableVersion.value
//          .getOrElse(throw new Error("Unable to determine previous version"))))

