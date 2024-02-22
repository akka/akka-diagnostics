/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

import akka.diagnostics.StarvationDetector.StarvationDetectorThread
import akka.dispatch.Dispatchers
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.testkit.EventFilter

class StarvationDetectorSpec extends AkkaSpec(s"""
      akka.diagnostics.starvation-detector.check-interval              = 100ms # check more often
      akka.diagnostics.starvation-detector.initial-delay               = 0     # no initial delay
      akka.diagnostics.starvation-detector.max-delay-warning-threshold = 70ms  # make check tighter
      akka.diagnostics.starvation-detector.warning-interval            = 20s   # must be longer than one antipattern test, so it is reported only once
      akka.actor.default-dispatcher {
        fork-join-executor {
          parallelism-min = ${StarvationDetectorSpec.numThreads}
          parallelism-max = ${StarvationDetectorSpec.numThreads}
        }
      }
      akka.actor.internal-dispatcher {
        fork-join-executor {
          parallelism-min = ${StarvationDetectorSpec.numThreads}
          parallelism-max = ${StarvationDetectorSpec.numThreads}
        }
      }
      custom-fjp-dispatcher {
        type = Dispatcher
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = ${StarvationDetectorSpec.numThreads}
          parallelism-max = ${StarvationDetectorSpec.numThreads}
        }
      }
      custom-affinity-dispatcher {
        type = Dispatcher
        executor = "affinity-pool-executor"
        affinity-pool-executor {
          parallelism-min = ${StarvationDetectorSpec.numThreads}
          parallelism-max = ${StarvationDetectorSpec.numThreads}
        }
      }
      custom-threadpool-dispatcher {
        type = Dispatcher
        executor = "thread-pool-executor"
        thread-pool-executor {
          fixed-pool-size = ${StarvationDetectorSpec.numThreads}
        }
      }
    """) {
  import akka.diagnostics.StarvationDetectorSpec._

  "The StarvationDetector" should {
    def testsExecutor(dispatcherId: String): Unit = s"support $dispatcherId" should {
      implicit val dispatcher: ExecutionContext = system.dispatchers.lookup(dispatcherId)
      // default dispatcher is already checked out of the box
      if (dispatcherId != DefaultDispatcherId && dispatcherId != InternalDispatcherId) {
        StarvationDetector.checkExecutionContext(
          dispatcher,
          system.log,
          StarvationDetectorSettings.fromConfig(
            system.settings.config.getConfig("akka.diagnostics.starvation-detector")),
          () => system.whenTerminated.isCompleted)
      }
      "log a warning if the dispatcher is busy for long periods of time" should {
        // Excluding CompletableFuture.get when running with FJP because it creates new threads (without bounds)
        // for that blocking operation. CompletableFuture.get is still an anti-pattern, but not something the
        // TSD can find. Tried to set lower bounds with system property
        // `java.util.concurrent.ForkJoinPool.common.maximumSpares` but that didn't help

        AntiPatterns
          .filterNot(p =>
            p.name == "CompletableFuture.get" && (dispatcherId == DefaultDispatcherId || dispatcherId == InternalDispatcherId || dispatcherId
              .contains("fjp")))
          .foreach { case AntiPattern(name, expected, block) =>
            name in {
              def runOne(i: Int, remaining: Int): Future[Unit] =
                if (remaining > 0)
                  Future {
                    Try(block()) // not interested in the error, just showing the anti-pattern
                    ()
                  }.flatMap(_ => runOne(i, remaining - 1))
                else Future.successful(())

              resetWarningInterval()

              val pattern = s"(?s)Exceedingly long scheduling time on ExecutionContext.*\\Q$expected\\E.*".r
              EventFilter
                .custom(
                  { case Logging.Warning(_, _, message: String) =>
                    if (pattern.findFirstIn(message).isEmpty) {
                      println(s"Unexpected warning: \n$message")
                      false
                    } else if (message.contains("total 0 thread")) {
                      println(s"Detector logged warning but it does not contain any thread stacks:\n$message")
                      false
                    } else true

                  },
                  occurrences = 1)
                .intercept {
                  val numIterations =
                    2 // 5 * numThreads  iterations * 2 tasks * 100ms / numThreads = 2 seconds run time

                  val result = Future.traverse(1 to (numThreads * 5))(runOne(_, numIterations))
                  Await.result(result, 20.seconds)
                }
            }
          }
      }
      "not log a warning if the dispatcher is busy for an amount of small non-blocking tasks" in {
        // Ignore because it's flaky in CI
        if (Runtime.getRuntime.availableProcessors <= 2)
          pending

        // fails consistently locally and on CI https://github.com/akka/akka-diagnostics/issues/63
        if (dispatcherId == "custom-affinity-dispatcher")
          pending

        Thread.sleep(1000)
        resetWarningInterval()

        var t0 = System.nanoTime()
        def runThunks(remaining: Int): Future[Unit] = {
          if (remaining > 0)
            Future {
              ()
            }.flatMap(_ => runThunks(remaining - 1))
          else {
            Future.successful(())
          }
        }

        EventFilter.warning(start = "Exceedingly long scheduling time on ExecutionContext", occurrences = 0).intercept {
          var numIterations = 10000

          val parallelism = math.max(1, math.min(numThreads, Runtime.getRuntime.availableProcessors) - 1)
          (1 to 5).foreach { _ =>
            t0 = System.nanoTime()
            val result = Future.sequence((1 to parallelism).map(_ => runThunks(numIterations)))
            Await.result(result, 10.seconds)
            val durationMillis = (System.nanoTime() - t0) / 1000 / 1000
            if (durationMillis < 100)
              numIterations = numIterations * 10
          }
        }
      }
    }

    testsExecutor(DefaultDispatcherId)
    testsExecutor(InternalDispatcherId)
    testsExecutor("custom-fjp-dispatcher")
    testsExecutor("custom-threadpool-dispatcher")
    testsExecutor("custom-affinity-dispatcher")
  }

  // HACK to make sure that next test will not run into warning silence
  private def resetWarningInterval(): Unit = {
    // clear any leftover log messages
    EventFilter.warning().intercept {
      Thread.sleep(1000)
    }

    val threads = new Array[Thread](10000)
    val res = Thread.enumerate(threads)
    val nextWarningAfterNanos = System.nanoTime() - 1
    threads
      .take(res)
      .collect { case t: StarvationDetectorThread =>
        t
      }
      .foreach(_.nextWarningAfterNanos = nextWarningAfterNanos)
  }

  val OtherEC = ExecutionContexts.fromExecutor(Executors.newCachedThreadPool())

  case class AntiPattern(name: String, expectedIssueDescription: String, block: () => Unit)
  def antiPattern(name: String, expectedIssueDescription: String)(block: => Unit): AntiPattern =
    AntiPattern(name, expectedIssueDescription, () => block)

  private val fileSize = new AtomicInteger(1000000)

  // A collection of blocking AntiPatterns to test, each should take ~ 100ms
  lazy val AntiPatterns: Seq[AntiPattern] = Seq(
    antiPattern("Thread.sleep", "Thread.sleep blocks a thread") {
      Thread.sleep(100)
    },
    // Await currently mostly works because it uses the blocking context (it might spawn excessive amounts of extra threads, though)
    // antiPattern("Await", "Await.ready / Await.result blocks a thread")(Try(Await.ready(Promise().future, 100.millis))),
    antiPattern("Socket connect", "java.net API is synchronous and blocks a thread") {
      new Socket()
        .connect(new InetSocketAddress("www.google.com", 81), ThreadLocalRandom.current().nextInt(100, 150))
    },
    antiPattern("Socket read", "java.net API is synchronous and blocks a thread") {
      val s = new Socket()
      try {
        s.setSoTimeout(newTimeOut())
        s.connect(new InetSocketAddress("www.google.com", 80))
        s.getInputStream.read()
      } finally s.close()
    },
    antiPattern("FileOutputStream.write", "java.io API is synchronous") {
      val t0 = System.nanoTime()
      val tmp = File.createTempFile("bigfile", "txt")
      tmp.deleteOnExit()

      val size = fileSize.get
      val b = new Array[Byte](size)
      val fos = new FileOutputStream(tmp)

      try fos.write(b)
      finally {
        fos.close()
        tmp.delete()
        val durationMs = (System.nanoTime() - t0) / 1000 / 1000
        if (durationMs < 100)
          // tuning depending on how fast your disk is
          // Note: with a fast SSD this requires _a lot_ of memory
          fileSize.compareAndSet(size, size * 2)
      }
    },
    antiPattern("CompletableFuture.get", "CompletableFuture.get blocks a thread.") {
      val f = new CompletableFuture

      Try(f.get(150, TimeUnit.MILLISECONDS))
    })

  private def newTimeOut(): Int = ThreadLocalRandom.current().nextInt(80, 120)
}
object StarvationDetectorSpec {
  val numThreads = 4
  val DefaultDispatcherId = Dispatchers.DefaultDispatcherId
  val InternalDispatcherId = "akka.actor.internal-dispatcher"
}
