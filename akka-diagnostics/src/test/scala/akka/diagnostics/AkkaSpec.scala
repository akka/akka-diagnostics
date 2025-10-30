/*
 * Copyright (C) 2025 Lightbend Inc. <https://akka.io>
 */

package akka.diagnostics

import java.lang.reflect.Modifier

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.dispatch.Dispatchers
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.testkit.DeadLettersFilter
import akka.testkit.TestEvent.Mute
import akka.testkit.TestKit
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalactic.CanEqual
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        use-slf4j = false # This is needed to avoid overriding below conf `loggers`
                          #  with `["akka.event.slf4j.Slf4jLogger"]` when importing `akka-persistence-testkit`
        loggers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-max = 8
            }
          }
        }
      }
                                                    """)

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.jdk.CollectionConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def testNameFromCallStack(classToStartFrom: Class[_]): String = {

    def isAbstractClass(className: String): Boolean = {
      try {
        Modifier.isAbstract(Class.forName(className).getModifiers)
      } catch {
        case _: Throwable => false // yes catch everything, best effort check
      }
    }

    val startFrom = classToStartFrom.getName
    val filteredStack = Thread.currentThread.getStackTrace.iterator
      .map(_.getClassName)
      // drop until we find the first occurrence of classToStartFrom
      .dropWhile(!_.startsWith(startFrom))
      // then continue to the next entry after classToStartFrom that makes sense
      .dropWhile {
        case `startFrom`                            => true
        case str if str.startsWith(startFrom + "$") => true // lambdas inside startFrom etc
        case str if isAbstractClass(str)            => true
        case _                                      => false
      }

    if (filteredStack.isEmpty)
      throw new IllegalArgumentException(s"Couldn't find [${classToStartFrom.getName}] in call stack")

    // sanitize for actor system name
    scrubActorSystemName(filteredStack.next())
  }

  /**
   * Sanitize the `name` to be used as valid actor system name by replacing invalid characters. `name` may for example
   * be a fully qualified class name and then the short class name will be used.
   */
  def scrubActorSystemName(name: String): String = {
    name
      .replaceFirst("""^.*\.""", "") // drop package name
      .replaceAll("""\$\$?\w+""", "") // drop scala anonymous functions/classes
      .replaceAll("[^a-zA-Z_0-9-]", "_")
      .replaceAll("""MultiJvmNode\d+""", "") // drop MultiJvm suffix
  }

}

abstract class AkkaSpec(_system: ActorSystem)
    extends TestKit(_system)
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures {

  implicit val patience: PatienceConfig = PatienceConfig(testKitSettings.DefaultTimeout.duration)

  def this(config: Config) = this(
    ActorSystem(
      AkkaSpec.testNameFromCallStack(classOf[AkkaSpec]),
      ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.testNameFromCallStack(classOf[AkkaSpec]), AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, Logging.simpleName(this))

  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true

  final override def beforeAll(): Unit = {
    // TODO not published startCoroner
    atStartup()
  }

  final override def afterAll(): Unit = {
    beforeTermination()
    shutdown()
    afterTermination()
    // TODO not published stopCoroner()
  }

  protected def atStartup(): Unit = {}

  protected def beforeTermination(): Unit = {}

  protected def afterTermination(): Unit = {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: => Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))

  def expectedTestDuration: FiniteDuration = 60.seconds

  def muteDeadLetters(messageClasses: Class[_]*)(sys: ActorSystem = system): Unit =
    if (!sys.log.isDebugEnabled) {
      def mute(clazz: Class[_]): Unit =
        sys.eventStream.publish(Mute(DeadLettersFilter(clazz)(occurrences = Int.MaxValue)))
      if (messageClasses.isEmpty) mute(classOf[AnyRef])
      else messageClasses.foreach(mute)
    }

  // for ScalaTest === compare of Class objects
  implicit def classEqualityConstraint[A, B]: CanEqual[Class[A], Class[B]] =
    new CanEqual[Class[A], Class[B]] {
      def areEqual(a: Class[A], b: Class[B]) = a == b
    }

  implicit def setEqualityConstraint[A, T <: Set[_ <: A]]: CanEqual[Set[A], T] =
    new CanEqual[Set[A], T] {
      def areEqual(a: Set[A], b: T) = a == b
    }
}
