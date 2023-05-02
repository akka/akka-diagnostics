/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
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
