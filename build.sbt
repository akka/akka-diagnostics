import akka._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.lightbend.paradox.sbt.ParadoxPlugin
import akka.AkkaDependency.RichProject
import com.typesafe.sbt.packager.docker._
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import Dependencies.{ Scala211, Scala212, Scala213, buildScalaVersion }


lazy val root = Project(id = "akka-diagnostics", file("akka-diagnostics"))
  .settings(defaultSettings)
  .settings(
    Dependencies.akkaDiagnostics)
  .settings(Release.settings: _*)
  .enablePlugins(BootstrapGenjavadoc, UnidocRoot, NoPublish)
  .dependsOn(
    addonsTestkit % "test",
    // so that the tests can check the sbr config
    splitBrainResolver % "test")
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

lazy val splitBrainResolver = akkaAddonsModule("akka-split-brain-resolver")
  .settings(
      Dependencies.akkaSplitBrainResolver,
      TestExtras.Filter.settings)
  .settings(Release.settings: _*)
  .dependsOn(addonsTestkit % "test")
  .enablePlugins(MultiNode, MultiNodeScalaTest, BootstrapGenjavadoc)
  .addAkkaModuleDependency("akka-actor")
  .addAkkaModuleDependency("akka-cluster")
  .addAkkaModuleDependency("akka-coordination")
  .addAkkaModuleDependency("akka-multi-node-testkit", "test")
  // used in the multinode tests for sbr
  .addAkkaModuleDependency("akka-cluster-sharding", "test")
  .addAkkaModuleDependency("akka-cluster-tools", "test")
  .addAkkaModuleDependency("akka-persistence", "test")

// Internal testkit
lazy val addonsTestkit = akkaAddonsModule("addons-testkit")
  .settings(
      name := "addons-testkit",
      description := "functionality for the tests in the other commercial addons",
      Dependencies.testkit
  )
  .addAkkaModuleDependency("akka-testkit")
  .enablePlugins(NoPublish)

lazy val docs = akkaAddonsModule("docs")
  .enablePlugins(AkkaParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, PublishRsyncPlugin)
  .settings(
      name := "Akka Diagnostics",
      publish / skip := true,
//      makeSite := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,
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
          "akka.version26" -> Dependencies.Akka.version26,
          "scala.binaryVersion" -> scalaBinaryVersion.value,
          "extref.scaladoc.base_url" -> s"/${(Preprocess / siteSubdirName).value}/",
          "extref.javadoc.base_url" -> s"/japi/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
          "scaladoc.akka.cluster.fastfailover.base_url" -> s"/api/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
          "javadoc.akka.cluster.fastfailover.base_url" -> s"/japi/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
          "scaladoc.akka.persistence.gdpr.base_url" -> s"/api/akka-diagnostics/${if (isSnapshot.value) "snapshot" else version.value}",
          "extref.akka.base_url" -> s"https://doc.akka.io/docs/akka/${Dependencies.Akka.partialVersion}/%s",
          "scaladoc.akka.base_url" -> s"https://doc.akka.io/api/akka/${Dependencies.Akka.partialVersion}",
          "extref.akka-http.base_url" -> s"https://doc.akka.io/docs/akka-http/${Dependencies.akkaHttpVersion.value}/%s",
          "scaladoc.akka.http.base_url" -> s"https://doc.akka.io/api/akka-http/${Dependencies.akkaHttpVersion.value}/",
          "snip.github_link" -> "false"
      ),
      paradoxRoots := List("index.html", "release-notes.html",
          // https://github.com/lightbend/paradox/issues/350
          "includes/common.html", "includes/proprietary.html"),
      unmanagedSourceDirectories in ScalariformKeys.format in Test := (unmanagedSourceDirectories in Test).value,
      publishRsyncArtifact := makeSite.value -> "www/",
      publishRsyncHost := "akkarepo@gustav.akka.io",
      resolvers += Resolver.jcenterRepo // required to resolve paradox-theme-akka
  )
  .dependsOn(splitBrainResolver, fastFailover)

lazy val fastFailover = akkaAddonsModule("akka-fast-failover")
  .settings(
      Dependencies.akkaFastFailover,
      Protobuf.settings)
  .settings(Release.settings: _*)
  .enablePlugins(BootstrapGenjavadoc)
  .dependsOn(addonsTestkit % "test")
  .addAkkaModuleDependency("akka-protobuf")
  .addAkkaModuleDependency("akka-actor")
  .addAkkaModuleDependency("akka-remote")
  .addAkkaModuleDependency("akka-testkit", "test")
  .addAkkaModuleDependency("akka-cluster-sharding", "test")

def akkaAddonsModule(name: String): Project =
    Project(id = name.replace("/", "-"), base = file(name))
      .settings(defaultSettings)

// settings
lazy val silencerVersion = "1.7.8"
lazy val defaultSettings = Dependencies.Versions ++ Seq(
    organization := "com.lightbend.akka",
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://www.lightbend.com/")),
    crossScalaVersions := Seq(Scala211, Scala212, Scala213),
    scalaVersion := buildScalaVersion,
    description := "Akka Enhancements is a suite of useful components that complement Akka.",

    // compile settings
    Compile / scalacOptions ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-Xfatal-warnings", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint", "-deprecation"),
    Compile / scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 13)) => Seq(
            "-Ywarn-unused:imports",
            "-P:silencer:globalFilters=object JavaConverters in package collection is deprecated",
        )
        case _ => Seq("-Ywarn-unused-import")
    }),
    Compile / scalacOptions  ++= (if (allWarnings) Seq("-deprecation") else Nil),
    Test / scalacOptions := (scalacOptions in Test).value.filterNot(opt =>
        opt == "-Xlog-reflective-calls" || opt.contains("genjavadoc") || opt == "-Xfatal-warnings"),
    Compile / doc / scalacOptions -= "-Xfatal-warnings", // no fatal warning for scaladoc yet
    Compile / doc / scalacOptions ++= Seq(
        "-doc-title",
        "Akka Enhancements",
        "-doc-version",
        version.value,
        "-skip-packages",
        "akka.pattern" // for some reason Scaladoc creates this
    ),

    // allow for silencing fatal warnings where they have reasons (tm)
    libraryDependencies ++= Seq(
        compilerPlugin(
            ("com.github.ghik" %% "silencer-plugin" % silencerVersion).cross(CrossVersion.patch)),
        ("com.github.ghik" %% "silencer-lib" % silencerVersion % Provided).cross(CrossVersion.patch)),

    // -XDignore.symbol.file suppresses sun.misc.Unsafe warnings
    javacOptions in compile ++= Seq("-parameters", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-XDignore.symbol.file"),
    javacOptions in compile ++= (if (allWarnings) Seq("-Xlint:deprecation") else Nil),
    javacOptions in doc ++= Seq(),

    crossVersion := CrossVersion.binary,

    ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet,

    homepage := Some(url("https://akka.io/")),
    publishMavenStyle := true,

    // test settings
    parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", false.toString).toBoolean,
    parallelExecution in Test := System.getProperty("akka.parallelExecution", false.toString).toBoolean,
    logBuffered in Test := System.getProperty("akka.logBufferedTests", "false").toBoolean,

    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),

    // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
    // -a Show stack traces and exception class name for AssertionErrors.
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")
) ++ TestExtras.Filter.settings


def allWarnings: Boolean = System.getProperty("akka.allwarnings", "false").toBoolean