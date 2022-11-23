# Akka Diagnostics

Akka Diagnostics is a suite of useful components that complement Akka.

## Akka Thread Starvation Detector
The Akka Thread Starvation Detector is a diagnostic tool that monitors the dispatcher of an ActorSystem and will log a warning if the dispatcher becomes unresponsive.

The most common reason for an ActorSystem to become unresponsive is that blocking tasks are run on the dispatcher and other tasks cannot be executed in a timely fashion any more. This will lead to all kinds of problems because tasks (like handling an Actor’s mailbox or executing a Future callback) are usually expected to finish in very short time on a healthy ActorSystem. When thread starvation occurs, all threads of the dispatcher’s thread pool are blocking e.g. doing IO, delaying other work for indefinite periods of time. The symptoms of thread starvation are usually increased latency (despite low CPU usage), timeouts, or failing Akka Remote connections.

The Starvation Detector will periodically schedule a simple task to measure the scheduling and execution time of the dispatcher. If a threshold is exceeded, a warning is logged with stack traces that show what threads of the dispatcher are busy with.

Using the Starvation Detector
To use the Starvation Detector feature a dependency on the akka-diagnostics artifact must be added.


    sbt
    "com.lightbend.akka" %% "akka-diagnostics" % "1.1.16"