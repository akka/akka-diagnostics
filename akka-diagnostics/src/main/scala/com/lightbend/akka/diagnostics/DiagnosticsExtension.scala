/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.akka.diagnostics

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }

import scala.util.control.NonFatal

/**
 * The diagnostics extension enables the
 * `DiagnosticsRecorder` for an actor system.
 */
object DiagnosticsExtension extends ExtensionId[DiagnosticsImpl] with ExtensionIdProvider {
  override def lookup: ExtensionId[_ <: Extension] = DiagnosticsExtension
  override def createExtension(system: ExtendedActorSystem): DiagnosticsImpl = new DiagnosticsImpl(system)
}

class DiagnosticsImpl(system: ExtendedActorSystem) extends Extension {
  StarvationDetector.checkSystemDispatcher(system)
  try DiagnosticsRecorder(system).runStartupReport()
  catch {
    case NonFatal(e) =>
      system.log.warning(
        "cannot start DiagnosticsRecorder, please configure section akka.diagnostics.recorder " +
          "(to correct error or turn off this feature): {}",
        e.getMessage)
  }

}
