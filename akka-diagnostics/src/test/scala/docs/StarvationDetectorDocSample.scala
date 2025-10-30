/*
 * Copyright (C) 2025 Lightbend Inc. <https://akka.io>
 */

package docs

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors

object StarvationDetectorDocSample {

  val system: ActorSystem[_] = ActorSystem[Nothing](Behaviors.empty[Nothing], "Doc")

  // #other-dispatcher
  import akka.diagnostics.StarvationDetector

  StarvationDetector.checkDispatcher(system, dispatcherConfigPath = "my-dispatcher")
  // #other-dispatcher

}
