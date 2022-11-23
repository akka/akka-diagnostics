/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.diagnostics

import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

/**
 * The diagnostics extension enables the `DiagnosticsRecorder` for an actor system.
 */
object DiagnosticsExtension extends ExtensionId[DiagnosticsImpl] with ExtensionIdProvider {
  override def lookup: ExtensionId[_ <: Extension] = DiagnosticsExtension
  override def createExtension(system: ExtendedActorSystem): DiagnosticsImpl = new DiagnosticsImpl(system)
}

class DiagnosticsImpl(system: ExtendedActorSystem) extends Extension {
  StarvationDetector.checkSystemDispatcher(system)
}
