/*
 * Copyright (C) 2025 Lightbend Inc. <https://akka.io>
 */

package jdocs;

import akka.actor.typed.ActorSystem;
//#other-dispatcher
import akka.diagnostics.StarvationDetector;

//#other-dispatcher

public class StarvationDetectorDocSample {

  static void illustrateOtherDispatcher(ActorSystem<?> system) {
    //#other-dispatcher
    String dispatcherConfigPath = "my-dispatcher";
    StarvationDetector.checkDispatcher(system, dispatcherConfigPath);
    //#other-dispatcher
  }
}
