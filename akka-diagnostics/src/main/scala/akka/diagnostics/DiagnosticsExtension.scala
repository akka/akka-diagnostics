/*
 * Copyright (C) 2025 Lightbend Inc. <https://akka.io>
 */

package akka.diagnostics

import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

/**
 * The diagnostics extension enables the [[StarvationDetector]] and reports configuration issues with [[ConfigChecker]]
 * for an `ActorSystem`. This extension is automatically activated when the `akka-diagnostics` dependency is included.
 */
object DiagnosticsExtension extends ExtensionId[DiagnosticsExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): DiagnosticsExtension = super.get(system)
  override def get(system: ClassicActorSystemProvider): DiagnosticsExtension = super.get(system)
  override def lookup: ExtensionId[_ <: Extension] = DiagnosticsExtension
  override def createExtension(system: ExtendedActorSystem): DiagnosticsExtension = new DiagnosticsExtension(system)
}

class DiagnosticsExtension(system: ExtendedActorSystem) extends Extension {
  StarvationDetector.checkSystemDispatcher(system)
  StarvationDetector.checkInternalDispatcher(system)
  ConfigChecker.reportIssues(system)
}
