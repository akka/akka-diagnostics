/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.diagnostics

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.util.Try
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.testkit.EventFilter
import akka.diagnostics.ConfigChecker.ConfigWarning

import scala.annotation.nowarn

object ConfigCheckerSpec {
  val conf = ConfigFactory.parseString("""
      akka.diagnostics.recorder.enabled = off
      """)
}

class ConfigCheckerSpec extends AkkaSpec(ConfigCheckerSpec.conf) {
  val reference = ConfigFactory.defaultReference()

  val defaultRemote = ConfigFactory
    .parseString(s"""
    akka.actor.provider = akka.remote.RemoteActorRefProvider
    # otherwise it will warn about undefined hostname
    akka.remote.artery.canonical.hostname = 127.0.0.1
    akka.remote.artery.tcp.hostname = 127.0.0.1
    akka.remote.artery.ssl.hostname = 127.0.0.1
    akka.remote.watch-failure-detector.heartbeat-interval = 1 s
    """)
    .withFallback(reference)

  val defaultCluster = ConfigFactory
    .parseString(s"""
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    # otherwise it will warn about undefined hostname
    akka.remote.artery.canonical.hostname = 127.0.0.1
    akka.remote.artery.tcp.hostname = 127.0.0.1
    akka.remote.artery.ssl.hostname = 127.0.0.1
    """)
    .withFallback(reference)

  def extSys = system.asInstanceOf[ExtendedActorSystem]

  // verify that it can be disabled
  def assertDisabled(c: Config, checkerKeys: String*): Unit = {
    val allDisabledCheckerKeys = c.getStringList("akka.diagnostics.checker.disabled-checks").asScala ++ checkerKeys
    val disabled = ConfigFactory
      .parseString(s"""
        akka.diagnostics.checker.disabled-checks = [${allDisabledCheckerKeys.mkString(",")}]
      """)
      .withFallback(c)
    new ConfigChecker(extSys, disabled, reference).check.warnings should ===(Nil)
  }

  def assertCheckerKey(warnings: immutable.Seq[ConfigWarning], expectedCheckerKeys: String*): Unit =
    warnings.map(_.checkerKey).toSet should ===(expectedCheckerKeys.toSet)

  def assertPath(warnings: immutable.Seq[ConfigWarning], expectedPaths: String*): Unit =
    warnings.flatMap(_.paths).toSet should ===(expectedPaths.toSet)

  // for copy paste into config-checkers.rst
  def printDocWarnings(warnings: immutable.Seq[ConfigWarning]): Unit = {
    if (true) // change to true when updating documentation, or debugging
      warnings.foreach { w =>
        val msg = s"| ${ConfigChecker.format(w)} |"
        val line = Vector.fill(msg.length - 2)("-").mkString("+", "", "+")
        println(line)
        println(msg)
        println(line)
      }

  }

  "The ConfigChecker" must {

    "find no warnings in akka-actor default configuration" in {
      val checker = new ConfigChecker(extSys, reference, reference)
      checker.check().warnings should be(Nil)
    }

    "find power user settings" in {
      val c = ConfigFactory
        .parseString("""
        akka.version = 0.1
        akka.actor.router.type-mapping.from-code = "Changed"
        akka.actor.router.type-mapping.added = "Added"
        akka.actor.router.type-mapping.added-ok = "AddedOk"
        akka.actor.unstarted-push-timeout = 1s

        myapp.something = 17

        akka.diagnostics.checker {
          confirmed-power-user-settings = [
            akka.actor.unstarted-push-timeout,
            akka.actor.router.type-mapping.added-ok
          ]

          disabled-checks = [typo]
        }
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)
      checker.isModifiedPowerUserSetting("akka.version") should ===(true)
      checker.isModifiedPowerUserSetting("akka.loglevel") should ===(false)
      checker.isModifiedPowerUserSetting("myapp.something") should ===(false)
      // akka.daemonic is configured as power-user-settings, but it is not changed
      checker.isModifiedPowerUserSetting("akka.daemonic") should ===(false)

      checker.isModifiedPowerUserSetting("akka.actor.router.type-mapping.from-code") should ===(true)
      checker.isModifiedPowerUserSetting("akka.actor.router.type-mapping.round-robin-pool") should ===(false)
      checker.isModifiedPowerUserSetting("akka.actor.router.type-mapping.added") should ===(true)
      // added-ok is configured in power-user-settings-disabled
      checker.isModifiedPowerUserSetting("akka.actor.router.type-mapping.added-ok") should ===(false)

      // akka.actor.unstarted-push-timeout is configured as power-user-settings, but disabled
      checker.isModifiedPowerUserSetting("akka.actor.unstarted-push-timeout") should ===(false)

      val warnings = checker.check().warnings
      assertCheckerKey(warnings, "power-user-settings")
      assertPath(
        warnings,
        "akka.version",
        "akka.actor.router.type-mapping.from-code",
        "akka.actor.router.type-mapping.added")

      assertDisabled(c, "power-user-settings", "typo")
    }

    "warn for jackson serialization if older than 2.6" in {
      val c = ConfigFactory
        .parseString("akka.serialization.jackson.type-in-manifest=off")
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      warnings.shouldBe(Seq.empty[ConfigWarning])
    }

    "find typos and misplacements" in {
      val c = ConfigFactory
        .parseString("""
        akka.loglevel = DEBUG # ok
        akka.log-level = INFO # typo
        akka.actor.loglevel = WARNING # misplacement
        akka.actor.serialize-messages = on # ok
        akka.actor.deployment {
          /parent/router1 {
            router = round-robin-pool
            nr-of-instances = 5
          }
          /parent/router2 {
            router = round-robin-pool
            number-of-instances = 5 # typo
          }
          foo = "bar"
        }
        my-dispatcher {
          throowput = 10
          fork-join-executor.parallelism-min = 16
          fork-join-executor.parallelism-max = 16
        }
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      assertCheckerKey(warnings, "typo")
      assertPath(
        warnings,
        "akka.log-level",
        "akka.actor.loglevel",
        """akka.actor.deployment."/parent/router2".number-of-instances""",
        "my-dispatcher.throowput",
        "akka.actor.deployment.foo")

      assertDisabled(c, "typo")
    }

    "not warn about typos in some sections" in {
      val c = ConfigFactory
        .parseString("""
        akka.actor {
          serializers {
            test = "akka.serialization.JavaSerializer"
          }
          serialization-bindings {
            "java.util.Date" = test
          }
          deployment."/foo".pool-dispatcher.fork-join-executor.parallelism-max = 10
        }
        akka.cluster.role.backend.min-nr-of-members = 3
        akka.test.alright = 17

        akka.diagnostics.checker.confirmed-typos = ["akka.test.alright"]
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)
      checker.check().warnings should be(Nil)
    }

    "find unsupported provider" in {
      val c = ConfigFactory
        .parseString("""
        akka.actor.provider = some.Other
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)
      val warnings = checker.check().warnings
      printDocWarnings(warnings)
      assertCheckerKey(warnings, "actor-ref-provider")
      assertPath(warnings, "akka.actor.provider")

      assertDisabled(c, "actor-ref-provider")
    }

    "find disabled jvm exit" in {
      val c = ConfigFactory
        .parseString("""
        akka.jvm-exit-on-fatal-error = off
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      printDocWarnings(warnings)
      assertCheckerKey(warnings, "jvm-exit-on-fatal-error")
      assertPath(warnings, "akka.jvm-exit-on-fatal-error")

      assertDisabled(c, "jvm-exit-on-fatal-error")
    }

    "find default-dispatcher size issues" in {
      val c = ConfigFactory
        .parseString("""
        akka.actor.default-dispatcher = {
          fork-join-executor.parallelism-min = 512
          fork-join-executor.parallelism-max = 512
        }
        akka.diagnostics.checker.disabled-checks = ["fork-join-pool-size"]
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      assertPath(warnings, "akka.actor.default-dispatcher")
      assertCheckerKey(warnings, "default-dispatcher-size")

      assertDisabled(c, "default-dispatcher-size")
    }

    "find default-dispatcher type issues" in {
      val c = ConfigFactory
        .parseString("""
        akka.actor.default-dispatcher = {
          type = PinnedDispatcher
          executor = thread-pool-executor
        }
        akka.diagnostics.checker.disabled-checks = ["default-dispatcher-size"]
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      assertCheckerKey(warnings, "default-dispatcher-type")
      assertPath(warnings, "akka.actor.default-dispatcher")

      assertDisabled(c, "default-dispatcher-type")
    }

    "find default-dispatcher throughput issues" in {
      val c = ConfigFactory
        .parseString("""
        akka.actor.default-dispatcher = {
          throughput = 200
          # expected
          # throughput-deadline-time = 1s
        }
      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      assertCheckerKey(warnings, "dispatcher-throughput")
      assertPath(
        warnings,
        "akka.actor.default-dispatcher.throughput",
        "akka.actor.default-dispatcher.throughput-deadline-time")

      assertDisabled(c, "dispatcher-throughput")
    }

    "find dispatchers" in {
      val c = ConfigFactory
        .parseString("""
        disp1 = {
          type = Dispatcher
        }
        myapp {
          disp2 {
            type = PinnedDispatcher
          }
          disp3 {
            executor = thread-pool-executor
          }
          disp4 {
            fork-join-executor.parallelism-min = 16
            fork-join-executor.parallelism-max = 16
          }
          disp4 {
            executor = thread-pool-executor
            thread-pool-executor.core-pool-size-min = 16
            thread-pool-executor.core-pool-size-max = 16
          }
          disp5 = {
            throughput = 100
            executor = thread-pool-executor
          }
        }

      """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val result = checker.findDispatchers()
      val keys = result.keySet
      keys should contain("disp1")
      keys should contain("myapp.disp2")
      keys should contain("myapp.disp3")
      keys should contain("myapp.disp4")
      keys should contain("myapp.disp5")
    }

    "find dispatcher issues" in {
      val c = ConfigFactory
        .parseString("""
          disp1 = {
            throughput = 200
            executor = thread-pool-executor
            # expected
            # throughput-deadline-time = 1s
          }
          ok-disp2 = {
            throughput = 200
            executor = thread-pool-executor
            # expected
            throughput-deadline-time = 1s
          }
          disp3 = {
            fork-join-executor.parallelism-min = 256
            fork-join-executor.parallelism-max = 256
          }
          ok-disp4 = {
            executor = thread-pool-executor
            thread-pool-executor.core-pool-size-min = 256
            thread-pool-executor.core-pool-size-max = 256
            fork-join-executor.parallelism-min = 256
            fork-join-executor.parallelism-max = 256
          }
          ok-disp5 = {
            fork-join-executor.parallelism-min = 4
            fork-join-executor.parallelism-max = 8
          }
          ok-disp6 {
            type = PinnedDispatcher
            executor = thread-pool-executor
            thread-pool-executor.allow-core-timeout = off
          }
        """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      assertCheckerKey(warnings, "dispatcher-throughput", "fork-join-pool-size", "dispatcher-total-size")
      warnings.find(_.pathsAsString == "disp1.throughput, disp1.throughput-deadline-time").get.checkerKey should be(
        "dispatcher-throughput")
      warnings.find(_.pathsAsString == "disp3").get.checkerKey should be("fork-join-pool-size")

      assertDisabled(c, "dispatcher-throughput", "fork-join-pool-size", "dispatcher-total-size")
    }

    "find too many dispatchers" in {
      val c = (1 to 11)
        .map(n => ConfigFactory.parseString(s"""
          disp-$n = {
            fork-join-executor.parallelism-min = 2
            fork-join-executor.parallelism-max = 2
          }"""))
        .reduce(_ withFallback _)
        .withFallback(reference)

      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      printDocWarnings(warnings)
      assertCheckerKey(warnings, "dispatcher-count")
      val paths = warnings.flatMap(_.paths).toSet
      paths should be((1 to 11).map("disp-" + _).toSet)

      assertDisabled(c, "dispatcher-count")
    }

    "find too many total dispatcher threads" in {
      val c = ConfigFactory
        .parseString("""
          disp1 = {
            fork-join-executor.parallelism-min = 50
            fork-join-executor.parallelism-max = 50
          }
          disp2 = {
            executor = thread-pool-executor
            thread-pool-executor.core-pool-size-min = 550
            thread-pool-executor.core-pool-size-max = 550
          }
          disp3 = {
            executor = thread-pool-executor
            thread-pool-executor.core-pool-size-min = 400
            thread-pool-executor.core-pool-size-max = 400
          }
        """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      printDocWarnings(warnings)
      assertCheckerKey(warnings, "dispatcher-total-size")
      val paths = warnings.flatMap(_.paths).toSet
      paths should contain("disp1")
      paths should contain("disp2")
      paths should contain("disp3")
      paths should not contain "akka.actor.default-dispatcher"

      assertDisabled(c, "dispatcher-total-size")
    }

//    if (!isAkka26)
//      "recommend against default-dispatcher as remote dispatcher" in {
//        val c = ConfigFactory.parseString(
//          """
//          akka.remote.use-dispatcher = "akka.actor.default-dispatcher"
//        """).withFallback(defaultRemote)
//        val checker = new ConfigChecker(extSys, c, reference)
//
//        val warnings = checker.check().warnings
//        printDocWarnings(warnings)
//        assertCheckerKey(warnings, "remote-dispatcher")
//        assertPath(warnings, "akka.remote.use-dispatcher")
//
//        assertDisabled(c, "remote-dispatcher")
//      }

//    if (!isAkka26)
//      "warn about secure cookie" in {
//        val c = ConfigFactory.parseString(
//          """
//        akka.remote.require-cookie = on
//        akka.remote.secure-cookie = abc
//      """).withFallback(defaultRemote)
//        val checker = new ConfigChecker(extSys, c, reference)
//
//        val warnings = checker.check().warnings
//        printDocWarnings(warnings)
//        assertCheckerKey(warnings, "secure-cookie")
//        assertPath(warnings, "akka.remote.require-cookie", "akka.remote.secure-cookie")
//
//        assertDisabled(c, "secure-cookie")
//      }

//    if (!isAkka26)
//      "find suspect transport failure detector" in {
//        val configStrings = List(
//          "akka.remote.transport-failure-detector.heartbeat-interval = 100ms",
//          "akka.remote.transport-failure-detector.heartbeat-interval = 40s",
//          "akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 10s",
//          "akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 5 minutes",
//          """akka.remote.transport-failure-detector.heartbeat-interval = 10s
//          akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 20s""")
//
//        val configs = configStrings.map(c => ConfigFactory.parseString(c)
//          .withFallback(ConfigFactory.parseString(
//            """akka.diagnostics.checker.confirmed-power-user-settings = ["akka.remote.transport-failure-detector.*"]"""))
//          .withFallback(defaultRemote))
//        configs.zipWithIndex.foreach {
//          case (c, i) =>
//            withClue(s"problem with config #${i + 1}") {
//              val checker = new ConfigChecker(extSys, c, reference)
//              val warnings = checker.check().warnings
//              assertCheckerKey(warnings, "transport-failure-detector")
//              assertDisabled(c, "transport-failure-detector")
//            }
//        }
//      }

    "find suspect remote watch failure detector" in {
      val configStrings = List(
        "akka.remote.watch-failure-detector.heartbeat-interval = 100ms",
        "akka.remote.watch-failure-detector.heartbeat-interval = 20s",
        "akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 3s",
        "akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 2 minutes",
        """akka.remote.watch-failure-detector.heartbeat-interval = 10s
          akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 20s""")

      val configs = configStrings.map(c =>
        ConfigFactory
          .parseString(c)
          .withFallback(
            ConfigFactory.parseString("""akka.diagnostics.checker.confirmed-power-user-settings =
            ["akka.remote.watch-failure-detector.unreachable-nodes-reaper-interval"]""")
          ) //TODO Q why is this needed if we are not setting it?
          .withFallback(defaultRemote))
      configs.zipWithIndex.foreach { case (c, i) =>
        withClue(s"problem with config #${i + 1}") {
          val checker = new ConfigChecker(extSys, c, reference)
          val warnings = checker.check().warnings
          printDocWarnings(warnings)
          assertCheckerKey(warnings, "remote-watch-failure-detector")
          assertDisabled(c, "remote-watch-failure-detector")
        }
      }
    }

//    if (!isAkka26)
//      "find suspect retry gate" in {
//        val configStrings = List(
//          "akka.remote.retry-gate-closed-for = 100ms",
//          "akka.remote.retry-gate-closed-for = 11s")
//
//        val configs = configStrings.map(c => ConfigFactory.parseString(c).withFallback(defaultRemote))
//        configs.zipWithIndex.foreach {
//          case (c, i) =>
//            withClue(s"problem with config #${i + 1}") {
//              val checker = new ConfigChecker(extSys, c, reference)
//              val warnings = checker.check().warnings
//              printDocWarnings(warnings)
//              assertCheckerKey(warnings, "retry-gate-closed-for")
//              assertPath(warnings, "akka.remote.retry-gate-closed-for")
//              assertDisabled(c, "retry-gate-closed-for")
//            }
//        }
//      }

//    if (!isAkka26)
//      "yell about quarantine pruning" in {
//        val c = ConfigFactory.parseString(
//          """
//          akka.remote.prune-quarantine-marker-after = 3600s
//        """).withFallback(defaultRemote)
//        val checker = new ConfigChecker(extSys, c, reference)
//
//        val warnings = checker.check().warnings
//        printDocWarnings(warnings)
//        assertCheckerKey(warnings, "prune-quarantine-marker-after")
//        assertPath(warnings, "akka.remote.prune-quarantine-marker-after")
//
//        assertDisabled(c, "prune-quarantine-marker-after")
//      }

//    if (!isAkka26)
//      "find suspect transports" in {
//        val configStrings = List(
//          "akka.remote.enabled-transports = [akka.remote.netty.udp]",
//          "akka.remote.enabled-transports = [other]")
//        val configs = configStrings.map(c => ConfigFactory.parseString(c).withFallback(defaultRemote))
//        configs.zipWithIndex.foreach {
//          case (c, i) =>
//            withClue(s"problem with config #${i + 1}") {
//              val checker = new ConfigChecker(extSys, c, reference)
//              val warnings = checker.check().warnings
//              printDocWarnings(warnings)
//              assertCheckerKey(warnings, "enabled-transports")
//              assertPath(warnings, "akka.remote.enabled-transports")
//              assertDisabled(c, "enabled-transports")
//            }
//        }
//
//        val c2 = ConfigFactory.parseString("akka.remote.enabled-transports = [akka.remote.netty.tcp]").withFallback(defaultRemote)
//        new ConfigChecker(extSys, c2, reference).check().warnings should be(Nil)
//
//        val c3 = ConfigFactory.parseString("akka.remote.enabled-transports = [akka.remote.netty.ssl]").withFallback(defaultRemote)
//        new ConfigChecker(extSys, c3, reference).check().warnings should be(Nil)
//      }

//    if (!isAkka26)
//      "warn about undefined hostname" in {
//        val c1 = ConfigFactory.parseString("akka.actor.provider = akka.remote.RemoteActorRefProvider")
//          .withFallback(reference)
//        val checker1 = new ConfigChecker(extSys, c1, reference)
//        val warnings1 = checker1.check().warnings
//        printDocWarnings(warnings1)
//        assertCheckerKey(warnings1, "hostname")
//        assertPath(warnings1, "akka.remote.netty.tcp.hostname")
//        assertDisabled(c1, "hostname")
//
//        val c2 = ConfigFactory.parseString(
//          """
//        akka.actor.provider = akka.remote.RemoteActorRefProvider
//        akka.remote.enabled-transports = [akka.remote.netty.ssl]""")
//          .withFallback(reference)
//        val checker2 = new ConfigChecker(extSys, c2, reference)
//        val warnings2 = checker2.check().warnings
//        assertCheckerKey(warnings2, "hostname")
//        assertPath(warnings2, "akka.remote.netty.ssl.hostname")
//        assertDisabled(c2, "hostname")
//
//        val c3 = ConfigFactory.parseString(
//          """
//        akka.actor.provider = akka.remote.RemoteActorRefProvider
//        akka.remote.netty.tcp.hostname = 127.0.0.1""").withFallback(reference)
//        new ConfigChecker(extSys, c3, reference).check().warnings should be(Nil)
//
//        val c4 = ConfigFactory.parseString(
//          """
//        akka.actor.provider = akka.remote.RemoteActorRefProvider
//        akka.remote.enabled-transports = [akka.remote.netty.ssl]
//        akka.remote.netty.ssl.hostname = 127.0.0.1
//        """).withFallback(reference)
//        new ConfigChecker(extSys, c4, reference).check().warnings should be(Nil)
//      }

//    if (!isAkka26)
//      "warn about large messages" in {
//        val c = ConfigFactory.parseString(
//          """
//          akka.remote.netty.tcp.maximum-frame-size = 2MiB
//        """).withFallback(defaultRemote)
//        val checker = new ConfigChecker(extSys, c, defaultRemote)
//
//        val warnings = checker.check().warnings
//        printDocWarnings(warnings)
//        assertCheckerKey(warnings, "maximum-frame-size")
//        assertPath(warnings, "akka.remote.netty.tcp.maximum-frame-size")
//        assertDisabled(c, "maximum-frame-size")
//
//        val c2 = ConfigFactory.parseString("akka.remote.netty.tcp.maximum-frame-size = 1MiB")
//          .withFallback(defaultRemote)
//        new ConfigChecker(extSys, c2, reference).check().warnings should be(Nil)
//      }

    "find default-remote-dispatcher-size size issues" in {
      val c = ConfigFactory
        .parseString("""
          akka.remote.default-remote-dispatcher = {
            fork-join-executor.parallelism-min = 1
            fork-join-executor.parallelism-max = 1
          }
        """)
        .withFallback(defaultRemote)
      val checker = new ConfigChecker(extSys, c, reference)

      val warnings = checker.check().warnings
      printDocWarnings(warnings)
      assertPath(warnings, "akka.remote.default-remote-dispatcher")
      assertCheckerKey(warnings, "default-remote-dispatcher-size")
      assertDisabled(c, "default-remote-dispatcher-size")
    }

//    if (!isAkka26)
//      "recommend SBR instead of auto-down-unreachable-after" in {
//        val c = ConfigFactory.parseString(
//          """
//          akka.cluster.auto-down-unreachable-after = 10s
//        """).withFallback(defaultCluster)
//        val checker = new ConfigChecker(extSys, c, reference)
//
//        val warnings = checker.check().warnings
//        printDocWarnings(warnings)
//        assertCheckerKey(warnings, "auto-down")
//        assertPath(warnings, "akka.cluster.auto-down-unreachable-after")
//
//        assertDisabled(c, "auto-down")
//      }

    "recommend against dedicated cluster dispatcher" in {
      val c = ConfigFactory
        .parseString("""
          akka.cluster.use-dispatcher = disp1
          disp1 = {
            fork-join-executor.parallelism-min = 6
            fork-join-executor.parallelism-max = 6
          }
        """)
        .withFallback(defaultCluster)
      val checker = new ConfigChecker(extSys, c, reference)
      val warnings = checker.check().warnings
      printDocWarnings(warnings)
      assertCheckerKey(warnings, "cluster-dispatcher")
      assertPath(warnings, "akka.cluster.use-dispatcher")
      assertDisabled(c, "cluster-dispatcher")
    }

    "warn about too small cluster dispatcher" in {
      val c = ConfigFactory
        .parseString("""
          akka.cluster.use-dispatcher = disp1
          disp1 = {
            fork-join-executor.parallelism-min = 1
            fork-join-executor.parallelism-max = 1
          }
        """)
        .withFallback(defaultCluster)
      val checker = new ConfigChecker(extSys, c, reference)
      val warnings = checker.check().warnings
      assertCheckerKey(warnings, "cluster-dispatcher")
      assertPath(warnings, "akka.cluster.use-dispatcher", "disp1")
      assertDisabled(c, "cluster-dispatcher")
    }

    "find suspect cluster failure detector" in {
      val configStrings = List(
        "akka.cluster.failure-detector.heartbeat-interval = 100ms",
        "akka.cluster.failure-detector.heartbeat-interval = 20s",
        "akka.cluster.failure-detector.acceptable-heartbeat-pause = 2s",
        "akka.cluster.failure-detector.acceptable-heartbeat-pause = 2 minutes",
        """akka.cluster.failure-detector.heartbeat-interval = 10s
          akka.cluster.failure-detector.acceptable-heartbeat-pause = 20s""")

      val configs = configStrings.map(c =>
        ConfigFactory
          .parseString(c)
          .withFallback(ConfigFactory.parseString("""akka.diagnostics.checker.confirmed-power-user-settings =
            ["akka.cluster.unreachable-nodes-reaper-interval"]"""))
          .withFallback(defaultCluster))
      configs.zipWithIndex.foreach { case (c, i) =>
        withClue(s"problem with config #${i + 1}") {
          val checker = new ConfigChecker(extSys, c, reference)
          val warnings = checker.check().warnings
          assertCheckerKey(warnings, "cluster-failure-detector")
          assertDisabled(c, "cluster-failure-detector")
        }
      }
    }

    "find suspect SBR configuration" in {
      val configStrings = List(
        """akka.cluster.down-removal-margin = 3s
          akka.cluster.split-brain-resolver.stable-after = 3s
          akka.cluster.split-brain-resolver.active-strategy = keep-majority""",
        """akka.cluster.down-removal-margin = 10s
          akka.cluster.split-brain-resolver.active-strategy = keep-majority
          # should also configure
          #akka.cluster.split-brain-resolver.stable-after = 10s""",
        """akka.cluster.down-removal-margin = 10s
          akka.cluster.split-brain-resolver.stable-after = 15s
          akka.cluster.split-brain-resolver.active-strategy = keep-majority""",
        s"""
          akka.cluster.auto-down-unreachable-after = 10s
          akka.cluster.split-brain-resolver.active-strategy = keep-majority
          akka.diagnostics.checker.disabled-checks = "[auto-down, typo]" "}
          """)

      val configs = configStrings.map(c =>
        ConfigFactory
          .parseString(c)
          .withFallback(defaultCluster))
      configs.zipWithIndex.foreach { case (c, i) =>
        withClue(s"problem with config #${i + 1}") {
          val checker = new ConfigChecker(extSys, c, reference)
          val warnings = checker.check().warnings
          printDocWarnings(warnings)
          assertCheckerKey(warnings, "split-brain-resolver")
          assertDisabled(c, "split-brain-resolver")
        }
      }
    }

    "not check SBR if reference.conf doesn't contain split-brain-resolver section" in {
      // akka-split-brain-resolver is in classpath and in this test we want to simulate that it wasn't
      val referenceWithoutSbr = ConfigFactory.defaultReference().withoutPath("akka.cluster.split-brain-resolver")
      val clusterWithoutSbr = defaultCluster.withoutPath("akka.cluster.split-brain-resolver")

      val checker = new ConfigChecker(extSys, clusterWithoutSbr, referenceWithoutSbr)
      val warnings = checker.check().warnings
      warnings should ===(Nil)
    }

    "not check SBR if reference.conf doesn't contain split-brain-resolver section, even if included in application.conf" in {
      // akka-split-brain-resolver is in classpath and in this test we want to simulate that it wasn't
      val referenceWithoutSbr = ConfigFactory.defaultReference().withoutPath("akka.cluster.split-brain-resolver")
      val clusterWithoutSbr = defaultCluster.withoutPath("akka.cluster.split-brain-resolver")
      val c = ConfigFactory
        .parseString("""
        akka.cluster.split-brain-resolver.active-strategy = keep-majority
        akka.cluster.down-removal-margin = 1s
        """)
        .withFallback(clusterWithoutSbr)

      val checker = new ConfigChecker(extSys, c, referenceWithoutSbr)
      val warnings = checker.check().warnings
      // it's reported as a typo since not included in reference.conf (as expected)
      assertCheckerKey(warnings, "typo")
    }

    "log warning when ActorSystem startup" in {
      val c = ConfigFactory
        .parseString("""
          akka.log-level = INFO # typo
          akka.diagnostics.checker.async-check-after = 200ms
        """)
        .withFallback(system.settings.config)

      val logSource = classOf[ConfigChecker].getName
      // the logging is performed async after 200ms, and therfore we can intercept the log like this
      val sys2 = ActorSystem(system.name + "-2", c)
      try {
        EventFilter.warning(start = "Lightbend recommendation", source = logSource, occurrences = 1).intercept {}(sys2)
      } finally {
        shutdown(sys2)
      }
    }

    "fail startup if configured to fail" in {
      val c = ConfigFactory
        .parseString("""
          akka.log-level = INFO # typo
          akka.diagnostics.checker.fail-on-warning = on
        """)
        .withFallback(system.settings.config)

      intercept[IllegalArgumentException] {
        ActorSystem(system.name + "-3", c)
      }
    }

    "be possible to disable" in {
      val c = ConfigFactory
        .parseString("""
          akka.log-level = INFO # typo
          akka.diagnostics.checker.fail-on-warning = on
          akka.diagnostics.checker.enabled = off
        """)
        .withFallback(system.settings.config)

      lazy val sys4 = ActorSystem(system.name + "-4", c)
      try {
        sys4 // this will throw if enabled=off doesn't work
      } finally {
        Try(shutdown(sys4))
      }
    }

    "print messages for documentation" in {
      val c1 =
        """
          # intro log example
          #//#dispatcher-throughput
          my-dispatcher = {
            fork-join-executor.parallelism-min = 4
            fork-join-executor.parallelism-max = 4
            throughput = 200
          }
          #//#dispatcher-throughput

          #//#typo
          akka.log-level=DEBUG

          akka.default-dispatcher {
            throughput = 10
          }
          #//#typo

          #//#power-user
          akka.cluster.gossip-interval = 5s
          #//#power-user

          #//#default-dispatcher-size-large
          akka.actor.default-dispatcher = {
            fork-join-executor.parallelism-min = 512
            fork-join-executor.parallelism-max = 512
          }
          #//#default-dispatcher-size-large

          #//#fork-join-large
          my-fjp = {
            executor = fork-join-executor
            fork-join-executor.parallelism-min = 100
            fork-join-executor.parallelism-max = 100
          }
          #//#fork-join-large

          #//#cluster-fd-short
          akka.cluster.failure-detector.acceptable-heartbeat-pause = 1s
          #//#cluster-fd-short

        """

      val c2 =
        """
        #//#cluster-fd-ratio
        akka.cluster.failure-detector {
          heartbeat-interval = 3s
          acceptable-heartbeat-pause = 6s
        }
        #//#cluster-fd-ratio

        #//#remote-watch-fd-short
        akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 3s
        #//#remote-watch-fd-short

        #//#disabled-checks
        akka.diagnostics.checker {
          disabled-checks = [dispatcher-throughput]
        }
        #//#disabled-checks

        #//#disabled
        akka.diagnostics.checker.enabled = off
        #//#disabled
        """

      val configs = List(c1, c2).map(s => ConfigFactory.parseString(s).withFallback(defaultCluster))
      configs.zipWithIndex.foreach { case (c, i) =>
        withClue(s"problem with config #${i + 1}") {
          val checker = new ConfigChecker(extSys, c, reference)
          val warnings = checker.check().warnings
          printDocWarnings(warnings)

          // no other than the expected typos, please
          val expectedTypos = Set("akka.log-level", "akka.default-dispatcher.throughput")
          warnings.foreach { w =>
            if (w.checkerKey == "typo")(w.paths.toSet.diff(expectedTypos)) should be(Set.empty)
          }
        }
      }

    }

    "avoid spurious coordinated-shutdown warnings" in {
      val config = ConfigFactory
        .parseString("""
        akka.coordinated-shutdown.phases {
          before-service-unbind {
            timeout = 10 seconds
          }
          my-phase {
            depends-on = [before-service-unbind]
          }
        }
        """)
        .withFallback(reference)
      val checker = new ConfigChecker(extSys, config, reference)
      val warnings = checker.check().warnings

      warnings should be(Vector.empty)
    }

    "not warn about classic remoting settings missing when artery is used" in {
      val config = ConfigFactory
        .parseString("""
       akka {
         actor {
           provider = remote
         }
         remote {
           artery {
             enabled = on
             canonical.hostname = "127.0.0.1"
             canonical.port = 25252
           }
         }
       }
      """)
        .withFallback(reference)

      val checker = new ConfigChecker(extSys, config, reference)
      val warnings = checker.check().warnings

      warnings should be(Vector.empty)
    }

    "not warn about the dynamic hostnames when artery is used" in {
      val config1 = ConfigFactory
        .parseString("""
       akka {
         actor {
           provider = remote
         }
         remote {
           artery {
             enabled = on
             canonical.hostname = "<getHostAddress>"
             canonical.port = 25252
             log-aeron-counters = on
           }
         }
       }
      """)
        .withFallback(reference)

      val checker = new ConfigChecker(extSys, config1, reference)
      val warnings = checker.check().warnings

      printDocWarnings(warnings)
      assertCheckerKey(warnings, "hostname")
      assertPath(warnings, "akka.remote.artery.canonical.hostname")

      val config2 =
        ConfigFactory.parseString("""akka.remote.artery.canonical.hostname = "<getHostName>" """).withFallback(config1)

      val checker2 = new ConfigChecker(extSys, config2, reference)
      val warnings2 = checker2.check().warnings

      printDocWarnings(warnings2)
      assertCheckerKey(warnings2, "hostname")
      assertPath(warnings2, "akka.remote.artery.canonical.hostname")

    }

    "not warn about HTTP server, client and pool specific parsing overrides" in {
      // these are brought in through some trix in akka-http
      val config = ConfigFactory
        .parseString("""
          akka.http.server.parsing.illegal-header-warnings = on
          akka.http.client.parsing.illegal-header-warnings = on
          akka.http.host-connection-pool.parsing.illegal-header-warnings = on
        """)
        .withFallback(reference)

      val checker = new ConfigChecker(extSys, config, reference)
      val warnings = checker.check().warnings

      warnings should be(Vector.empty)
    }

    "not warn about things in the cinnamon config" in {
      val config = ConfigFactory.parseResources("all-cinnamon-2018-05-14-reference.conf").withFallback(reference)

      val checker = new ConfigChecker(extSys, config, reference)
      val warnings = checker.check().warnings

      warnings should be(Vector.empty)
    }

    @nowarn("msg=possible missing interpolator")
    val defaultGrpc = ConfigFactory.parseString("""
      akka.grpc.client."*" {
        host = ""
        port = 0
        ssl-config = ${ssl-config}
      }
    """)

    "not warn about paths with quotes" in {
      val config = ConfigFactory
        .parseString("""akka.grpc.client."*".ssl-config.hostnameVerifierClass = 37""")
        .withFallback(reference)
      val referenceWithGrpc = reference.withFallback(defaultGrpc).resolve
      val checker = new ConfigChecker(extSys, config, referenceWithGrpc)
      val warnings = checker.check().warnings

      warnings should be(Vector.empty)
    }

    "warn about paths under akka.grpc.client for specific services" in {
      val config = ConfigFactory
        .parseString("""
        akka.grpc.client {
          "helloworld.GreeterService" {
            host = 127.0.0.1
            poort = 8080
          }
          otherValue = 42
        }
      """)
        .withFallback(reference)
      val referenceWithGrpc = reference.withFallback(defaultGrpc).resolve
      val checker = new ConfigChecker(extSys, config, referenceWithGrpc)
      val warnings = checker.check().warnings

      warnings should be(
        Vector(
          ConfigWarning(
            "typo",
            """akka.grpc.client."helloworld.GreeterService".poort is not an Akka configuration setting. Did you mean one of 'akka.http.client.proxy.https.port', 'akka.management.http.port', 'akka.grpc.client.*.port'? Is it a typo or is it placed in the wrong section? Application specific properties should be placed outside the "akka" config tree.""",
            List("""akka.grpc.client."helloworld.GreeterService".poort"""),
            List()),
          ConfigWarning(
            "typo",
            """akka.grpc.client.otherValue is not an Akka configuration setting. Is it a typo or is it placed in the wrong section? Application specific properties should be placed outside the "akka" config tree.""",
            List("""akka.grpc.client.otherValue"""),
            List())))
    }

  }
}
