/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt.Keys._
import sbt._

object Release {
  val settings = Seq(
    publishTo := Some("Cloudsmith API" at "https://maven.cloudsmith.io/lightbend/commercial-releases/"),
    publishMavenStyle := true,
    // we don't want any repository blocks in the pom-file
    pomIncludeRepository := { _ => false },
    /// Format of credentials:

    /*
    realm=Cloudsmith API
    host=maven.cloudsmith.io
    user=USERNAME
    password=YOUR-API-KEY
    */
    /// API key found at cloudsmith.io/user/settings/api
    /// Contact internal-it@lightbend.com to get set up if needed

    credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
  )
}
