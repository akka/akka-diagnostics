/*
 * Copyright (C) 2009-2019 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.akka.diagnostics

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.management.scaladsl.{ ManagementRouteProvider, ManagementRouteProviderSettings }
import com.lightbend.akka.diagnostics.ConfigChecker.{ ConfigWarning, ValidationResults }
import spray.json.RootJsonFormat

/**
 * INTERNAL API
 */
@InternalApi
object ManagementRoutes extends ExtensionId[ManagementRoutes] {
  def createExtension(system: ExtendedActorSystem): ManagementRoutes =
    new ManagementRoutes(system)
}

/**
 * Provides management routes for the diagnostics extensions plus a few additional endpoints
 * when Akka Management is used together with Akka Diagnostics.
 *
 * INTERNAL API
 */
@InternalApi
final class ManagementRoutes private (system: ExtendedActorSystem) extends Extension with ManagementRouteProvider {

  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import JsonFormats._

  private val log = Logging.getLogger(system, this)

  def routes(settings: ManagementRouteProviderSettings): Route =
    pathPrefix("diagnostics") {
      if (system.settings.config.getBoolean("akka.diagnostics.management.enabled")) {
        log.debug("Adding management routes for Akka Diagnostics")
        extractClientIP { clientIp =>
          concat(
            path("startup-info") {
              log.info("Startup info dumped through management to {}", clientIp)
              val infoJsonString = DiagnosticsRecorder(system).gatherStartupInfo()
              complete(HttpEntity(ContentTypes.`application/json`, infoJsonString))
            },
            path("config-check") {
              log.info("Config check triggered through management from {}", clientIp)
              val validationResults = new ConfigChecker(system).check()
              complete(validationResults)
            },
            path("thread-dump") {
              log.info("System threads dumped through management to {}", clientIp)
              val threadDumpJsonString = DiagnosticsRecorder(system).dumpThreads()
              complete(HttpEntity(ContentTypes.`application/json`, threadDumpJsonString))
            })
        }
      } else {
        log.debug("Management routes for Akka Diagnostics disabled")
        complete(HttpResponse(StatusCodes.Forbidden, entity = HttpEntity("Management routes for Akka Diagnostics disabled")))
      }
    }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object JsonFormats {
  import spray.json.DefaultJsonProtocol._

  implicit val configWarningFormat = jsonFormat4(ConfigWarning)
  implicit val validationResultsFormat: RootJsonFormat[ValidationResults] = jsonFormat1(ValidationResults)
}
