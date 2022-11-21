/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.akka.diagnostics

import java.io._
import java.lang.management.{ ManagementFactory, ThreadInfo }
import java.net.{ URLClassLoader, URLEncoder }
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.{ Calendar, TimeZone }

import javax.management.{ InstanceAlreadyExistsException, InstanceNotFoundException, ObjectName, StandardMBean }
import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import com.lightbend.akka.diagnostics.mbean.{ AnnotatedStandardMXBean, Description, Name }
import com.typesafe.config._
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.Try

import akka.util.ManifestInfo

/**
 * INTERNAL API
 */
private[akka] object DiagnosticsRecorder extends ExtensionId[DiagnosticsRecorder] with ExtensionIdProvider {
  override def get(system: ActorSystem): DiagnosticsRecorder = super.get(system)

  override def lookup = DiagnosticsRecorder

  override def createExtension(system: ExtendedActorSystem): DiagnosticsRecorder = new DiagnosticsRecorder(system)

  private val UTC = TimeZone.getTimeZone("UTC")

  private class UtcDateFormat(pattern: String) extends SimpleDateFormat(pattern) {
    // set the calendar to use UTC
    calendar = Calendar.getInstance(UTC)
    calendar.setLenient(false)
  }

  private def newUtcTimestampFormat: UtcDateFormat =
    new UtcDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")

  private def createWriter(file: File, append: Boolean = false): PrintWriter =
    new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(file, append)), "utf-8"))

  /**
   * The MBean interface of the Diagnostics Recorder
   */
  @Description(
    "Akka Diagnostics Recorder writes configuration and system information to a " +
      "file that can be attached to your Lightbend support cases.")
  trait DiagnosticsRecorderMBean {
    @Description("The location of the diagnostics file.")
    def getReportFileLocation: String

    @Description(
      "Capture a configured number of thread dumps and additional metrics and " +
        "append to the diagnostics report file.")
    def collectThreadDumps(): String

    @Description(
      "Capture a given number of thread dumps and additional metrics and " +
        "append to the diagnostics report file.")
    def collectThreadDumps(@Description("number of thread dumps")@Name("count") count: java.lang.Integer): String
  }
}

/**
 * INTERNAL API: Akka Diagnostics Recorder writes configuration and system information to a file that customers can
 * attach to Lightbend support cases. The information will help us at Lightbend to give you the best possible support.
 *
 * It will register a MBean in the "akka" name space, which can be accessed from a JMX console such as JConsole. From
 * JMX you can trigger thread dumps that will also be appended to the file.
 */
private[akka] class DiagnosticsRecorder(system: ExtendedActorSystem) extends Extension {
  import DiagnosticsRecorder._

  // no concurrent writes to the file
  private val fileLock = new Object

  private val config = system.settings.config
  private val enabled = config.getBoolean("akka.diagnostics.recorder.enabled")
  private val sensitiveConfigPaths: Set[String] =
    config.getStringList("akka.diagnostics.recorder.sensitive-config-paths").asScala.toSet
  private val collectThreadDumpsCount = config.getInt("akka.diagnostics.recorder.collect-thread-dumps-count")
  private val collectThreadDumpsInterval =
    config.getDuration("akka.diagnostics.recorder.collect-thread-dumps-interval", MILLISECONDS).millis

  private var findDeadlocks = true

  private val threadMx = ManagementFactory.getThreadMXBean

  // All tasks of the Diagnostics Recorder are executed by this dispatcher.
  // Since some tasks involve blocking file operations it is important to
  // run them on a separate dispatcher.
  private implicit val dispatcher = system.dispatchers.lookup("akka.diagnostics.recorder.dispatcher")

  private val qualifiedSystemName: String = {
    val encSystemName = URLEncoder.encode(system.name, "utf-8")
    val remoteQualifier = {
      val address = system.provider.getDefaultAddress
      if (address.hasLocalScope) ""
      else s"-${address.host.getOrElse("")}-${address.port.getOrElse("")}"
    }
    encSystemName + remoteQualifier
  }

  val reportFileName: String = s"Diagnostics-$qualifiedSystemName.json"
  private lazy val reportFile: File = new File(reportDir, reportFileName)

  private val mBeanServer = ManagementFactory.getPlatformMBeanServer

  val mbeanName: ObjectName = new ObjectName(s"akka:type=Diagnostics - $qualifiedSystemName")
  if (enabled && config.getBoolean("akka.diagnostics.recorder.jmx-enabled")) {
    try {
      val mbean = new AnnotatedStandardMXBean(new DiagnosticsRecorderMBeanImpl, classOf[DiagnosticsRecorderMBean])
      mBeanServer.registerMBean(mbean, mbeanName)
      system.registerOnTermination(unregisterMBean())
    } catch {
      case e: InstanceAlreadyExistsException =>
      // ignore - we are running multiple actor systems in the same JVM (probably for testing)
    }
  }

  /**
   * Collect configuration and some system metrics, such as heap settings and write to the diagnostics report file.
   */
  def runStartupReport(): Unit = {
    if (enabled) {
      val after = config.getDuration("akka.diagnostics.recorder.startup-report-after", MILLISECONDS).millis
      system.scheduler.scheduleOnce(after) {
        startupReport()
      }
    }
  }

  private def startupReport(): Unit = fileLock.synchronized {
    try {
      val dir = reportDir
      val fileName = reportFileName
      // writing the readme is not critical, hence the Try
      Try(writeReadme(new File(dir, "readme.txt"), fileName))
      val writer = createWriter(new File(dir, fileName))
      try {
        writer.println(gatherStartupInfo())
      } finally {
        Try(writer.close())
      }
    } catch {
      case NonFatal(e) =>
        system.log.warning(
          "Couldn't gather Akka diagnostics information, please configure section akka.diagnostics.recorder (to correct error or turn off this feature): {}",
          e.getMessage)
    }
  }

  private def reportDir: File = {
    val dir = new File(config.getString("akka.diagnostics.recorder.dir"))
    mkReportDir(dir)
    dir
  }

  private def mkReportDir(dir: File): Unit = {
    if (!dir.exists)
      if (!dir.mkdirs())
        throw new IllegalArgumentException(s"Couldn't create directory: $dir")
    if (!dir.isDirectory())
      throw new IllegalArgumentException(s"Configured akka.diagnostics.recorder.dir [$dir] is not a directory")
  }

  private def writeReadme(file: File, infoFileName: String): Unit = {
    val writer = createWriter(file)
    try {
      writer.print("Please attach the ")
      writer.print(infoFileName)
      writer.println(" file to your Lightbend support cases at http://support.lightbend.com/")
      writer.println("The information will help us at Lightbend to give you the best possible support.")
    } finally {
      Try(writer.close())
    }
  }

  def gatherStartupInfo(): String = {
    val sb = new StringBuilder(1024)
    sb.append("{\n")
    appendJson(sb, "  ", "start-time", newUtcTimestampFormat.format(system.startTime))
    appendVersions(sb)
    appendJson(sb, "  ", "classpath", classpath)

    appendConfigurationWarningsJson(sb)
    sb.append(",\n")

    appendStartupSystemMetricsJson(sb)
    sb.append(",\n")

    val reference = ConfigFactory.defaultReference(system.dynamicAccess.classLoader)
    val appConfiguration = applicationConfig(config, reference)
    appendJson(sb, "  ", "configuration", appConfiguration, end = true)

    sb.append("}\n")
    sb.toString
  }

  private def classpath: String =
    system.dynamicAccess.classLoader match {
      case cl: URLClassLoader => cl.getURLs.map(_.getFile).mkString(":")
      case _ => System.getProperty("java.class.path")
    }

  private def appendVersions(sb: StringBuilder): Unit = {
    val versions = ManifestInfo(system).versions

    val akkaVersion = versions.get("akka-actor") match {
      case Some(v) => v.version
      case None => system.settings.ConfigVersion
    }
    appendJson(sb, "  ", "akka-version", akkaVersion)

    def appendLibraryVersion(productFamilyName: String, artifactName: String): Unit = {
      versions.get(artifactName).foreach { v =>
        appendJson(sb, "  ", productFamilyName + "-version", v.version)
      }
    }

    appendLibraryVersion("akka-diagnostics", "akka-diagnostics")
    appendLibraryVersion("akka-grpc", "akka-grpc-runtime")
    appendLibraryVersion("akka-http", "akka-http-core")
    appendLibraryVersion("akka-management", "akka-management")
    appendLibraryVersion("akka-persistence-cassandra", "akka-persistence-cassandra")
    appendLibraryVersion("akka-persistence-couchbase", "akka-persistence-couchbase")
    appendLibraryVersion("akka-split-brain-resolver", "akka-split-brain-resolver")
    appendLibraryVersion("alpakka-kafka", "akka-stream-kafka")
    appendLibraryVersion("lagom", "lagom-api")
    appendLibraryVersion("play", "play")
  }

  /**
   * Extract the application specific configuration, i.e. remove things that come from reference.conf
   */
  def applicationConfig(config: Config, reference: Config): Config = {
    var result = config
    // stack of the path elements
    // Using java.util.LinkedList because we use ConfigUtil to join these path elements.
    val pathList = new java.util.LinkedList[String]

    def filter(obj: ConfigObject): Unit = {
      val iter = obj.entrySet().iterator()
      while (iter.hasNext()) {
        val entry = iter.next()
        entry.getValue match {
          case o: ConfigObject =>
            val key = entry.getKey
            // include all "java" system properties (no need to traverse)
            if (!(pathList.isEmpty && key == "java")) {
              pathList.add(key)
              val origin = o.origin().resource()
              if (origin == "reference.conf") {
                result = result.withoutPath(ConfigUtil.joinPath(pathList))
                pathList.removeLast()
              } else {
                filter(o) // recursive
              }
            }

          case v: ConfigValue => // leaf value
            pathList.add(entry.getKey)
            val origin = v.origin().resource()
            if (origin == "reference.conf") {
              val path = ConfigUtil.joinPath(pathList)
              result = result.withoutPath(path)
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

    filter(config.root)

    val excludedSensitive = sensitiveConfigPaths.filter(result.hasPath)
    val result2 = excludedSensitive.foldLeft(result) { (acc, p) => acc.withoutPath(p) }
    val result3 = ConfigFactory
      .parseMap(Map("excluded-sensitive-paths" -> excludedSensitive.toSeq.asJava).asJava)
      .withFallback(result2)
    result3
  }

  private def appendConfigurationWarningsJson(sb: StringBuilder): Unit = {
    val warnings = new ConfigChecker(system).check().warnings
    appendJsonName(sb, "  ", "configuration-warnings").append("[")
    if (warnings.nonEmpty) sb.append("\n")
    warnings.zipWithIndex.foreach {
      case (w, i) =>
        sb.append("    {\n")
        appendJson(sb, "      ", "checker-key", w.checkerKey)
        appendJson(sb, "      ", "message", w.message.replace('"', '\''))
        appendJson(sb, "      ", "properties", w.propertiesAsString)
        appendJson(sb, "      ", "defaults", w.defaultsAsString, end = true)
        sb.append("\n    }")
        if (i != warnings.size - 1)
          sb.append(",\n")
    }
    sb.append("]")
  }

  private def appendStartupSystemMetricsJson(sb: StringBuilder): Unit = {
    import scala.collection.JavaConverters._
    val osMBean = ManagementFactory.getOperatingSystemMXBean
    val memPoolMBean = ManagementFactory.getMemoryPoolMXBeans
    val memoryMBean = ManagementFactory.getMemoryMXBean
    val heap = memoryMBean.getHeapMemoryUsage

    appendJsonName(sb, "  ", "system-metrics").append("{\n")
    appendJson(sb, "    ", "heap-init", heap.getInit)
    appendJson(sb, "    ", "heap-max", heap.getMax)
    appendJson(sb, "    ", "heap-used", heap.getUsed)
    appendJson(sb, "    ", "heap-committed", heap.getCommitted)
    appendJson(sb, "    ", "os-processors", osMBean.getAvailableProcessors)

    memPoolMBean.asScala.zipWithIndex.foreach {
      case (memPool, i) =>
        val usage = memPool.getUsage
        appendJsonName(sb, "    ", "mem-pool-" + i).append("{\n")
        appendJson(sb, "      ", "name", memPool.getName)
        appendJson(sb, "      ", "type", memPool.getType.toString)
        appendJson(sb, "      ", "init", usage.getInit)
        appendJson(sb, "      ", "max", usage.getMax)
        appendJson(sb, "      ", "used", usage.getUsed)
        appendJson(sb, "      ", "committed", usage.getCommitted, end = true)
        sb.append("\n    }")
        if (i != memPoolMBean.size - 1) sb.append(",\n")
    }
    sb.append("\n  }")
  }

  /**
   * Collect a number of thread dumps with configured interval. The thread dumps and some basic metrics such as heap
   * usage are appended to diagnostics report file.
   */
  def runCollectThreadDumps(count: Int): Unit = {
    for (n <- 0 until count) {
      system.scheduler.scheduleOnce(n * collectThreadDumpsInterval)(collectThreadDump())
    }
  }

  private def collectThreadDump(): Unit = try {
    val dump = dumpThreads()
    fileLock.synchronized {
      mkReportDir(reportFile.getParentFile) // in case the directory was removed
      val writer = createWriter(reportFile, append = true)
      try writer.println(dump)
      finally Try(writer.close())
    }
  } catch {
    case NonFatal(e) =>
      system.log.error(e, "Couldn't create thread dump")
  }

  def dumpThreads(): String = {
    val startTime = System.nanoTime()
    val sb = new StringBuilder(1024)
    sb.append("{\n")
    appendJson(sb, "  ", "timestamp", newUtcTimestampFormat.format(System.currentTimeMillis()))
    gatherCurrentSystemMetrics(sb)
    appendThreadInfosJson(dumpAllThreads(), sb, "all-threads")
    sb.append(",\n")

    appendJson(sb, "  ", "deadlock-detection-enabled", findDeadlocks)
    if (findDeadlocks) {
      val (deadlockedThreads, deadlockDesc) = findDeadlockedThreads()
      if (dumpAllThreads().nonEmpty) {
        appendJson(sb, "  ", "deadlocks-for", deadlockDesc)
        appendThreadInfosJson(deadlockedThreads, sb, "deadlock-threads")
        sb.append(",\n")
      }
    }

    val tookMs = (System.nanoTime() - startTime) / 1000 / 1000
    appendJson(sb, "  ", "dump-threads-took-ms", tookMs, end = true)
    if (tookMs > 3000)
      findDeadlocks = false // disable because it might be expensive

    sb.append("\n}\n")
    sb.toString
  }

  private def gatherCurrentSystemMetrics(sb: StringBuilder): Unit = {
    val osMx = ManagementFactory.getOperatingSystemMXBean()
    val memMx = ManagementFactory.getMemoryMXBean()

    appendJson(sb, "  ", "load-average", osMx.getSystemLoadAverage())
    appendJson(sb, "  ", "heap-usage", memMx.getHeapMemoryUsage().getUsed)
    appendJson(sb, "  ", "non-heap-usage", memMx.getNonHeapMemoryUsage().getUsed)

    // sum of GC collection counts and time
    var collectionCount = 0L
    var collectionTime = 0L
    val gcIter = ManagementFactory.getGarbageCollectorMXBeans.iterator
    while (gcIter.hasNext) {
      val gcMbean = gcIter.next
      val c = gcMbean.getCollectionCount
      if (c > 0) collectionCount += c
      val t = gcMbean.getCollectionTime
      if (t > 0) collectionTime += t
    }

    appendJson(sb, "  ", "gc-count", collectionCount)
    appendJson(sb, "  ", "gc-time", collectionTime)
  }

  private def dumpAllThreads(): Seq[ThreadInfo] = {
    threadMx.dumpAllThreads(threadMx.isObjectMonitorUsageSupported, threadMx.isSynchronizerUsageSupported).toIndexedSeq
  }

  private def findDeadlockedThreads(): (Seq[ThreadInfo], String) = {
    val (ids, desc) = if (threadMx.isSynchronizerUsageSupported()) {
      (threadMx.findDeadlockedThreads(), "monitors and ownable synchronizers")
    } else {
      (threadMx.findMonitorDeadlockedThreads(), "monitors, but NOT ownable synchronizers")
    }
    if (ids == null) {
      (Seq.empty, desc)
    } else {
      val maxTraceDepth = 1000 // Seems deep enough
      (threadMx.getThreadInfo(ids, maxTraceDepth).toIndexedSeq, desc)
    }
  }

  private def appendThreadInfosJson(threadInfos: Seq[ThreadInfo], sb: StringBuilder, name: String): Unit = {
    appendJsonName(sb, "  ", name).append("[").append('\n')
    val size = threadInfos.length
    for ((ti, i) <- threadInfos.sortBy(_.getThreadName).zipWithIndex) {
      appendThreadInfoJson(ti, sb)
      if (i != size - 1)
        sb.append(",\n")
    }
    sb.append("]")
  }

  private def appendThreadInfoJson(ti: ThreadInfo, sb: StringBuilder): Unit = {
    sb.append("  {\n")
    appendJson(sb, "    ", "name", ti.getThreadName)
    appendJson(sb, "    ", "id", ti.getThreadId)
    appendJson(sb, "    ", "state", ti.getThreadState.toString)
    if (ti.getLockName != null)
      appendJson(sb, "    ", "lock-on", ti.getLockName)
    if (ti.getLockOwnerName != null) {
      appendJson(sb, "    ", "lock-owned-by", ti.getLockOwnerName)
      appendJson(sb, "    ", "lock-owned-by-id", ti.getLockOwnerId)
    }
    if (ti.isSuspended)
      appendJson(sb, "    ", "suspended", true)
    if (ti.isInNative)
      appendJson(sb, "    ", "in-native", true)

    val locks = ti.getLockedSynchronizers
    if (locks.nonEmpty)
      appendJsonName(sb, "    ", "locked-synchronizers")
        .append("[")
        .append(locks.mkString("\n      \"", "\",\n      \"", "\""))
        .append("],\n")

    def appendFrame(msg: String, o: Any, first: Boolean = false) = {
      if (!first) sb.append(",\n")
      sb.append("      \"").append(msg).append(o).append('"')
    }

    appendJsonName(sb, "    ", "stack-trace").append("[")
    val stackTrace = ti.getStackTrace
    if (stackTrace.length != 0) sb.append("\n")
    for (i <- 0 until stackTrace.length) {
      val ste = stackTrace(i)
      appendFrame("at ", ste, first = i == 0)
      if (i == 0 && ti.getLockInfo != null) {
        import java.lang.Thread.State._
        ti.getThreadState match {
          case BLOCKED => appendFrame("  -  blocked on ", ti.getLockInfo)
          case WAITING => appendFrame("  -  waiting on ", ti.getLockInfo)
          case TIMED_WAITING => appendFrame("  -  waiting on ", ti.getLockInfo)
          case _ =>
        }
      }

      for (mi <- ti.getLockedMonitors if mi.getLockedStackDepth == i)
        appendFrame("  -  locked ", mi)
    }
    sb.append("]\n") // close stack-trace array tag

    sb.append("  }")
  }

  /**
   * Unregisters the JMX MBean from MBean server.
   */
  private def unregisterMBean(): Unit = {
    try {
      mBeanServer.unregisterMBean(mbeanName)
    } catch {
      case e: InstanceNotFoundException =>
      // ignore - we are running multiple actor systems in the same JVM (probably for testing)
    }
  }

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: String): Unit =
    appendJson(sb, indent, name, value, end = false)

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: String, end: Boolean): Unit = {
    appendJsonName(sb, indent, name).append('"').append(value).append('"')
    if (!end) sb.append(",\n")
  }

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: Long): Unit =
    appendJson(sb, indent, name, value, end = false)

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: Long, end: Boolean): Unit = {
    appendJsonName(sb, indent, name).append(value)
    if (!end) sb.append(",\n")
  }

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: Double): Unit =
    appendJson(sb, indent, name, value, end = false)

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: Double, end: Boolean): Unit = {
    appendJsonName(sb, indent, name).append(value)
    if (!end) sb.append(",\n")
  }

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: Boolean): Unit =
    appendJson(sb, indent, name, value, end = false)

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: Boolean, end: Boolean): Unit = {
    appendJsonName(sb, indent, name).append(value)
    if (!end) sb.append(",\n")
  }

  private def appendJson(sb: StringBuilder, indent: String, name: String, value: Config, end: Boolean): Unit = {
    appendJsonName(sb, indent, name)
      // comments not allowed in valid json
      .append(value.root.render(ConfigRenderOptions.defaults.setOriginComments(false).setComments(false)))
    if (!end) sb.append(",\n")
  }

  private def appendJsonName(sb: StringBuilder, indent: String, name: String): StringBuilder = {
    sb.append(indent).append('"').append(name).append("\" : ")
    sb
  }

  /**
   * INTERNAL API
   */
  private[akka] class DiagnosticsRecorderMBeanImpl
    extends StandardMBean(classOf[DiagnosticsRecorderMBean])
    with DiagnosticsRecorderMBean {

    override lazy val getReportFileLocation: String = reportFile.getAbsolutePath

    override def collectThreadDumps(): String =
      collectThreadDumps(collectThreadDumpsCount)

    override def collectThreadDumps(count: java.lang.Integer): String = {
      val c = capThreadDumpsCount(count)
      runCollectThreadDumps(c)
      collectThreadDumpsReply(c)
    }

    private def collectThreadDumpsReply(count: Int): String = {
      if (count == 1)
        s"One thread dump will be collected and written to [$getReportFileLocation] "
      else
        s"[$count] thread dumps will be collected and written to [$getReportFileLocation] " +
          s"with [${collectThreadDumpsInterval.toMillis} ms] interval."
    }

    private def capThreadDumpsCount(c: Int): Int =
      math.max(1, math.min(c, 20))

  }

}
