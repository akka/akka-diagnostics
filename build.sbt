import akka.AutomaticModuleName
import com.geirsson.CiReleasePlugin
import sbtdynver.DynVerPlugin.autoImport.dynverSonatypeSnapshots

GlobalScope / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

val specificationVersion: String = sys.props("java.specification.version")
val isJdk17orHigher: Boolean =
  VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(">=17"))
val isJdk11orHigher: Boolean = {
  val result = VersionNumber(sys.props("java.specification.version")).matchesSemVer(SemanticSelector(">=11"))
  if (!result)
    throw new IllegalArgumentException("JDK 11 or higher is required")
  result
}

inThisBuild(
  Seq(
    organization := "com.lightbend.akka",
    organizationName := "Lightbend Inc.",
    homepage := Some(url("https://doc.akka.io/docs/akka-diagnostics/current")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/akka/akka-diagnostics"), "https://github.com/akka/akka-diagnostics.git")),
    startYear := Some(2022),
    developers += Developer(
      "contributors",
      "Contributors",
      "https://gitter.im/akka/dev",
      url("https://github.com/akka/akka-diagnostics/graphs/contributors")),
    releaseNotesURL := (
      if (isSnapshot.value) None
      else Some(url(s"https://github.com/akka/akka-diagnostics/releases/tag/v${version.value}"))
    ),
    licenses := {
      val tagOrBranch =
        if (isSnapshot.value) "main"
        else "v" + version.value
      Seq(("BUSL-1.1", url(s"https://raw.githubusercontent.com/akka/akka-diagnostics/${tagOrBranch}/LICENSE")))
    },
    description := "Akka diagnostics tools and utilities",
    // append -SNAPSHOT to version when isSnapshot
    dynverSonatypeSnapshots := true,
    // add snapshot repo when Akka version overriden
    resolvers += "Akka library repository".at("https://repo.akka.io/maven"),
    resolvers ++=
      (if (System.getProperty("override.akka.version") != null)
         Seq("Akka Snapshots".at("https://oss.sonatype.org/content/repositories/snapshots/"))
       else Seq.empty)))

lazy val common: Seq[Setting[_]] =
  Seq(
    crossScalaVersions := Dependencies.CrossScalaVersions,
    scalaVersion := Dependencies.CrossScalaVersions.head,
    crossVersion := CrossVersion.binary,
    scalafmtOnCompile := true,
    headerLicense := Some(HeaderLicense.Custom("""Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>""")),
    // Setting javac options in common allows IntelliJ IDEA to import them automatically
    Compile / javacOptions ++= Seq("-encoding", "UTF-8", "--release", "11"),
    Compile / scalacOptions ++= {
      var scalacOptionsBase = Seq("-encoding", "UTF-8", "-feature", "-unchecked", "-deprecation", "-release", "11")
      if (scalaVersion.value == Dependencies.Scala213)
        scalacOptionsBase ++: Seq("-Xfatal-warnings", "-Xlint", "-Ywarn-dead-code", "-Wconf:cat=deprecation:info")
      else
        scalacOptionsBase
    },
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
      val openModules =
        if (isJdk17orHigher) Seq("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED")
        else Nil
      "-Xms1G" :: "-Xmx1G" :: "-XX:MaxDirectMemorySize=256M" :: akkaProperties ++ openModules
    },
    projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
    Global / excludeLintKeys += projectInfoVersion)

lazy val root = (project in file("."))
  .settings(
    name := "akka-diagnostics-root",
    publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo"))))
  .settings(common)
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(SitePlugin, CiReleasePlugin)
  .settings(dontPublish)
  .aggregate(`akka-diagnostics`, docs)

lazy val `akka-diagnostics` = (project in file("akka-diagnostics"))
  .settings(common)
  .settings(libraryDependencies ++= Dependencies.akkaDiagnostics)
  .settings(AutomaticModuleName.settings("akka.diagnostics"))
  .disablePlugins(CiReleasePlugin)

lazy val docs = (project in file("docs"))
  .enablePlugins(AkkaParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, PublishRsyncPlugin)
  .settings(common)
  .settings(dontPublish)
  .settings(
    name := "Akka Diagnostics",
    makeSite := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,
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
      "extref.akka-http.base_url" -> s"https://doc.akka.io/docs/akka-http/${Dependencies.AkkaHttpVersionInDocs}/%s",
      "scaladoc.akka.http.base_url" -> s"https://doc.akka.io/api/akka-http/${Dependencies.AkkaHttpVersionInDocs}/",
      "snip.github_link" -> "false"),
    ApidocPlugin.autoImport.apidocRootPackage := "akka",
    apidocRootPackage := "akka",
    resolvers += Resolver.jcenterRepo, // required to resolve paradox-theme-akka
    publishRsyncArtifacts += makeSite.value -> "www/",
    publishRsyncHost := "akkarepo@gustav.akka.io")
  .disablePlugins(CiReleasePlugin)

lazy val dontPublish = Seq(publish / skip := true, Compile / publishArtifact := false)
