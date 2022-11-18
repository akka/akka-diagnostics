/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbtunidoc.BaseUnidocPlugin.autoImport.{unidoc, unidocProjectFilter}
import sbtunidoc.JavaUnidocPlugin.autoImport.JavaUnidoc
import sbtunidoc.ScalaUnidocPlugin.autoImport.ScalaUnidoc
import sbtunidoc.GenJavadocPlugin.autoImport.{Genjavadoc, unidocGenjavadocVersion}
import sbt.Keys._
import sbt.File

import scala.annotation.tailrec

object Scaladoc extends AutoPlugin {

  object CliOptions {
    val scaladocDiagramsEnabled = CliOption("akka.scaladoc.diagrams", true)
    val scaladocAutoAPI = CliOption("akka.scaladoc.autoapi", true)
  }

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val validateDiagrams = settingKey[Boolean]("Validate generated scaladoc diagrams")

  override lazy val projectSettings = {
    inTask(doc)(Seq(
      scalacOptions in Compile ++= scaladocOptions(version.value, (baseDirectory in ThisBuild).value))) ++
      Seq(
        validateDiagrams in Compile := true,
        autoAPIMappings := CliOptions.scaladocAutoAPI.get) ++
      CliOptions.scaladocDiagramsEnabled.ifTrue(doc in Compile := {
        val docs = (doc in Compile).value
        if ((validateDiagrams in Compile).value)
          scaladocVerifier(docs)
        docs
      })
  }

  def scaladocOptions(ver: String, base: File): List[String] = {
    val opts = List("-implicits", "-groups")
    CliOptions.scaladocDiagramsEnabled.ifTrue("-diagrams").toList ::: opts
  }

  def scaladocVerifier(file: File): File = {
    @tailrec
    def findHTMLFileWithDiagram(dirs: Seq[File]): Boolean = {
      if (dirs.isEmpty) false
      else {
        val curr = dirs.head
        val (newDirs, files) = curr.listFiles.partition(_.isDirectory)
        val rest = dirs.tail ++ newDirs
        val hasDiagram = files exists { f =>
          val name = f.getName
          if (name.endsWith(".html") && !name.startsWith("index-") &&
            !name.equals("index.html") && !name.equals("package.html")) {
            val source = scala.io.Source.fromFile(f)(scala.io.Codec.UTF8)
            val hd = try source.getLines().exists(lines =>
              lines.contains("<div class=\"toggleContainer block diagram-container\" id=\"inheritance-diagram-container\">") ||
                lines.contains("<svg id=\"graph")
            )
            catch {
              case e: Exception => throw new IllegalStateException("Scaladoc verification failed for file '" + f + "'", e)
            } finally source.close()
            hd
          } else false
        }
        hasDiagram || findHTMLFileWithDiagram(rest)
      }
    }

    // if we have generated scaladoc and none of the files have a diagram then fail
    if (file.exists() && !findHTMLFileWithDiagram(List(file)))
      sys.error("ScalaDoc diagrams not generated!")
    else
      file
  }
}

/**
 * For projects with few (one) classes there might not be any diagrams.
 */
object ScaladocNoVerificationOfDiagrams extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = Scaladoc

  override lazy val projectSettings = Seq(
    Scaladoc.validateDiagrams in Compile := false)
}

/**
 * Unidoc settings for root project. Adds unidoc command.
 */
object UnidocRoot extends AutoPlugin {

  object CliOptions {
    // needs to default to true for techhub publish of javadocs
    val genjavadocEnabled = CliOption("akka.genjavadoc.enabled", true)
  }

  object autoImport {
    val unidocRootIgnoreProjects = settingKey[Seq[Project]]("Projects to ignore when generating unidoc")
  }
  import autoImport._

  override def trigger = noTrigger
  override def requires =
    UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(sbtunidoc.ScalaUnidocPlugin && sbtunidoc.JavaUnidocPlugin && sbtunidoc.GenJavadocPlugin)
      .getOrElse(sbtunidoc.ScalaUnidocPlugin)

  val akkaSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(
    Seq(javacOptions in (JavaUnidoc, unidoc) := Seq("-Xdoclint:none"))).getOrElse(Nil)

  override lazy val projectSettings = {
    def unidocRootProjectFilter(ignoreProjects: Seq[Project]) =
      ignoreProjects.foldLeft(inAnyProject) { _ -- inProjects(_) }

    inTask(unidoc)(Seq(
      unidocProjectFilter in ScalaUnidoc := unidocRootProjectFilter(unidocRootIgnoreProjects.value),
      unidocProjectFilter in JavaUnidoc := unidocRootProjectFilter(unidocRootIgnoreProjects.value)))
  }
}

/**
 * Unidoc settings for every multi-project. Adds genjavadoc specific settings.
 */
object BootstrapGenjavadoc extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(sbtunidoc.GenJavadocPlugin)
    .getOrElse(plugins.JvmPlugin)

  override lazy val projectSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(
    Seq(
      scalacOptions in Compile ++= Seq("-P:genjavadoc:fabricateParams=true", "-P:genjavadoc:suppressSynthetic=false"),
      javacOptions in compile += "-Xdoclint:none",
      javacOptions in test += "-Xdoclint:none",
      javacOptions in doc += "-Xdoclint:none",
      unidocGenjavadocVersion in Global := "0.18"
    )
  ).getOrElse(Nil)
}
