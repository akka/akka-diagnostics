/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.diagnostics

import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.dispatch.ThreadPoolConfig
import akka.event.Logging
import com.typesafe.config._
import org.apache.commons.lang3.StringUtils

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

object ConfigChecker {

  final case class ValidationResults(warnings: immutable.Seq[ConfigWarning])

  final case class ConfigWarning(
      checkerKey: String,
      message: String,
      properties: immutable.Seq[String],
      defaults: immutable.Seq[String]) {

    def propertiesAsString: String = properties.mkString(", ")

    def paths: immutable.Seq[String] =
      properties.map(_.split("=")(0).trim)

    def pathsAsString: String = paths.mkString(", ")

    def defaultsAsString: String = defaults.mkString(", ")
  }

  /**
   * Main method to run the `ConfigChecker` as a java program. The configuration is loaded by the Lightbend Config
   * library, i.e. "application.conf" if you don't specify another file with for example `-Dconfig.file`. See
   * https://github.com/typesafehub/config for details of how to specify configuration location.
   *
   * Potential configuration issues, if any, are printed to `System.out` and the JVM is exited with -1 status code.
   *
   * If no configuration issues are found the message "No configuration issues found" is printed to `System.out` and the
   * JVM is exited with 0 status code.
   *
   * Use [#reportIssues] if you don't want to exit the JVM.
   */
  def main(args: Array[String]): Unit = {

    val config = ConfigFactory
      .parseString("akka.diagnostics.checker.fail-on-warning = on")
      .withFallback(ConfigFactory.load())

    Try(ActorSystem("ConfigChecker", config)) match {
      case Success(_) =>
        println("No configuration issues found")
        System.exit(0)
      case Failure(e) =>
        println(e.getMessage)
        Thread.sleep(2000) // give it a chance to flush log
        System.exit(-1)
    }
  }

  /**
   * Validates the configuration of the given actor system. This is performed when the actor system is started.
   */
  def reportIssues(system: ExtendedActorSystem): Unit = {
    import Internal._

    def runChecks(): ValidationResults = {
      val checker = new ConfigChecker(system)
      val result = checker.check()
      logWarnings(result)
      result
    }

    def logWarnings(result: ValidationResults): Unit =
      if (result.warnings.nonEmpty) {
        val formatted = result.warnings.map(w => recommendation(format(w)))
        val log = Logging.getLogger(system, classOf[ConfigChecker].getName)
        formatted.foreach(log.warning)
      }

    mode(system.settings.config) match {
      case Disabled => // don't run checks
      case LogWarnings =>
        val asyncCheckAfter =
          system.settings.config.getDuration("akka.diagnostics.checker.async-check-after", MILLISECONDS).millis
        if (asyncCheckAfter > Duration.Zero)
          system.scheduler.scheduleOnce(asyncCheckAfter)(runChecks())(system.dispatcher)
        else
          runChecks()

      case FailOnWarnings =>
        val result = runChecks()
        if (result.warnings.nonEmpty)
          throw new IllegalArgumentException(recommendation(result.warnings.map(format).mkString("\n* ", "\n* ", "\n")))
    }
  }

  /**
   * Formatted string representation of a warning.
   */
  def format(warning: ConfigWarning): String = {
    val defaultsAsString = warning.defaultsAsString
    s"${warning.message} Related config properties: [${warning.propertiesAsString}]. " +
    (if (defaultsAsString == "") "" else s"Corresponding default values: [$defaultsAsString]. ") +
    s"You may disable this check by adding [${warning.checkerKey}] to configuration string list " +
    s"akka.diagnostics.checker.disabled-checks."
  }

  private def recommendation(msg: String): String =
    s"Configuration recommendation: $msg"

  /**
   * INTERNAL API
   */
  private object Internal {
    def mode(config: Config): Mode =
      if (config.getBoolean("akka.diagnostics.checker.enabled")) {
        if (config.getBoolean("akka.diagnostics.checker.fail-on-warning")) FailOnWarnings
        else LogWarnings
      } else Disabled

    sealed trait Mode
    case object Disabled extends Mode
    case object LogWarnings extends Mode
    case object FailOnWarnings extends Mode
  }

  private implicit class ConfigTools(val c: Config) extends AnyVal {
    def stringValue(path: String): Option[String] =
      if (c.hasPath(path)) {
        val value = c.getValue(path)
        if (value.valueType() == ConfigValueType.STRING) Some(value.unwrapped().asInstanceOf[String])
        else None
      } else None

    def numericValue(path: String): Option[Int] =
      if (c.hasPath(path)) {
        val value = c.getValue(path)
        if (value.valueType() == ConfigValueType.NUMBER) Some(value.unwrapped().asInstanceOf[Int])
        else None
      } else None

    def hasPathOfType(path: String, valueType: ConfigValueType): Boolean =
      c.hasPath(path) && c.getValue(path).valueType() == valueType
  }
}

/**
 * The `ConfigChecker will try to find potential configuration issues. It is run when the actor system is started. It
 * also possible to run it as a Java main program, see [[ConfigChecker#main]].
 *
 * Detailed documentation can be found in the `akka.diagnostics.checker` section of the reference.conf and in the
 * "Configuration Checker" section of the Akka Reference Documentation.
 */
class ConfigChecker(system: ExtendedActorSystem, config: Config, reference: Config) {
  import ConfigChecker._

  def this(system: ExtendedActorSystem) =
    this(system, system.settings.config, ConfigFactory.defaultReference(system.dynamicAccess.classLoader))

  private val disabledChecks: Set[String] =
    config.getStringList("akka.diagnostics.checker.disabled-checks").asScala.toSet

  private val confirmedPowerUserSettings: Set[String] =
    config.getStringList("akka.diagnostics.checker.confirmed-power-user-settings").asScala.toSet
  private[akka] val (powerUserSettings: Set[String], powerUserWildcardSettings: Set[String]) = {
    val fullList =
      config
        .getStringList("akka.diagnostics.checker.power-user-settings")
        .asScala
        .toSet
        .diff(confirmedPowerUserSettings)
    val (wildcards, exact) = fullList.partition(_.endsWith(".*"))
    val wildcardPrefixes = wildcards.map(s => s.substring(0, s.length - 2))

    (exact, wildcardPrefixes)
  }

  private val disabledTypoSections: Set[String] = {
    config
      .getStringList("akka.diagnostics.checker.disabled-typo-sections")
      .asScala
      .toSet
      .union(config.getStringList("akka.diagnostics.checker.confirmed-typos").asScala.toSet)
  }

  private val defaultDispatcherPath = "akka.actor.default-dispatcher"
  private val internalDispatcherPath = "akka.actor.internal-dispatcher"
  private val autoDownPath = "akka.cluster.auto-down-unreachable-after"
  private val knownDispatcherTypes = Set("PinnedDispatcher", "Dispatcher")
  private val knownExecutorTypes =
    Set("default-executor", "fork-join-executor", "thread-pool-executor", "affinity-pool-executor")
  private val knownDispatcherPrefixes = Set("akka.", "lagom.", "play.", "cassandra-plugin-", "kafka.")

  private val knownSettings = {
    import scala.collection.JavaConverters._
    def collectLeaves(path: String, list: ConfigObject): Seq[(String, String)] =
      list
        .entrySet()
        .iterator()
        .asScala
        .map(e => e.getKey -> e.getValue)
        .flatMap {
          case (key, obj: ConfigObject) => collectLeaves(s"$path.$key", obj)
          case (key, _)                 => Seq(key -> s"$path.$key")
        }
        .toVector

    collectLeaves("akka", reference.getConfig("akka").root)
  }
  private val maxSimilarDistance = 5
  private val maxSimilarItems = 3
  private def similar(name: String): Seq[String] =
    knownSettings
      .map { case (key, path) =>
        (key, path, StringUtils.getLevenshteinDistance(key, name, maxSimilarDistance))
      }
      .filter(_._3 >= 0)
      .sortBy(_._3)
      .take(maxSimilarItems)
      .map(_._2)

  /**
   * Run all checks. No side effects, such as logging.
   */
  def check(): ValidationResults = {
    val warnings = Vector.empty[ConfigWarning] ++
      checkCore() ++
      checkRemote() ++
      checkCluster() ++
      checkSplitBrainResolver() ++
      checkDispatchers() ++
      checkTree()

    ValidationResults(warnings)
  }

  private def ifEnabled(checkerKey: String)(f: String => List[ConfigWarning]): List[ConfigWarning] = {
    if (disabledChecks(checkerKey)) Nil
    else f(checkerKey)
  }

  private def warn(checkerKey: String, path: String, message: String): List[ConfigWarning] =
    warn(checkerKey, List(path), message)

  private def warn(checkerKey: String, paths: List[String], message: String): List[ConfigWarning] = {
    val properties = paths.map(p => tryGetStringProperty(config, p).getOrElse(p))
    val defaults = paths.map(p => tryGetStringProperty(reference, p)).flatten
    List(ConfigWarning(checkerKey, message, properties, defaults))
  }

  private def tryGetStringProperty(c: Config, path: String): Option[String] =
    Try(path + " = " + c.getString(path)).toOption

  /**
   * INTERNAL API
   */
  private[akka] def isModifiedPowerUserSetting(path: String): Boolean =
    try {
      !confirmedPowerUserSettings(path) &&
      (!reference.hasPath(path) || config.getValue(path) != reference.getValue(path)) &&
      (powerUserSettings(path) || powerUserWildcardSettings.exists(path.startsWith))
    } catch {
      case e: ConfigException => false // getValue might throw if used on a non-value path
    }

  private def checkTree(): Vector[ConfigWarning] = {

    val powerUserSettingsCheckKey = "power-user-settings"
    val hasPowerUserSettings = disabledChecks(powerUserSettingsCheckKey)
    val typoCheckKey = "typo"
    val hasTypo = disabledChecks(typoCheckKey)
    if (hasPowerUserSettings && hasTypo)
      Vector.empty
    else {
      val w = new VectorBuilder[ConfigWarning]
      // stack of the path elements, ConfigUtil handles double-quote when needed, e.g. for
      // akka.actor.serialization-bindings."java.io.Serializable"
      // akka.actor.serialization-bindings."[B"
      // Using java.util.LinkedList because we use ConfigUtil to join these path elements.
      val pathList = new java.util.LinkedList[String]

      val deploymentReference = reference.getConfig("akka.actor.deployment.default")
      lazy val grpcClientReference = reference.getConfig("""akka.grpc.client."*"""")

      def inDeploymentSection: Boolean =
        pathList.size > 4 && pathList.asScala.startsWith(Seq("akka", "actor", "deployment"))
      def inGrpcClientSection: Boolean =
        pathList.size > 4 && pathList.asScala.startsWith(Seq("akka", "grpc", "client"))

      def checkConfigObject(obj: ConfigObject): Unit = {
        val iter = obj.entrySet().iterator()
        while (iter.hasNext()) {
          val entry = iter.next()
          entry.getValue match {
            case o: ConfigObject =>
              pathList.add(entry.getKey)
              checkConfigObject(o) // recursive
            case v: ConfigValue =>
              pathList.add(entry.getKey)
              val p = ConfigUtil.joinPath(pathList)

              if (!hasTypo) {
                val isTypo =
                  if (disabledTypoSections.exists(p.startsWith))
                    false
                  else if (inDeploymentSection && pathList.contains("pool-dispatcher"))
                    false
                  else if (inDeploymentSection)
                    // For checking typos inside a `akka.actor.deployment."/user/foo/"` section remove those 4 path elements
                    // and compare with the default deployment config.
                    !deploymentReference.hasPathOrNull(ConfigUtil.joinPath(pathList.subList(4, pathList.size)))
                  else if (inGrpcClientSection)
                    // For checking typos inside a `akka.grpc.client."FooService"` section remove those 4 path elements
                    // and compare with the fallback deployment config.
                    !grpcClientReference.hasPathOrNull(ConfigUtil.joinPath(pathList.subList(4, pathList.size)))
                  else
                    !reference.hasPathOrNull(p)
                if (isTypo) {
                  val similarItems = similar(entry.getKey)
                  val didYouMeanSentence =
                    if (similarItems.nonEmpty) s" Did you mean one of ${similarItems.map(p => s"'$p'").mkString(", ")}?"
                    else ""

                  w += new ConfigWarning(
                    typoCheckKey,
                    s"$p is not an Akka configuration setting.$didYouMeanSentence Is it a typo or is it placed in the wrong section? " +
                    """Application specific properties should be placed outside the "akka" config tree.""",
                    List(p),
                    Nil)
                }
              }

              if (!hasPowerUserSettings && isModifiedPowerUserSetting(p))
                w ++= warn(
                  powerUserSettingsCheckKey,
                  p,
                  s"$p is an advanced configuration setting. Make sure that you fully understand " +
                  "the implications of changing the default value. You can confirm that you know " +
                  s"the meaning of this configuration setting by adding [$p] to configuration string list " +
                  "akka.diagnostics.checker.confirmed-power-user-settings.")

              pathList.removeLast()

            case _ =>
              // in case there would be something else
              ()
          }
        }
        // pop the stack for recursive calls
        pathList.removeLast()
      }

      pathList.add("akka")
      checkConfigObject(config.getConfig("akka").root)

      w.result()
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def findDispatchers(): Map[String, Config] = {
    var result = Map.empty[String, Config]
    // stack of the path elements
    // Using java.util.LinkedList because we use ConfigUtil to join these path elements.
    val pathList = new java.util.LinkedList[String]

    def isADispatherBlock(c: Config): Boolean = {
      def hasKnownDispatcherType =
        c.stringValue("type").exists(knownDispatcherTypes)

      def hasKnownExecutorType =
        c.stringValue("executor").exists(knownExecutorTypes) ||
        c.hasPathOfType("fork-join-executor", ConfigValueType.OBJECT) ||
        c.hasPathOfType("thread-pool-executor", ConfigValueType.OBJECT)

      hasKnownDispatcherType || hasKnownExecutorType
    }

    def find(obj: ConfigObject): Unit = {
      val iter = obj.entrySet().iterator()
      while (iter.hasNext()) {
        val entry = iter.next()
        entry.getValue match {
          case o: ConfigObject =>
            val c = o.toConfig
            pathList.add(entry.getKey)
            if (isADispatherBlock(c)) {
              result += ConfigUtil.joinPath(pathList) -> c
              pathList.removeLast()
            } else
              find(o) // recursive
          case _ => // leaf value
        }
      }
      // pop the stack for recursive calls
      if (!pathList.isEmpty) pathList.removeLast()
    }

    find(config.root)
    result
  }

  private def checkDispatchers(): Vector[ConfigWarning] = {
    var w = Vector.empty[ConfigWarning]
    val dispatchers = findDispatchers()
    w ++= checkNumberOfDispatchers(dispatchers)
    // we can't be sure that it is a real dispatcher sections, so skip if exception
    Try { w ++= checkTotalDispatcherPoolSize(dispatchers) }
    dispatchers.foreach { case (path, cfg) =>
      val cfgWithFallback = cfg.withFallback(system.dispatchers.defaultDispatcherConfig)
      Try { w ++= checkDispatcherThroughput(path, cfgWithFallback) }
      Try { w ++= checkForkJoinPoolSize(path, cfgWithFallback) }
      if (!path.startsWith("akka."))
        w ++= checkTypoInDispatcherSection(path, cfg)
    }
    w
  }

  private def checkTypoInDispatcherSection(path: String, dispatcher: Config): Vector[ConfigWarning] =
    if (disabledChecks("typo"))
      Vector.empty
    else {
      val w = new VectorBuilder[ConfigWarning]
      // stack of the path elements
      // Using java.util.LinkedList because we use ConfigUtil to join these path elements.
      val pathList = new java.util.LinkedList[String]

      val defaultDispatcherReference = system.dispatchers.defaultDispatcherConfig

      def checkConfigObject(obj: ConfigObject): Unit = {
        val iter = obj.entrySet().iterator()
        while (iter.hasNext()) {
          val entry = iter.next()
          entry.getValue match {
            case o: ConfigObject =>
              pathList.add(entry.getKey)
              checkConfigObject(o) // recursive
            case v: ConfigValue =>
              pathList.add(entry.getKey)
              val p = ConfigUtil.joinPath(pathList)
              val fullPath = path + "." + p
              if (!disabledTypoSections.exists(fullPath.startsWith) && !defaultDispatcherReference.hasPath(p)) {
                w += new ConfigWarning(
                  "typo",
                  s"$fullPath is not an Akka dispatcher configuration setting. Is it a typo or is it placed in the wrong section? " +
                  s"If this is not a dispatcher setting you may disable this check by adding [$fullPath] to configuration string list " +
                  s"akka.diagnostics.checker.confirmed-typos.",
                  List(fullPath),
                  Nil)
              }
              pathList.removeLast()
            case _ =>
              // in case there would be something else
              ()
          }
        }
        // pop the stack for recursive calls
        if (!pathList.isEmpty) pathList.removeLast()
      }

      checkConfigObject(dispatcher.root)
      w.result()
    }

  private def checkCore(): Vector[ConfigWarning] = {
    Vector.empty[ConfigWarning] ++
    checkProvider() ++
    checkJvmExitOnFatalError() ++
    checkDefaultDispatcherSize() ++
    checkDefaultDispatcherType() ++
    checkDispatcherThroughput(defaultDispatcherPath, config.getConfig(defaultDispatcherPath))
  }

  private def checkProvider(): List[ConfigWarning] =
    ifEnabled("actor-ref-provider") { checkerKey =>
      val path = "akka.actor.provider"
      val supported = Set(
        "akka.actor.LocalActorRefProvider",
        "akka.remote.RemoteActorRefProvider",
        "akka.cluster.ClusterActorRefProvider",
        "local",
        "remote",
        "cluster")
      val provider = config.getString(path)
      if (supported(provider)) Nil
      else
        warn(
          checkerKey,
          path,
          s"[$provider] is not a supported ActorRef provider. Use one of [${supported.mkString(", ")}].")
    }

  private def checkJvmExitOnFatalError(): List[ConfigWarning] =
    ifEnabled("jvm-exit-on-fatal-error") { checkerKey =>
      val path = "akka.jvm-exit-on-fatal-error"
      if (config.getBoolean(path)) Nil
      else
        warn(
          checkerKey,
          path,
          "Don't use jvm-exit-on-fatal-error=off. It's safer to shutdown the JVM in case of a " +
          "fatal error, such as OutOfMemoryError.")
    }

  private def dispatcherPoolSize(c: Config): Int = {
    val dispatcherType = c.getString("type")
    if (dispatcherType == "PinnedDispatcher" || dispatcherType == "akka.testkit.CallingThreadDispatcherConfigurator")
      1 // a PinnedDispatcher is not really size 1, but that is the best we can guess
    else if (c.getString("executor") == "thread-pool-executor") {
      val min = c.getInt("thread-pool-executor.core-pool-size-min")
      val max = c.getInt("thread-pool-executor.core-pool-size-max")
      val factor = c.getDouble("thread-pool-executor.core-pool-size-factor")
      ThreadPoolConfig.scaledPoolSize(min, factor, max)
    } else {
      val min = c.getInt("fork-join-executor.parallelism-min")
      val max = c.getInt("fork-join-executor.parallelism-max")
      val factor = c.getDouble("fork-join-executor.parallelism-factor")
      ThreadPoolConfig.scaledPoolSize(min, factor, max)
    }
  }

  private def checkDefaultDispatcherSize(): List[ConfigWarning] =
    ifEnabled("default-dispatcher-size") { checkerKey =>
      val path = defaultDispatcherPath

      val size = dispatcherPoolSize(config.getConfig(path))

      val availableProcessors = Runtime.getRuntime.availableProcessors
      if (size > 64 && size > availableProcessors)
        warn(
          checkerKey,
          path,
          s"Don't use too large pool size [$size] for the default-dispatcher. " +
          "Note that the pool size is calculated by ceil(available processors * parallelism-factor), " +
          "and then bounded by the parallelism-min and parallelism-max values. " +
          s"This machine has [$availableProcessors] available processors. " +
          "If you use a large pool size here because of blocking execution you should instead use " +
          "a dedicated dispatcher to manage blocking tasks/actors. Blocking execution shouldn't " +
          "run on the default-dispatcher because that may starve system internal tasks.")
      else if (size <= 3)
        warn(
          checkerKey,
          path,
          s"Don't use too small pool size [$size] for the default-dispatcher. " +
          "Internal actors and tasks may run on the default-dispatcher.")
      else Nil
    }

  private def checkDefaultDispatcherType(): List[ConfigWarning] =
    ifEnabled("default-dispatcher-type") { checkerKey =>
      val path = defaultDispatcherPath
      val dispatcherType = config.getString(path + ".type")
      if (dispatcherType == "PinnedDispatcher" ||
        dispatcherType == "akka.testkit.CallingThreadDispatcherConfigurator")
        warn(
          checkerKey,
          path,
          s"Don't use [$dispatcherType] as default-dispatcher. Configure a separate dispatcher for " +
          "that kind of special purpose dispatcher.")
      else Nil
    }

  private def checkDispatcherThroughput(path: String, c: Config): List[ConfigWarning] =
    ifEnabled("dispatcher-throughput") { checkerKey =>
      val throughput = c.getInt("throughput")
      val deadline = c.getDuration("throughput-deadline-time", MILLISECONDS)
      if (throughput > 100 && deadline <= 0L)
        warn(
          checkerKey,
          List(path + ".throughput", path + ".throughput-deadline-time"),
          s"Use throughput-deadline-time when dispatcher is configured with high throughput [$throughput] " +
          "batching to avoid unfair processing.")
      else Nil
    }

  private def checkForkJoinPoolSize(path: String, c: Config): List[ConfigWarning] =
    ifEnabled("fork-join-pool-size") { checkerKey =>
      val size = dispatcherPoolSize(c)
      if (c.getString("executor") == "thread-pool-executor")
        Nil
      else {
        val availableProcessors = Runtime.getRuntime.availableProcessors
        if (size > 64 && size > availableProcessors)
          warn(
            checkerKey,
            path,
            s"Don't use too large pool size [$size] for fork-join pool. " +
            "Note that the pool size is calculated by ceil(available processors * parallelism-factor), " +
            "and then bounded by the parallelism-min and parallelism-max values. " +
            s"This machine has [$availableProcessors] available processors. " +
            "If you use a large pool size here because of blocking execution you should use " +
            "a thread-pool-executor instead.")
        else Nil
      }
    }

  private def checkNumberOfDispatchers(dispatchers: Map[String, Config]): List[ConfigWarning] =
    ifEnabled("dispatcher-count") { checkerKey =>
      val customDispatchers = dispatchers.collect { case (p, _) if !isLightbendInternalDispatcher(p) => p }
      if (customDispatchers.size > 6)
        warn(
          checkerKey,
          customDispatchers.toList,
          s"You have configured [${customDispatchers.size}] different custom dispatchers. " +
          "Do you really need that many dispatchers? " +
          "Separating into CPU bound tasks and blocking (IO) tasks are often enough.")
      else Nil
    }

  private def isLightbendInternalDispatcher(configPath: String): Boolean =
    knownDispatcherPrefixes.exists(prefix => configPath.startsWith(prefix))

  private def checkTotalDispatcherPoolSize(dispatchers: Map[String, Config]): List[ConfigWarning] =
    ifEnabled("dispatcher-total-size") { checkerKey =>
      val sizes = dispatchers.collect {
        // FIXME does the filtering really make sense here, don't we want to include the lightbend
        // dispatcher pool sizes as well?
        case (p, c) if !isLightbendInternalDispatcher(p) =>
          val cfgWithFallback = c.withFallback(system.dispatchers.defaultDispatcherConfig)
          p -> Try(dispatcherPoolSize(cfgWithFallback)).getOrElse(0)
      }
      val total = sizes.foldLeft(0) { case (acc, (_, s)) => acc + s }
      val availableProcessors = Runtime.getRuntime.availableProcessors
      if (total > 200 && total > availableProcessors * 2)
        warn(
          checkerKey,
          sizes.collect { case (p, s) if s != 0 => p }.toList,
          s"You have a total of [$total] threads in all configured dispatchers. " +
          "That many threads might result in reduced performance. " +
          s"This machine has [$availableProcessors] available processors.")
      else Nil
    }

  private def isRemoteConfigAvailable: Boolean = {
    val provider = config.getString("akka.actor.provider")
    // check existence of a property from reference.conf that will unlikely be defined elsewhere
    reference.hasPath("akka.actor.serializers.daemon-create") &&
    (provider == "remote" || provider == "akka.remote.RemoteActorRefProvider" || isClusterConfigAvailable)
  }

  private def remoteConfigPath(path: String): String = {
    path
  }

  private def checkRemote(): Vector[ConfigWarning] =
    if (isRemoteConfigAvailable) {
      Vector.empty[ConfigWarning] ++
      checkRemoteDispatcher() ++
      checkRemoteWatchFailureDetector() ++
      checkHostname() ++
      checkFrameSize() ++
      checkRemoteDispatcherSize()
    } else Vector.empty[ConfigWarning]

  private def checkRemoteDispatcher(): List[ConfigWarning] =
    ifEnabled("remote-dispatcher") { checkerKey =>
      val path = remoteConfigPath("akka.remote.artery.advanced.use-dispatcher")
      if (config.getString(path) == defaultDispatcherPath)
        warn(
          checkerKey,
          path,
          "Use a dedicated dispatcher for remoting instead of default-dispatcher. " +
          "The internal actors in remoting may use the threads in a way that should not " +
          "interfere with other system internal tasks that are running on the default-dispatcher. " +
          "It can be things like serialization and blocking DNS lookups.")
      else Nil
    }

  private def checkRemoteWatchFailureDetector(): List[ConfigWarning] =
    ifEnabled("remote-watch-failure-detector") { checkerKey =>
      val path = "akka.remote.watch-failure-detector"

      val heartbeatInterval = config.getDuration(path + ".heartbeat-interval", MILLISECONDS).millis
      val acceptable = config.getDuration(path + ".acceptable-heartbeat-pause", MILLISECONDS).millis
      val reaper = config.getDuration(path + ".unreachable-nodes-reaper-interval", MILLISECONDS).millis

      val w1 =
        if (heartbeatInterval < 500.millis)
          warn(
            checkerKey,
            path + ".heartbeat-interval",
            s"Remote watch failure detector heartbeat-interval of [${heartbeatInterval.toMillis} ms] " +
            "is probably too short to be meaningful. There is overhead of sending heartbeat messages " +
            "too frequently.")
        else if (heartbeatInterval > 10.seconds)
          warn(
            checkerKey,
            path + ".heartbeat-interval",
            s"Remote watch failure detector heartbeat-interval of [${heartbeatInterval.toMillis} ms] " +
            "is probably too long to be meaningful.")
        else Nil

      val w2 =
        if (acceptable < 5.second)
          warn(
            checkerKey,
            path + ".acceptable-heartbeat-pause",
            s"Remote watch failure detector acceptable-heartbeat-pause of [${acceptable.toMillis} ms] " +
            "is probably too short to be meaningful. It may cause quarantining of remote system " +
            "because of false failure detection caused by for example GC pauses.")
        else if (acceptable > 1.minute)
          warn(
            checkerKey,
            path + ".acceptable-heartbeat-pause",
            s"Remote watch failure detector acceptable-heartbeat-pause of [${acceptable.toMillis} ms] " +
            "is probably too long to be meaningful.")
        else Nil

      val ratio = acceptable.toMillis / heartbeatInterval.toMillis
      val w3 =
        if (ratio < 3)
          warn(
            checkerKey,
            List(path + ".acceptable-heartbeat-pause", path + ".heartbeat-interval"),
            s"Remote watch failure detector ratio [$ratio] between acceptable-heartbeat-pause and heartbeat-interval " +
            "is too small, decrease the heartbeat-interval and/or increase acceptable-heartbeat-pause. " +
            "Otherwise it may trigger false failure detection and resulting in quarantining of remote system.")
        else Nil

      val w4 =
        if (reaper < heartbeatInterval)
          warn(
            checkerKey,
            List(path + ".unreachable-nodes-reaper-interval", path + ".heartbeat-interval"),
            s"Remote watch failure detector unreachable-nodes-reaper-interval should be less than or equal to the " +
            "heartbeat-interval")
        else Nil

      List(w1, w2, w3, w4).flatten
    }

  private def checkHostname(): List[ConfigWarning] =
    ifEnabled("hostname") { checkerKey =>
      if (config.getBoolean("akka.remote.artery.enabled")) {
        // artery
        config.getString("akka.remote.artery.canonical.hostname") match {
          case "<getHostAddress>" =>
            warn(
              checkerKey,
              "akka.remote.artery.canonical.hostname",
              s"hostname is set to <getHostAddress>, which means that `InetAddress.getLocalHost.getHostAddress` " +
              "will be used to resolve the hostname. That can result in wrong hostname in some environments, " +
              """such as "127.0.1.1". Define the hostname explicitly instead. """ +
              s"On this machine `InetAddress.getLocalHost.getHostAddress` is [${InetAddress.getLocalHost.getHostAddress}].")
          case "<getHostName>" =>
            warn(
              checkerKey,
              "akka.remote.artery.canonical.hostname",
              s"hostname is set to <getHostName>, which means that `InetAddress.getLocalHost.getHostAddress` " +
              "will be used to resolve the hostname. That can result in wrong hostname in some environments, " +
              """such as "127.0.1.1". Define the hostname explicitly instead. """ +
              s"On this machine `InetAddress.getLocalHost.getHostAddress` is [${InetAddress.getLocalHost.getHostName}].")
          case _ => Nil
        }

      } else {
//        // classic remoting
//        config.getStringList(remoteConfigPath("akka.remote.enabled-transports")).asScala.toList.flatMap { t =>
//          if ((t == remoteConfigPath("akka.remote.netty.tcp") && config.getString(t + ".hostname") == "") ||
//            (t == remoteConfigPath("akka.remote.netty.ssl") && config.getString(t + ".hostname") == ""))
//            warn(
//              checkerKey,
//              t + ".hostname",
//              s"hostname is not defined, which means that `InetAddress.getLocalHost.getHostAddress` " +
//              "will be used to resolve the hostname. That can result in wrong hostname in some environments, " +
//              """such as "localhost". Define the hostname explicitly instead. """ +
//              s"On this machine `InetAddress.getLocalHost.getHostAddress` is [${InetAddress.getLocalHost.getHostAddress}].")
//          else Nil
        //*******************************************
        //        # This flag disabled Artery in Akka 2.6.x and 2.7.x. If it is set to off with Akka 2.8.0 or later
        //        # an exception will be thrown at startup with the purpose to notify the user that Classic Remoting
        //        # has been removed.
        //          enabled = on
        throw new IllegalArgumentException("'akka.remote.artery.enabled = off' is not allowed")
      }
    }

  private def checkFrameSize(): List[ConfigWarning] = {
    ifEnabled("maximum-frame-size") { checkerKey =>

      def checkFrameSizeAt(path: String): List[ConfigWarning] = {
        val frameSize = config.getBytes(path)
        if (frameSize > (1024 * 1024))
          warn(
            checkerKey,
            path,
            s"You have configured maximum-frame-size to [${config.getBytes(path)} bytes]. We recommend against " +
            "sending too large messages, since that may cause other messages to be delayed. For example, it's " +
            "important that failure detector heartbeat messages have a chance to get through without too long delays. " +
            "Try to split up large messages into smaller chunks, or use another communication channel (HTTP, Akka IO) " +
            "for large payloads.")
        else Nil
      }

//      List(
//        checkFrameSizeAt(remoteConfigPath("akka.remote.netty.tcp.maximum-frame-size")),
//        checkFrameSizeAt(remoteConfigPath("akka.remote.netty.ssl.maximum-frame-size"))).flatten
      List(checkFrameSizeAt(remoteConfigPath("akka.remote.artery.advanced.maximum-frame-size"))).flatten
    }
  }

  private def checkRemoteDispatcherSize(): List[ConfigWarning] =
    ifEnabled("default-remote-dispatcher-size") { checkerKey =>
      val path = "akka.remote.default-remote-dispatcher"
      val size = dispatcherPoolSize(config.getConfig(path).withFallback(system.dispatchers.defaultDispatcherConfig))
      if (size < 2)
        warn(checkerKey, path, s"Don't use too small pool size [$size] for the default-remote-dispatcher-size.")
      else Nil
    }

  private def isClusterConfigAvailable: Boolean = {
    val provider = config.getString("akka.actor.provider")
    // check existence of a property from reference.conf that will unlikely be defined elsewhere
    reference.hasPath("akka.actor.serializers.akka-cluster") &&
    (provider == "cluster" || provider == "akka.cluster.ClusterActorRefProvider")
  }

  private def checkCluster(): Vector[ConfigWarning] =
    if (isClusterConfigAvailable) {
      Vector.empty[ConfigWarning] ++
      checkAutoDown() ++
      checkClusterFailureDetector() ++
      checkClusterDispatcher()
    } else Vector.empty[ConfigWarning]

  private def checkAutoDown(): List[ConfigWarning] =
    ifEnabled("auto-down") { checkerKey =>
      if (isAutoDownEnabled)
        warn(
          checkerKey,
          autoDownPath,
          "Use Akka Split Brain Resolver instead of auto-down, since auto-down may cause the cluster to be " +
          "split into two separate disconnected clusters when there are network partitions, long garbage " +
          "collection pauses or system overload. This is especially important if you use Cluster Singleton, " +
          "Cluster Sharding, or Persistence.")
      else Nil
    }

  private def isAutoDownEnabled: Boolean = {
    // FIXME the auto-down check can be removed when we only use Akka 2.6
    config.hasPath(autoDownPath) && config.getString(autoDownPath).toLowerCase(Locale.ROOT) != "off" &&
    config.getString("akka.cluster.downing-provider-class") == ""
  }

  private def checkClusterFailureDetector(): List[ConfigWarning] =
    ifEnabled("cluster-failure-detector") { checkerKey =>
      val path = "akka.cluster.failure-detector"
      val reaperPath = "akka.cluster.unreachable-nodes-reaper-interval"

      val heartbeatInterval = config.getDuration(path + ".heartbeat-interval", MILLISECONDS).millis
      val acceptable = config.getDuration(path + ".acceptable-heartbeat-pause", MILLISECONDS).millis
      val reaper = config.getDuration(reaperPath, MILLISECONDS).millis

      val w1 =
        if (heartbeatInterval < 500.millis)
          warn(
            checkerKey,
            path + ".heartbeat-interval",
            s"Cluster failure detector heartbeat-interval of [${heartbeatInterval.toMillis} ms] " +
            "is probably too short to be meaningful. There is overhead of sending heartbeat messages " +
            "too frequently.")
        else if (heartbeatInterval > 5.seconds)
          warn(
            checkerKey,
            path + ".heartbeat-interval",
            s"Cluster failure detector heartbeat-interval of [${heartbeatInterval.toMillis} ms] " +
            "is probably too long to be meaningful.")
        else Nil

      val w2 =
        if (acceptable < 2.second)
          warn(
            checkerKey,
            path + ".acceptable-heartbeat-pause",
            s"Cluster failure detector acceptable-heartbeat-pause of [${acceptable.toMillis} ms] " +
            "is probably too short to be meaningful. It may cause marking nodes unreachable and then " +
            "back to reachable because of false failure detection caused by for example GC pauses.")
        else if (acceptable > 1.minute)
          warn(
            checkerKey,
            path + ".acceptable-heartbeat-pause",
            s"Cluster failure detector acceptable-heartbeat-pause of [${acceptable.toMillis}} ms] " +
            "is probably too long to be meaningful. Note that a node marked as unreachable will " +
            "become reachable again if the failure detector observes that it can communicate with it again, " +
            "i.e. unreachable is not a fatal condition.")
        else Nil

      val ratio = acceptable.toMillis / heartbeatInterval.toMillis
      val w3 =
        if (ratio < 3)
          warn(
            checkerKey,
            List(path + ".acceptable-heartbeat-pause", path + ".heartbeat-interval"),
            s"Cluster failure detector ratio [$ratio] between acceptable-heartbeat-pause and heartbeat-interval " +
            "is too small, decrease the heartbeat-interval and/or increase acceptable-heartbeat-pause. " +
            "Otherwise it may trigger false failure detection and resulting in quarantining of remote system.")
        else Nil

      val w4 =
        if (reaper < heartbeatInterval)
          warn(
            checkerKey,
            List(reaperPath, path + ".heartbeat-interval"),
            s"Cluster failure detector unreachable-nodes-reaper-interval should be less than or equal to the " +
            "heartbeat-interval")
        else Nil

      List(w1, w2, w3, w4).flatten
    }

  private def checkClusterDispatcher(): List[ConfigWarning] =
    ifEnabled("cluster-dispatcher") { checkerKey =>
      val path = "akka.cluster.use-dispatcher"
      val clusterDispatcher = config.getString(path)
      if (clusterDispatcher != "" && clusterDispatcher != defaultDispatcherPath && clusterDispatcher != internalDispatcherPath) {
        if (config.hasPath(clusterDispatcher)) {
          val size = dispatcherPoolSize(
            config
              .getConfig(clusterDispatcher)
              .withFallback(system.dispatchers.defaultDispatcherConfig))
          val w1 = warn(
            checkerKey,
            path,
            "Normally it should not be necessary to configure a separate dispatcher for the Cluster. " +
            s"The default-dispatcher should be sufficient for performing the Cluster tasks, i.e. $path should " +
            "not be changed. If you have Cluster related problems when using the default-dispatcher that is typically " +
            "an indication that you are running blocking or CPU intensive actors/tasks on the default-dispatcher. " +
            "Use dedicated dispatchers for such actors/tasks instead of running them on the default-dispatcher, " +
            "because that may starve system internal tasks.")
          val w2 =
            if (size < 2)
              warn(
                checkerKey,
                List(path, clusterDispatcher),
                "Don't configure Cluster dispatcher with less than 2 threads.")
            else Nil
          List(w1, w2).flatten
        } else
          warn(checkerKey, clusterDispatcher, s"Configured Cluster dispatcher [$clusterDispatcher] does not exist.")
      } else Nil
    }

  private def isSplitBrainResolverConfigAvailable: Boolean = {
    // check existence of a property from reference.conf
    reference.hasPath("akka.cluster.split-brain-resolver.active-strategy")
  }

  private def checkSplitBrainResolver(): List[ConfigWarning] =
    ifEnabled("split-brain-resolver") { checkerKey =>

      val downingProviderPath = "akka.cluster.downing-provider-class"
      val sbrStrategyPath = "akka.cluster.split-brain-resolver.active-strategy"
      val sbrActive = isClusterConfigAvailable && isSplitBrainResolverConfigAvailable &&
        config.getString(sbrStrategyPath).toLowerCase(Locale.ROOT) != "off"
      if (sbrActive) {
        val downRemovalPath = "akka.cluster.down-removal-margin"
        val stableAfterPath = "akka.cluster.split-brain-resolver.stable-after"

        val stableAfter = config.getDuration(stableAfterPath, MILLISECONDS).millis
        val downRemoval = config.getString(downRemovalPath).toLowerCase(Locale.ROOT) match {
          case "off" => stableAfter // we are using a better default value whenSBR is enabled
          case _     => config.getDuration(downRemovalPath, MILLISECONDS).millis
        }

        val w1 =
          if (downRemoval != stableAfter)
            warn(
              checkerKey,
              List(downRemovalPath, stableAfterPath),
              s"It is normally best to configure $downRemovalPath and $stableAfterPath to the same duration. ")
          else Nil

        val w2 =
          if (downRemoval < 5.seconds)
            warn(
              checkerKey,
              downRemovalPath,
              s"Cluster down-removal-margin of [${downRemoval.toMillis} ms] is probably too short. There is a risk that " +
              "persistent actors and singletons have not stopped at the non-surviving side of a network partition before " +
              "corresponding actors are started in surviving partition. See Split Brain Resolver documentation for " +
              "recommended configuration for different cluster sizes.")
          else Nil

        val w3 =
          if (stableAfter < 5.seconds)
            warn(
              checkerKey,
              stableAfterPath,
              s"SBR stable-after of [${stableAfter.toMillis} ms] is probably too short. There is a risk that " +
              "the SBR decision is based on incomplete information. Don't set this to a shorter duration than the " +
              "membership dissemination time in the cluster, which depends on the cluster size. " +
              "See Split Brain Resolver documentation for recommended configuration for different cluster sizes.")
          else Nil

        val w4 =
          if (isAutoDownEnabled)
            warn(
              checkerKey,
              List(autoDownPath, sbrStrategyPath),
              "You have enabled both auto-down and split-brain-resolver. For backwards " +
              "compatibility reasons auto-down will be used instead of split-brain-resolver. " +
              "Please remove the auto-down configuration.")
          else Nil

        List(w1, w2, w3, w4).flatten
      } else Nil

    }

}
