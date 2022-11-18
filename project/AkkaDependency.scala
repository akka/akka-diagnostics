/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt.Keys._
import sbt._

object AkkaDependency {
  // Needs to be a URI like git://github.com/akka/akka.git#master or file:///xyz/akka
  val akkaSourceDependencyUri = System.getProperty("akka.sources", "")
  val shouldUseSourceDependency = akkaSourceDependencyUri != ""
  val akkaRepository = {
    // as a little hacky side effect also disable aggregation of samples
    System.setProperty("akka.build.aggregateSamples", "false")

    uri(akkaSourceDependencyUri)
  }

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String, config: String = "", versionFromSetting: Boolean = false): Project =
      if (shouldUseSourceDependency) {
        val moduleRef = ProjectRef(akkaRepository, module)
        val withConfig: ClasspathDependency =
          if (config == "") moduleRef
          else moduleRef % config

        project.dependsOn(withConfig)
      } else {
        project.settings(libraryDependencies += {
          val dep = "com.typesafe.akka" %% module % (if (versionFromSetting) Dependencies.akkaVersion.value else Dependencies.Akka.version)
          val withConfig =
            if (config == "") dep
            else dep % config
          withConfig
        })
      }
  }
}
