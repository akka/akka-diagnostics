# Akka Thread Starvation Detector

The Akka Thread Starvation Detector is a diagnostic tool that monitors the dispatcher of an `ActorSystem` and
will log a warning if the dispatcher becomes unresponsive.

The most common reason for an `ActorSystem` to become unresponsive is that blocking tasks are run on the
dispatcher and other tasks cannot be executed in a timely fashion any more. This will lead to all kinds of problems
because tasks (like handling an Actor's mailbox or executing a Future callback) are usually expected to finish
in very short time on a healthy `ActorSystem`. When thread starvation occurs, all threads of the dispatcher's
thread pool are blocking e.g. doing IO, delaying other work for indefinite periods of time. The symptoms of thread
starvation are usually increased latency (despite low CPU usage), timeouts, or failing Akka Remote connections.

The Starvation Detector will periodically schedule a simple task to measure the scheduling and execution time of
the dispatcher. If a threshold is exceeded, a warning is logged with stack traces that show what threads of the
dispatcher are busy with.

## Using the Starvation Detector

To use the Starvation Detector feature a dependency on the *akka-diagnostics* artifact must be added.

```
@@dependency [Maven,sbt,Gradle] {
  group=com.lightbend.akka
  artifact=akka-diagnostics_$scala.binary.version$
  version=$project.version$
}

This plugin depends on Akka $akka.version$ or later, and note that it is important that all `akka-*` 
dependencies are in the same version, so it is recommended to depend on them explicitly to avoid problems 
with transient dependencies causing an unlucky mix of versions.
```

When this dependency is included the Starvation Detector is automatically run when the *ActorSystem*
is started.

You can create starvation detectors for other execution contexts than the main Akka ActorSystem one as well.
Use `com.lightbend.akka.diagnostics.StarvationDetector.checkExecutionContext` to create a starvation detector
for any `ExecutionContext` (though, it will not include stack trace information if the `ExecutionContext`
is not an Akka Dispatcher).

## Configuration

You can customize settings of the starvation detector to prevent spurious logging depending on your application logic.

@@snip [reference.conf](/akka-diagnostics/src/main/resources/reference.conf) { #starvation-detector}

By default, the starvation detector runs seldom enough not to cause any performance hit itself. Thread starvation issues usually affect
systems for longer time spans, so the starvation detector is still likely to experience and warn even when it runs only infrequently.

## Understanding The Log Output

Here's an example warning (taken from our tests that simulate blocking calls using `Thread.sleep`):

```
[WARN] [04/24/2017 15:38:35.661] [Thread-217] [StarvationDetector(akka://StarvationDetectorSpec)] Exceedingly long scheduling
time on ExecutionContext Dispatcher[akka.actor.default-dispatcher]. Starvation detector task was only executed after 714 ms which is
longer than the warning threshold of 100 milliseconds. This might be a sign of thread, CPU, or dispatcher starvation.
This can be caused by blocking calls, CPU overload, GC, or overlong work units. See the below information for hints
about what could be the cause. Next warning will be shown in 10000 milliseconds.

Thread states (total 2 thread):   2 TIMED_WAITING
Top stacks:
  2 java.lang.Thread.sleep(Native Method)
    StarvationDetectorSpec$$[...]runOne$1$1.apply$mcV$sp(StarvationDetectorSpec.scala:17)
    [...]
```

The logging message gives this information:

 * Scheduling time for the starvation detector task (714 milliseconds)
 * Some general hints what could be the reason for the delay
 * Thread state statistics (`TIMED_WAITING` which is the state `Thread.sleep` puts a thread in)
 * A histogram of stack traces for all threads of this dispatcher ordered by the most frequent stack trace first
   (in this case all of the 2 threads of the dispatcher were blocking in `Thread.sleep` with the same strack trace).
   The shown stack traces show the state of threads when the threshold was exceeded and are only a single sample of
   the state of the application when the condition occurred. The information should therefore be taken only as an
   indication of what could be wrong not as the final answer.

@@@ note

Many blocking IO tasks will block in native code which means that from the view of the JVM, the thread is in `RUNNABLE`
state. Therefore, the thread state statistics will only give a hint at what is going on but the stack traces will usually
give more useful information.

@@@

## Solving Thread Starvation Issues

See @extref:[Blocking Needs Careful Management](akka:dispatchers.html#blocking-needs-careful-management) in
the Akka reference documentation. The Akka-Http documentation also has a page on @extref:["Handling blocking operations"](akka-http:handling-blocking-operations-in-akka-http-routes.html)
that applies generally to all Akka applications.
