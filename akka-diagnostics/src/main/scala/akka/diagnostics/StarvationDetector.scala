/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.diagnostics

import java.util.concurrent.ConcurrentHashMap
import akka.dispatch.affinity.AffinityPool
import akka.actor.ClassicActorSystemProvider
import akka.annotation.InternalApi
import akka.dispatch.Dispatcher
import akka.dispatch.ExecutorServiceDelegate
import akka.dispatch.ForkJoinExecutorConfigurator.AkkaForkJoinPool
import akka.dispatch.MonitorableThreadFactory
import akka.event.Logging
import akka.event.LoggingAdapter
import com.typesafe.config.Config

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.function.BooleanSupplier

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

final class StarvationDetectorSettings(
    val checkInterval: FiniteDuration,
    val initialDelay: FiniteDuration,
    val maxDelayWarningThreshold: FiniteDuration,
    val warningInterval: FiniteDuration,
    val threadTraceLimit: Int) {

  def withCheckInterval(newCheckInterval: FiniteDuration): StarvationDetectorSettings =
    copy(checkInterval = newCheckInterval)
  def withInitialDelay(newInitialDelay: FiniteDuration): StarvationDetectorSettings =
    copy(initialDelay = newInitialDelay)
  def withMaxDelayWarningThreshold(newMaxDelayWarningThreshold: FiniteDuration): StarvationDetectorSettings =
    copy(maxDelayWarningThreshold = newMaxDelayWarningThreshold)
  def withWarningInterval(newWarningInterval: FiniteDuration): StarvationDetectorSettings =
    copy(warningInterval = newWarningInterval)
  def withThreadTraceLimit(newValue: Int): StarvationDetectorSettings =
    copy(threadTraceLimit = newValue)
  def withNoThreadTraceLimit: StarvationDetectorSettings =
    copy(threadTraceLimit = Integer.MAX_VALUE)

  def isEnabled: Boolean = checkInterval > Duration.Zero

  private def copy(
      checkInterval: FiniteDuration = checkInterval,
      initialDelay: FiniteDuration = initialDelay,
      maxDelayWarningThreshold: FiniteDuration = maxDelayWarningThreshold,
      warningInterval: FiniteDuration = warningInterval,
      threadTraceLimit: Int = threadTraceLimit): StarvationDetectorSettings =
    new StarvationDetectorSettings(
      checkInterval,
      initialDelay,
      maxDelayWarningThreshold,
      warningInterval,
      threadTraceLimit)
}

object StarvationDetectorSettings {
  def apply(
      checkInterval: FiniteDuration,
      initialDelay: FiniteDuration,
      maxDelayWarningThreshold: FiniteDuration,
      warningInterval: FiniteDuration): StarvationDetectorSettings =
    apply(checkInterval, initialDelay, maxDelayWarningThreshold, warningInterval, 5)

  def apply(
      checkInterval: FiniteDuration,
      initialDelay: FiniteDuration,
      maxDelayWarningThreshold: FiniteDuration,
      warningInterval: FiniteDuration,
      threadTraceLimit: Int): StarvationDetectorSettings =
    new StarvationDetectorSettings(
      checkInterval,
      initialDelay,
      maxDelayWarningThreshold,
      warningInterval,
      threadTraceLimit)

  /** Java API */
  def create(
      checkInterval: FiniteDuration,
      initialDelay: FiniteDuration,
      maxDelayWarningThreshold: FiniteDuration,
      warningInterval: FiniteDuration): StarvationDetectorSettings =
    apply(checkInterval, initialDelay, maxDelayWarningThreshold, warningInterval)

  /** Java API */
  def create(
      checkInterval: FiniteDuration,
      initialDelay: FiniteDuration,
      maxDelayWarningThreshold: FiniteDuration,
      warningInterval: FiniteDuration,
      threadTraceLimit: Int): StarvationDetectorSettings =
    apply(checkInterval, initialDelay, maxDelayWarningThreshold, warningInterval, threadTraceLimit)

  def fromConfig(config: Config): StarvationDetectorSettings = {
    def finiteDuration(path: String): FiniteDuration =
      Duration(config.getDuration(path, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    def finiteDurationOrOff(path: String): FiniteDuration =
      if (config.getString(path) == "off") Duration.Zero
      else finiteDuration(path)

    apply(
      finiteDurationOrOff("check-interval"),
      finiteDurationOrOff("initial-delay"),
      finiteDuration("max-delay-warning-threshold"),
      finiteDuration("warning-interval"),
      config.getString("thread-traces-limit") match {
        case "infinite" => Integer.MAX_VALUE
        case x          => x.toInt
      })
  }

}

object StarvationDetector {

  // only using the key of this Map, but need ConcurrentHashMap for `computeIfAbsent`
  private val starvationMonitoredContexts = new ConcurrentHashMap[ExecutionContext, StarvationDetectorThread]()

  final case class UnsupportedDispatcherException(msg: String) extends RuntimeException(msg) with NoStackTrace

  /**
   * Creates and runs a StarvationDetector thread for the dispatcher of the system's main dispatcher, i.e.
   * akka.actor.default-dispatcher.
   */
  def checkSystemDispatcher(provider: ClassicActorSystemProvider): Unit =
    checkSystemDispatcher(
      provider,
      StarvationDetectorSettings.fromConfig(
        provider.classicSystem.settings.config.getConfig("akka.diagnostics.starvation-detector")))

  /**
   * Creates and runs a StarvationDetector thread for the dispatcher of the system's main dispatcher, i.e.
   * akka.actor.default-dispatcher, with custom configuration.
   */
  def checkSystemDispatcher(provider: ClassicActorSystemProvider, config: StarvationDetectorSettings): Unit = {
    val system = provider.classicSystem
    checkExecutionContext(
      system.dispatcher,
      Logging(system, classOf[StarvationDetectorThread]),
      config,
      () => system.whenTerminated.isCompleted)
  }

  /**
   * Creates and runs a StarvationDetector thread for the internal dispatcher of the system. Akka 2.6 introduced this
   * dispatcher for internal tasks like clustering. If there is no internal dispatcher, does nothing.
   */
  def checkInternalDispatcher(provider: ClassicActorSystemProvider): Unit =
    checkInternalDispatcher(
      provider,
      StarvationDetectorSettings.fromConfig(
        provider.classicSystem.settings.config.getConfig("akka.diagnostics.starvation-detector")))

  /**
   * Creates and runs a StarvationDetector thread for the internal dispatcher of the system with custom configuration.
   * Akka 2.6 introduced this dispatcher for internal tasks like clustering. If there is no internal dispatcher, does
   * nothing.
   */
  def checkInternalDispatcher(provider: ClassicActorSystemProvider, config: StarvationDetectorSettings): Unit = {
    checkDispatcher(provider, "akka.actor.internal-dispatcher", config)
  }

  /**
   * Creates and runs a StarvationDetector thread for the given Akka dispatcher defined in config with the
   * `dispatcherConfigPath`.
   *
   * Note that a dispatcher will only have one StarvationDetector for it active at a time. If there is another
   * StarvationDetector running, this method does nothing.
   */
  def checkDispatcher(provider: ClassicActorSystemProvider, dispatcherConfigPath: String): Unit =
    checkDispatcher(
      provider,
      dispatcherConfigPath,
      StarvationDetectorSettings.fromConfig(
        provider.classicSystem.settings.config.getConfig("akka.diagnostics.starvation-detector")))

  /**
   * Creates and runs a StarvationDetector thread for the given Akka dispatcher defined in config with the
   * `dispatcherConfigPath`.
   *
   * Note that a dispatcher will only have one StarvationDetector for it active at a time. If there is another
   * StarvationDetector running, this method does nothing.
   */
  def checkDispatcher(
      provider: ClassicActorSystemProvider,
      dispatcherConfigPath: String,
      config: StarvationDetectorSettings): Unit = {
    val system = provider.classicSystem
    val dispatcher = system.dispatchers.lookup(dispatcherConfigPath)
    checkExecutionContext(
      dispatcher,
      Logging(system, classOf[StarvationDetectorThread]),
      config,
      () => system.whenTerminated.isCompleted)
  }

  /**
   * Creates and runs a StarvationDetector thread for the given ExecutionContext. Thread analytics are currently only
   * available for Akka dispatchers.
   *
   * You need to provide a `hasTerminated` function that will be used to figure out if the execution context has shut
   * down to shutdown the starvation detector thread.
   *
   * Note that an ExecutionContext will only have one StarvationDetector for it active at a time. If there is another
   * StarvationDetector running, this method does nothing.
   */
  def checkExecutionContext(
      ec: ExecutionContext,
      log: LoggingAdapter,
      config: StarvationDetectorSettings,
      hasTerminated: () => Boolean): Unit =
    if (config.isEnabled) {
      starvationMonitoredContexts.computeIfAbsent(
        ec,
        _ => {
          val thread = new StarvationDetectorThread(ec, log, config, hasTerminated)
          thread.setDaemon(true)
          thread.start()
          thread
        })
    }

  /**
   * JAVA API
   *
   * Creates and runs a StarvationDetector thread for the given ExecutionContext. Thread analytics are currently only
   * available for Akka dispatchers.
   *
   * You need to provide a `hasTerminated` function that will be used to figure out if the execution context has shut
   * down to shutdown the starvation detector thread.
   */
  def checkExecutionContext(
      ec: ExecutionContext,
      log: LoggingAdapter,
      config: StarvationDetectorSettings,
      hasTerminated: BooleanSupplier): Unit =
    checkExecutionContext(ec, log, config, hasTerminated.getAsBoolean _)

  private type StackTrace = Array[StackTraceElement]

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] class StarvationDetectorThread(
      ec: ExecutionContext,
      log: LoggingAdapter,
      config: StarvationDetectorSettings,
      hasTerminated: () => Boolean)
      extends Thread {
    import config._

    val ecName = ec.toString
    setName(s"StarvationDetector-$ecName")

    val checkIntervalNanos = checkInterval.toNanos
    @volatile // to allow overriding in tests
    var nextWarningAfterNanos = 0L

    class Check(onFinish: () => Unit) extends Runnable {
      def run(): Unit = onFinish()
    }

    def checkOnce(): Unit = {
      val latch = new CountDownLatch(1)
      val startNanos = System.nanoTime()
      ec.execute(new Check(() => latch.countDown()))
      if (!latch.await(maxDelayWarningThreshold.toNanos, TimeUnit.NANOSECONDS)) {
        if (log.isDebugEnabled) log.debug(s"Starvation detector triggered for $ecName")
        // take a stack dump immediately when the threshold is exceeded
        val stackTraces = Try(threadsOfExecutor(ec).map(statusOf))
        if (log.isDebugEnabled) log.debug(s"Starvation detector finished collecting stack traces for $ecName")
        latch.await() // then wait for completion of the task
        val endNanos = System.nanoTime()
        val lasted = endNanos - startNanos

        if (endNanos > nextWarningAfterNanos) {
          val info =
            stackTraces match {
              case Success(threadStatuses) =>
                val states = threadStats(threadStatuses)
                val stacks = topStacks(threadStatuses)

                if (threadStatuses.isEmpty) {
                  s"No dispatcher thread found running. The cause for the task scheduling delay is likely " +
                  "caused by external causes and not by thread starvation. Possible causes could be GC pauses, " +
                  "general CPU overload or scheduling issues, pauses due to a VM or container environment."
                } else {
                  s"Thread states (total ${threadStatuses.size} thread): $states\n" +
                  s"Stack traces:\n$stacks"
                }
              case Failure(ex) =>
                ex.getClass.getName match {
                  case "java.lang.reflect.InaccessibleObjectException" => //FIXME avoid using class name when JDK 8 is not supported
                    throw InaccessibleObjectException(ex.getMessage, ex.getCause) // stopping Starvation Detector
                  case _ => s"[Could not get thread info because ${ex.toString}]"
                }
            }

          log.warning(
            s"Exceedingly long scheduling time on ExecutionContext $ecName. " +
            s"Starvation detector task was only executed after ${lasted / 1000000} ms which is " +
            s"longer than the warning threshold of $maxDelayWarningThreshold. " +
            s"This might be a sign of thread, CPU, or dispatcher starvation. " +
            s"This can be caused by blocking calls, CPU overload, GC, or overlong work units. " +
            s"See the below information for hints about what could be the cause. " +
            s"Next warning will be shown in $warningInterval.\n\n$info")

          nextWarningAfterNanos = endNanos + warningInterval.toNanos
        }
      }
    }

    override def run(): Unit =
      try {
        val random = ThreadLocalRandom.current()

        if (initialDelay > Duration.Zero) {
          log.info(
            s"Starvation detector will start after `akka.diagnostics.starvation-detector.initial-delay = $initialDelay`")
          LockSupport.parkNanos(initialDelay.toNanos)
        }
        log.info(s"Starvation detector starting for dispatcher [$ec]")
        while (!hasTerminated()) {
          try checkOnce()
          catch {
            case e: InaccessibleObjectException =>
              log.warning(s"Stopping Starvation detector. Reason: ${e.getMessage}. \n" +
              s"Probably you are missing some JVM parameters. See 'note' in https://doc.akka.io/libraries/akka-diagnostics/current/starvation-detector.html#configuration")
              return
            case NonFatal(ex) =>
              log.error(
                ex,
                "Starvation detector failed and terminated. This is likely a bug. " +
                "Please report to https://github.com/akka/akka-diagnostics/issues")
              return
          }
          // Add +/- 5% of jitter. In tests, we observed accidental synchronization of starvation detector execution
          // with other periodic tasks on the dispatcher which can lead to false results. In other words,
          // when we reach here we know the latest starvation detector task just finished, i.e. it just got a slot for
          // execution. If the check interval is an integer factor or multiple of one of the periodic tasks
          // that run on this dispatcher (e.g. Akka scheduler granularity is 100ms, check interval is 1 second)
          // we might now accidentally schedule the next execution directly into a free(ish) time slot.
          val jitter = (checkIntervalNanos / 100 * (random.nextDouble() - 0.5)).toLong
          LockSupport.parkNanos(checkIntervalNanos + jitter)
        }
      } finally {
        log.info(s"Starvation detector stopping for dispatcher [$ec]")
        starvationMonitoredContexts.remove(ec)
      }

    case class ThreadStatus(thread: Thread, state: Thread.State, stackTrace: StackTrace) {
      def mapTrace(f: StackTrace => StackTrace): ThreadStatus = copy(stackTrace = f(stackTrace))
    }
    private def statusOf(thread: Thread): ThreadStatus = ThreadStatus(thread, thread.getState, thread.getStackTrace)
    private def threadsOfExecutor(ec: ExecutionContext): Seq[Thread] = ec match {
      case x: Dispatcher => threadsOfExecutorService(getDispatcherES(x).executor)
      case _             => Nil
    }
    private def threadsOfExecutorService(es: ExecutorService): Seq[Thread] = threadNamePrefix(es) match {
      case Some(prefix) =>
        val buffer = new Array[Thread](Thread.activeCount() + 10)
        val read = Thread.enumerate(buffer)
        val threadsForPrefix = buffer
          .take(read)
          .filter(_.getName.startsWith(prefix))
          .toVector
        if (threadsForPrefix.nonEmpty) threadsForPrefix
        else {
          if (log.isDebugEnabled) {
            log.debug(
              "Failed to find any threads for prefix [{}] among thread names [{}]",
              prefix,
              buffer.map(_.getName).mkString(", "))
          }
          Nil
        }
      case _ =>
        throw UnsupportedDispatcherException(
          s"Failed to extract thread prefix, unsupported executor service type [${es.getClass.toString}], starvation will not be detected for this dispatcher.")
    }
    private def threadNamePrefix(es: ExecutorService): Option[String] = es match {
      case ak: AkkaForkJoinPool    => Some(getAkkaFJPFactory(ak).name)
      case tpe: ThreadPoolExecutor => Some(getThreadPoolExecutorFactory(tpe).name)
      case ap: AffinityPool        => Some(getAffinityPoolFactory(ap).name)
      case _                       => None
    }
    private def isSleepingFJThread(trace: StackTrace): Boolean =
      trace.length >= 2 &&
      trace
        .take(5)
        .exists(t =>
          Problem.classMethod("akka.dispatch.forkjoin.ForkJoinPool.scan")(t) ||
          Problem.classMethod("java.util.concurrent.ForkJoinPool.scan")(t))

    private def topStacks(threadStacks: Seq[ThreadStatus]): String = {
      val allStacks =
        threadStacks
          .map(
            _.mapTrace(_.takeWhile(!_.getClassName.endsWith("TaskInvocation")))
          ) // TaskInvocation.run is the last frame belonging to Akka's scheduler impl

      val filteredStacksWithoutEmpty =
        allStacks
          .filterNot(t => t.stackTrace.isEmpty)

      val filteredStacks =
        filteredStacksWithoutEmpty
          .filterNot(t => isSleepingFJThread(t.stackTrace))

      val numEmpty = allStacks.size - filteredStacksWithoutEmpty.size
      val numSleepingPoolThread = filteredStacksWithoutEmpty.size - filteredStacks.size

      val groupedAndSortedBySize =
        filteredStacks
          .groupBy(x => (x.stackTrace.toVector, x.state))
          .toVector
          .groupBy(_._2.size)
          .toVector
          .sortBy(-_._1)

      if (groupedAndSortedBySize.nonEmpty) {
        val toTake =
          if (groupedAndSortedBySize.head._1 == 1) Integer.MAX_VALUE // if grouping doesn't work show all
          else config.threadTraceLimit // otherwise show the top N offenders

        val traceGroups = groupedAndSortedBySize.flatMap(_._2)
        val omittedGroups =
          if (traceGroups.size > toTake)
            s"traces for ${traceGroups.drop(toTake).map(_._2.size).sum} threads were omitted. Increase " +
            s"`akka.diagnostics.starvation-detector.thread-traces-limit` to show more stack traces, "
          else ""

        traceGroups
          .take(toTake)
          .map { case ((topElement, topState), els) =>
            val problem = Problem.WellKnownProblems.find(_.stackTraceFilter(topElement))
            val problemDesc = problem
              .map { p =>
                s"\n\nPotential issue '${p.name}': ${p.description}${p.uri.map(uri => s" Find more information at $uri.").getOrElse("")}"
              }
              .getOrElse("")

            f"${els.size}%3d thread(s) in state: $topState%s ${topElement.head}\n" +
            topElement.drop(1).map(el => s"    $el").mkString("\n") + problemDesc
          }
          .mkString("\n\n") +
        s"\n\nAdditionally, $omittedGroups" +
        s"$numEmpty threads reported an empty stack trace, and " +
        s"$numSleepingPoolThread threads were sleeping pool threads waiting for work."
      } else {
        s"No active threads found ($numEmpty threads reported an empty stack trace," +
        s"$numSleepingPoolThread threads were sleeping pool threads waiting for work)"
      }
    }

    private def threadStats(threads: Seq[ThreadStatus]): String = {
      threads
        .groupBy(_.state)
        .toVector
        .sortBy(_._1.name())
        .map { case (state, els) =>
          f"${els.size}%3d $state"
        }
        .mkString(" ")
    }
  }

  private type StackTraceFilter = Seq[StackTraceElement] => Boolean
  private case class Problem(name: String, description: String, uri: Option[String], stackTraceFilter: StackTraceFilter)
  private object Problem {

    val Jdk21 = Runtime.version().feature() == 21
    val Jdk25OrNewer = Runtime.version().feature() >= 25

    val WellKnownProblems: Seq[Problem] = Seq(
      Problem(
        "Thread.sleep",
        "Thread.sleep blocks a thread. Use system.scheduler.scheduleOnce or akka.pattern.after to continue processing asynchronously after a delay.",
        None,
        if (Jdk21) topFrameIs(classMethod("java.lang.Thread.sleep0")) // native method for loom
        else if (Jdk25OrNewer) topFrameIs(classMethod("java.lang.Thread.sleepNanos0")) // native method for loom
        else topFrameIs(classMethod("java.lang.Thread.sleep"))),
      Problem(
        "Await",
        "Await.ready / Await.result blocks a thread. Use Future.map and other combinators to continue processing asynchronously after a Future is completed.",
        None,
        anyFrameIs(classMethod("scala.concurrent.Await$.ready") || classMethod("scala.concurrent.Await$.result"))),
      Problem(
        "CompletableFuture.get",
        "CompletableFuture.get blocks a thread. Use `thenApply` and other combinators to continue processing asynchronously after a Future is completed.",
        None,
        anyFrameIs(classMethod("java.util.concurrent.CompletableFuture.get"))),
      Problem(
        "java.net",
        "java.net API is synchronous and blocks a thread. Use an asynchronous network API instead like Akka TCP, Akka Stream TCP, or java.nio.channels.SocketChannel.",
        None,
        anyFrameIs(classStartsWith("java.net"))),
      Problem(
        "java.io",
        "java.io API is synchronous and blocks a thread. Make sure to run (potentially) blocking IO operations in a dedicated IO dispatcher.",
        None,
        topFrameIs(classStartsWith("java.io"))))

    def topFrameIs(topCondition: StackTraceElement => Boolean): StackTraceFilter = _.headOption.exists(topCondition)
    def anyFrameIs(frameCondition: StackTraceElement => Boolean): StackTraceFilter = _.exists(frameCondition)
    def classStartsWith(name: String): StackTraceElement => Boolean = _.getClassName.startsWith(name)
    def classMethod(name: String): StackTraceElement => Boolean = {
      val i = name.lastIndexOf('.')
      val className = name.take(i)
      val methodName = name.drop(i + 1)
      frame => frame.getClassName == className && frame.getMethodName == methodName
    }
    implicit class LiftBoolOperator[T](val f: T => Boolean) extends AnyVal {
      def &&(other: T => Boolean): T => Boolean = t => f(t) && other(t)
      def ||(other: T => Boolean): T => Boolean = t => f(t) || other(t)
    }
  }

  private lazy val getDispatcherES: Dispatcher => ExecutorServiceDelegate = {
    val m = classOf[Dispatcher].getDeclaredMethod("executorService")
    m.setAccessible(true)

    d => m.invoke(d).asInstanceOf[ExecutorServiceDelegate]
  }
  private lazy val getAkkaFJPFactory: AkkaForkJoinPool => MonitorableThreadFactory = {
    val f = classOf[AkkaForkJoinPool].getSuperclass.getDeclaredField("factory")
    f.setAccessible(true)

    fjp => f.get(fjp).asInstanceOf[MonitorableThreadFactory]
  }
  private lazy val getThreadPoolExecutorFactory: ThreadPoolExecutor => MonitorableThreadFactory = {
    val f = classOf[ThreadPoolExecutor].getDeclaredField("threadFactory")
    f.setAccessible(true)

    tpe => f.get(tpe).asInstanceOf[MonitorableThreadFactory]
  }

  private lazy val getAffinityPoolFactory: AffinityPool => MonitorableThreadFactory = {
    val threadFactoryField =
      classOf[AffinityPool].getDeclaredField("akka$dispatch$affinity$AffinityPool$$threadFactory")
    threadFactoryField.setAccessible(true)

    ap => threadFactoryField.get(ap).asInstanceOf[MonitorableThreadFactory]
  }
}

private final case class InaccessibleObjectException(msg: String, ex: Throwable) extends RuntimeException(msg, ex)
