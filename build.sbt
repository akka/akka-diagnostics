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
  .enablePlugins(BootstrapGenjavadoc, UnidocRoot)
  .dependsOn(addonsTestkit % "test")
  .addAkkaModuleDependency("akka-actor")
  // used for verifying the config checker
  .addAkkaModuleDependency("akka-remote", "test")
  .addAkkaModuleDependency("akka-cluster", "test")
  .addAkkaModuleDependency("akka-cluster-metrics", "test")
  .addAkkaModuleDependency("akka-cluster-sharding", "test")
  .addAkkaModuleDependency("akka-cluster-tools", "test")
  .addAkkaModuleDependency("akka-persistence", "test")
  .settings(
      UnidocRoot.autoImport.unidocRootIgnoreProjects := List(
          docs,
          addonsTestkit)
  )

// Internal testkit
lazy val addonsTestkit = akkaAddonsModule("addons-testkit")
  .settings(
      name := "addons-testkit",
      description := "functionality for the tests in the other commercial addons",
      Dependencies.testkit
  )
  .addAkkaModuleDependency("akka-testkit")

lazy val docs = akkaAddonsModule("docs")
  .enablePlugins(AkkaParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, PublishRsyncPlugin)
  .settings(dontPublish)
  .settings(
      name := "Akka Diagnostics",
      publish / skip := true,
//      makeSite := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,  //FIXME to be added
      Preprocess / siteSubdirName := s"api/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
      Preprocess / sourceDirectory := (LocalRootProject / ScalaUnidoc / unidoc / target).value,
      Preprocess / preprocessRules := Seq(
          ("\\.java\\.scala".r, _ => ".java")
      ),
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
      paradoxRoots := List("index.html", "release-notes.html",
          // https://github.com/lightbend/paradox/issues/350
          "includes/common.html", "includes/proprietary.html"),
      publishRsyncArtifacts += makeSite.value -> "www/",
      publishRsyncHost := "akkarepo@gustav.akka.io",
      resolvers += Resolver.jcenterRepo // required to resolve paradox-theme-akka
  )

def akkaAddonsModule(name: String): Project =
    Project(id = name.replace("/", "-"), base = file(name))
      .settings(defaultSettings)

lazy val dontPublish = Seq(publish / skip := true, Compile / publishArtifact := false)


// settings
lazy val silencerVersion = "1.7.8"
lazy val defaultSettings = Seq(
    organization := "com.lightbend.akka",
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://www.lightbend.com/")),
    crossScalaVersions := Seq(Scala212, Scala213),
    scalaVersion := buildScalaVersion,
    description := "Akka Diagnostics is a suite of useful components that complement Akka.",

    // compile settings
    Test / scalacOptions := (Test / scalacOptions).value.filterNot(opt =>
        opt == "-Xlog-reflective-calls" || opt.contains("genjavadoc") || opt == "-Xfatal-warnings"),
    Compile / doc / scalacOptions -= "-Xfatal-warnings", // no fatal warning for scaladoc yet
    Compile / doc / scalacOptions ++= Seq(
        "-doc-title",
        "Akka Diagnostics",
        "-doc-version",
        version.value,
        "-skip-packages",
        "akka.pattern" // for some reason Scaladoc creates this
    ),

    // -XDignore.symbol.file suppresses sun.misc.Unsafe warnings
    Compile / javacOptions ++= Seq("-parameters", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-XDignore.symbol.file"),
    doc / javacOptions ++= Seq(),
    crossScalaVersions := Seq(Dependencies.Scala213, Dependencies.Scala212),
    crossVersion := CrossVersion.binary,

    ThisBuild / ivyLoggingLevel := UpdateLogging.Quiet,

    homepage := Some(url("https://akka.io/")),
    publishMavenStyle := true,

    // test settings
    GlobalScope / parallelExecution := System.getProperty("akka.parallelExecution", false.toString).toBoolean,
    Test / parallelExecution := System.getProperty("akka.parallelExecution", false.toString).toBoolean,
    Test / logBuffered := System.getProperty("akka.logBufferedTests", "false").toBoolean,

    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument("-oDF"),

    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -a Show stack traces and exception class name for AssertionErrors.
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")
) ++ TestExtras.Filter.settings