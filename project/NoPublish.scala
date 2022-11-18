package akka
import sbt.AutoPlugin

import sbt._
import sbt.Keys._

object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    skip in publish := true,
    sources in (Compile, doc) := Seq.empty,
  )
}