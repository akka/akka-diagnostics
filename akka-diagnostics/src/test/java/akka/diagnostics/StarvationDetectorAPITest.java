/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.diagnostics;

import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class StarvationDetectorAPITest {
    StarvationDetectorAPITest() {
        FiniteDuration duration = Duration.create(1, TimeUnit.SECONDS);
        StarvationDetectorSettings settings =
            StarvationDetectorSettings.create(duration, duration, duration, duration);
        // compilation test
        StarvationDetector.checkExecutionContext(null, null, // dummy values
                settings,
                () -> true);
    }
}
