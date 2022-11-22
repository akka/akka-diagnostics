# Testing

The `akka-persistence-multi-dc-testkit` module provides a `Simulator` for unit
testing replicated entities without using a database. Fine-grained control is available for inspecting
side effects and simulating various scenario's and replication orderings.

For more realistic but more heavy 'integration test'-style tests you might want
to consider writing a [Multi JVM Test](#integration-testing) in addition to simulator-based tests.

## Dependency

To use the simulator and the Cassandra launcher described below you will need the `akka-persistence-multi-dc-testkit` dependency:

sbt
:   @@@vars
```
// Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html
"com.lightbend.akka" %% "akka-persistence-multi-dc-testkit" % "$version$" % Test
```
@@@

Gradle
:   @@@vars
```
// Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html
dependencies {
  testCompile group: 'com.lightbend.akka', name: 'akka-persistence-multi-dc-testkit_$scala.binaryVersion$', version: '$version$'
}
```
@@@

Maven
:   @@@vars
```
<!-- Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html -->
<dependency>
  <groupId>com.lightbend.akka</groupId>
  <artifactId>akka-persistence-multi-dc-testkit_$scala.binaryVersion$</artifactId>
  <version>$version$</version>
  <scope>test</scope>
</dependency>
```
@@@

@@include[../includes/common.md](../includes/common.md) { #find-credentials }

### Basic simulator usage

A simple use of the simulator might look like this:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #basic-test }

Java
:  @@snip[BasicTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/basic/BasicTest.java) { #basic-test }

The `createSimulator` helper method is a convenience method that makes it easy
to create a `Simulator` under a mostly-empty `ActorSystem` and makes sure its
lifecycle is correctly tied into your test framework of choice. For example,
under @scala[scalatest]@java[junit] the complete test might look like:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #basic-test-with-infra }

Java
:  @@snip[BasicTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/basic/BasicTest.java) { #basic-test-with-infra }

For the sake of completeness, the entity under test looks like:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #buggy-entity }

Java
:  @@snip[BasicTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/basic/BuggyEntity.java) { #buggy-entity }

## Testing side effects

To demonstrate some more advanced testing scenario's, we introduce a
`ReliableDeliverer` actor implementation which we will test. This actor
implements an "at-least-once" delivery: after accepting a task and
persisting the intent of performing some async action, we immediately
send a `DeliveryScheduled` acknowledgement to the sender. Then we
attempt to perform some async call, retrying in case of restarts, sending
a final `DeliveryAcknowledged` at a best-effort basis. Typically you might
also want to trigger retries with a `Timer`, but this has been left out of this
example for brevity.

The actor under test looks like this:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #reliable-deliverer }

Java
:  @@snip[ReliableDeliverer.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/reliabledeliverer/ReliableDeliverer.java)

Testing that it indeed sends both a `DeliveryScheduled` and a
`DeliveryAcknowledged` back to the sender for the "happy path" scenario can
be demonstrated with the following test:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #reliable-deliverer-responds }

Java
:  @@snip[SenderTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/reliabledeliverer/SenderTest.java)

## Testing recovery

Of course it is important to test the logic of your replicated entity is also
consistent after recovery. The simulator provides a convenient `.restart()`
method to start a new instance of the replicated entity and apply the recovery
logic. Do note that this does not actually restart the actor system used for
hosting the tests.

To demonstrate testing recovery, we can for example show the recovery behavior
of the `ReliableDeliverer` actor introduced in the previous section:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #reliable-deliverer-recovers }

Java
:  @@snip[RecoveryTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/reliabledeliverer/RecoveryTest.java)

## Snapshotting

When snapshots are used to make recovery more efficient:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #snapshotting }

Java
:  @@snip[Snapshotting.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/snapshotting/Snapshotting.java)

The simulator correctly takes this into account and replays the events
starting from the point of the snapshot:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #test-snapshotting }

Java
:  @@snip[SnapshottingTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/snapshotting/SnapshottingTest.java)

## Timeouts and Timers

In the following example we have a test that shuts itself down when its receive timeout has been triggered:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #stop-when-idle }

Java
:  @@snip[RecoveryTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/stopwhenidle/StopWhenIdle.java)

In its test, a receive timeout can be explicitly triggered:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #trigger-receive-timeout }

Java
:  @@snip[ReceiveTimeoutTest.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/stopwhenidle/ReceiveTimeoutTest.java)

When a replicated entity uses the powerful Timers API:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #ticking }

Java
:  @@snip[Ticking.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/timers/Ticking.java)

The simulator API allows timers to be inspected and triggered:

Scala
:   @@snip [SimulatorSpec.scala](/akka-persistence-multi-dc-testkit/src/test/scala/akka/persistence/multidc/testkit/SimulatorSpec.scala) { #timers }

Java
:  @@snip[Ticking.java](/akka-persistence-multi-dc-testkit/src/test/java/akka/persistence/multidc/testkit/timers/TimerTest.java)

## Integration testing

It is also possible to test your replicated entity using an integration test. Such a test starts a number of actor systems, and provides some tools to simulate failures. These would typically connect to
a real cassandra instance, making them more faithful but also rather heavy.

To make it easier to spawn a temporary Cassandra instance for testing `akka-persistence-multi-dc-testkit`
provides a `CassandraLifecycle` utility. This can be relatively easily hooked into your test framework,
for example for @scala[scalatest your could use a trait]@java[JUnit you could initialize it] like this:

Scala
:   @@snip [CassandraLifecycleScalatest.scala](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/testkit/CassandraLifecycleScalatest.scala) { #cassandra-hook }

Java
:  @@snip[AuctionExampleTest.java](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java) { #cassandra-hook }

Combined with `PersistenceMultiDcTestKit` you can write tests that verify the behavior when replication between
systems is temporarily suspended. You will need to add `persistenceMultiDcTestSettings` to your actor system configuration.

Scala
:   @@snip [AuctionExampleSpec.scala](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/AuctionExampleSpec.scala) { #disable-replication }

Java
:  @@snip[AuctionExampleTest.java](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java) { #disable-replication }


A full example, testing the auction actor described @ref[here](auction-example.md), might look like this:

Scala
:   @@snip [AuctionExampleSpec.scala](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/AuctionExampleSpec.scala) { #full-example }

Java
:  @@snip[AuctionExampleTest.java](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java) { #full-example }

@@@ div { .group-scala }

## Multi-JVM testing

A @extref:[Multi JVM Test](akka:multi-jvm-testing.html) is even more heavyweight, as it will spawn an entire new
JVM for each cluster node. This does allow for even more production-like tests, however bear in mind that it is
also harder to interpret any failures.

@@snip [ShardedReplicatedEntitySpec.scala](/akka-persistence-multi-dc-tests/src/multi-jvm/scala/docs/ShardedReplicatedEntitySpec.scala) { #full-example }

@@@
