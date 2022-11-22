# Fast Failover

It takes time for Akka clustering to reliably determine that a node is no longer reachable and can safely be removed from the cluster, by default this time is a minimum of 10 seconds. In some cases, you may want certain transactions to failover faster than that, in sub second times for example. Akka's fast failover feature is designed for these scenarios.

@@@ warning

This module is currently marked as @extref:[May Change](akka:common/may-change.html) in the sense of that the API might be changed based on feedback from initial usage.

@@@

@@include[proprietary.md](includes/proprietary.md)

## Approach

Akka clustering, in combination with Akka's split brain resolver, is able to guarantee that features like cluster sharding will only ever have one node running each entity, even in the presence of network partitions. In order to do this it needs a certain level of consensus, as well as some timeouts to wait for a partioned part of the cluster to down itself, and this is why it takes time to failover.

Akka fast failover does not use any consensus, nor does it wait for partitions to down themselves, rather it gambles that if if a reply (or progress update) to a request takes too long, then that node has crashed. This allows it to failover as soon as such an event occurs. The compromise is that the node may not have crashed, it may simply be not responding due to a partition or long garbage collection pause, and in that scenario the operation may end up being performed on two nodes concurrently, which may lead to inconsistencies.

In some cases, the need for reliability is very high, while it is possible to manage the inconsistencies that may occur when fast failover happens. These cases can make use of Akka faster failover to achieve mid transaction failover.

Akka fast failover requires dividing your cluster into multiple failure groups. A consistent hash function will be used to decide what order each failure group should be tried in for a given operation. This means when everything is running smoothly, all operations for a given entity will always go to the same failure group. When a failure occurs, they will then consistently try on the first failure group, and then failover to the next failure group, and so on.

Akka fast failover is typically used with @extref:[Cluster Sharding](akka:cluster-sharding.html), but could also be used with a consistent hashing router.

## Dependency

To use the fast failover feature a dependency on the *akka-fast-failover* artifact must be added.

sbt
:   @@@vars
```
// Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html
"com.lightbend.akka" %% "akka-fast-failover" % "$version$"
```
@@@

Gradle
:   @@@vars
```
// Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html
dependencies {
  compile group: 'com.lightbend.akka', name: 'akka-fast-failover_$scala.binaryVersion$', version: '$version$'
}
```
@@@

Maven
:   @@@vars
```
<!-- Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html -->
<dependency>
  <groupId>com.lightbend.akka</groupId>
  <artifactId>akka-fast-failover_$scala.binaryVersion$</artifactId>
  <version>$version$</version>
</dependency>
```
@@@

@@include[includes/common.md](includes/common.md) { #find-credentials }

## Setup

In order to configure Akka for a fast failover setup, you'll need to divide your Akka cluster into multiple failure groups. If the infrastructure you're deploying to supports availability zones, then it may make sense to have a 1:1 mapping of failure groups to availability zones. Failure groups are most easily distinguished using Akka cluster roles.

You will need to have at least two failure groups. Since Akka fast failover doesn't rely on any consensus or quorum based decisions, there's nothing wrong with having only two failure groups (rather than three). The number of failure groups will dictate how many times an operation can failover.

## Starting

Here's an example of starting a fast failover actor:

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #start-fast-failover }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #start-fast-failover }

The routees are a `akka.routing.Routee` for each failure group.

The order of the routees is important, they must be the same order on every node in order for the failure group selection to be consistent. We will look at some examples of how to create routees for different setups below.

The `extractEntityId` or `messageExtractor` function extracts the entity ID from the messages it receives, it might be defined like so:

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #message-extractor }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #extract-entity-id }

In addition to operations timing out, they can also be explicitly failed by responding to the operation with an `akka.actor.Status.Failure`. When such a failure is received, the `failureStrategy` function decides how these failures should be handled. The `failureStrategy` takes an exception and returns a directive that either tells fast failover to fail the operation for good, or to attempt failover. The failure handler might look like this:

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #failure-strategy }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #failure-strategy }

### Setup with cluster sharding

If using cluster sharding, you will need to create multiple instances of cluster sharding, one for each failure group. Each node will then start a cluster sharding region for the failure group (role) that it's a part of, and a cluster sharding proxy for the other failure groups. The code for doing so looks like this:

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #cluster-sharding-routees }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #cluster-sharding-routees }

Note that the order of the routees here is important, they must use the same failure group order for every node. A possible mistake to make would be to create the shard region for the current node, and prepend it to a list of proxies for every other node, this must not be done. We do this by using a static indexed list, and then mapping the values of it to create the shard region and proxies.

It's important to note that the cluster sharding entity id and shard extractors must handle the `FastFailover.Attempt` message. The entity id from the fast failover extractor can be found in the attempt message.

### Setup with consistent hashing routers

Using a cluster aware consistent hashing router is also a useful approach to fast failover.

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #cluster-router-routees }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #cluster-router-routees }

## Implementing fast failover operations

The fast failover actor will wrap all operations in an `Attempt` message, and expect `Heartbeat` messages to be sent while the operation is active, and a `Result` message containing the response to be sent when the operation has finished. While this protocol can be manually implemented, Akka fast failover provides some transparent handling for it, via `FastFailoverHelper`. `FastFailoverHelper` provides a `handleFastFailover` receive method that can be mixed into your `receive` method to handle the fast failover protocol:

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #fast-failover-helper }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #fast-failover-helper }

### Holding messages

In some circumstances, you want to hold some messages while a fast failover operation is in progress, because they might interrupt the processing of the operation. For example, you may want to hold the message that gets sent by a shard to shut an actor down when the shard is being rebalanced, so that the fast failover operation has a chance to complete. This can be done by overriding the `shouldHoldUntilNoOperation` method. This will be invoked for every message received while there is an operation in progress. If it returns `true`, then that message will be held, and then resent when there are no active operations.

Note that holding messages implies that your actor will not always receive messages in order - any messages that get held may be overtaken by other messages that are not held. This needs to be taken into account when considering which messages should be held.

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #hold-messages }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #hold-messages }

If your actor is restarted, during an operation, then any messages that were held will be lost. This problem can be alleviated by invoking `drainHeldMessages` from the `preRestart` method of the actor, which will resend all the held messages back to `self`. Note that when the actor is restarted, the state of any in progress operations will be forgotten, so these messages will be handled immediately, rather than being reheld.

### Forwarding operations to other actors

An actor may have to forward an operation on to another actor on another node. It could do this by sending the message using a regular forward, however because the operation helper wraps the message sending in its own ask so that it can know when to stop sending heartbeats, this will mean that the response will go back through the current actor. It also means that heartbeats will be sent from the current actor, rather than from the remote actor where the work is being done, which means the wrong node will be monitored.

An actor that's using the `FastFailoverHelper` can instead respond with a `FastFailoverHelper.Forward` message, which will cause the `FastFailoverHelper` to stop sending heartbeats, and will forward the attempt on to the recipient. The message will be wrapped in an `Attempt` message, so the recipient must likewise be able to speak the fast failover protocol. It becomes the recipients responsibility to send heartbeats to prevent the operation from failing over.

This can be done like so:

Java
:   @@snip [FastFailoverMain.java](/akka-fast-failover/src/test/java/docs/FastFailoverMain.java) { #forward-messages }

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #forward-messages }


@@@ div { .group-scala }

### Fast failover mix-in (Scala only)

An even simpler way to integrate fast failover protocol support into an actor is to use the `FastFailoverSupport` mix-in. This utilises an internal Akka feature called `aroundReceive`, which may conflict with other Akka mixins like `PersistentActor`. However if such mix-ins are not being used, then the `FastFailoverSupport` trait can simply be mixed in to your actor to give you fast failover support:

Scala
:   @@snip [FastFailoverExample.scala](/akka-fast-failover/src/test/scala/docs/FastFailoverExample.scala) { #fast-failover-support }

This also provides a `drainHeldMessages` method that can be overridden, as with `FastFailoverHelper`.

@@@

## Configuration

Fast failover provides a number of things that can be configured. Configuration can either be passed programmatically, via the `FastFailoverSettings`, or loaded from configuration, for example from `application.conf`.

### Heartbeats

Fast failover uses heartbeats to detect if a node running an operation is still reachable. The node running the operation is responsible for sending heartbeats at a configured interval. The node requesting the operation will failover when heartbeats aren't received within a given timeout. As such heartbeats have two configuration properties, the heartbeat interval and the heartbeat timeout. Generally, the heartbeat timeout should be at least twice the value of the heartbeat interval.

Heartbeats are useful when the time the operation could take is longer than the time that you want to failover within when a node is detected as unreachable. If you don't want to use heartbeats, ie, if you just want to failover if an attempt at an operation takes longer than the attempt timeout, then set the heartbeat timeout and interval to something larger than the attempt timeout.

### Attempt timeouts

Each attempt at invoking an operation can be configured to have an absolute timeout, regardless of whether heartbeats are arriving for that attempt or not. This timeout should be greater than the heartbeat timeout, otherwise the heartbeat timeout will effectively be disabled.

The attempt timeout is used both on the sending side of an operation, to failover the operation when it takes too long, as well as by the fast failover helper, to timeout it's own ask operation on itself, to ensure that it will stop sending heartbeats if cases where the operation never terminates.

The attempt timeout is useful in situations where heartbeats may not reliably tell that the operation has failed, for example, a synchronous database call may never complete, resulting in a scenario where the operation never fails over because the node doing the database call is still sending heartbeats. The attempt timeout can be effectively disabled by setting it to something greater than the operation timeout.

### Operation timeouts and max failovers

Fast failover will continue attempting to failover operations until either the operation timeout is reached, or the maximum number of failovers is reached.

The operation timeout is an absolute longest that an operation should be attempted for. Once that timeout is reached, an `AskTimeoutException` wrapped in an `akka.status.Status.Failure` will be sent to the initiator of the operation. No further attempts to failover the operation will be made.

The max failovers governs how many times failover should be attempted. A value of one means that the operation will be attempted once, and if it fails or times out, it will be attempted a second time, and then no more. The error sent to the initiator will be an `AskTimeoutException` if the last failure was caused by a timeout, or the exception that triggered a failure.

### Reference

A full reference of the configuration settings available can be found here:

@@snip [reference.conf](/akka-fast-failover/src/main/resources/reference.conf) { #fast-failover-config }
