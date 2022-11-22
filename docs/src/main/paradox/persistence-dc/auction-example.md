# Auction Example

@scala[Having to use CRDTs for replication might seem like a severe constraint because the set of predefined CRDTs is quite limited.]
In this example we want to show that real-world applications can be implemented by designing events in a way that they
don't conflict. In the end, you will end up with a solution based on a custom CRDT.

We are building a small auction service. An auction is represented by one replicated actor. It should have the following operations:

 * Place a bid
 * Get the currently highest bid
 * Finish the auction

We model those operations as commands to be sent to the auction actor:

Scala
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/AuctionExampleSpec.scala) { #auction-commands }

Java
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java) { #auction-commands }

The auction entity is an event-sourced persistent actor. These events are used to persist state changes:

Scala
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/AuctionExampleSpec.scala) { #auction-events }

Java
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java) { #auction-events }

You may have noticed here, that we include the `highestCounterOffer` in the `AuctionFinished` event. This is because we use a
popular auction style where the winner does not have to pay the highest bidden price but only just enough to beat the second
highest bid.

Let's have a look at the auction entity that will handle incoming commands:

Scala
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/AuctionExampleSpec.scala) { #auction-actor }

Java
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java) { #auction-actor }

The auction entity is started with the initial parameters for the auction. As seen before, replicated entities need to be
parameterized with the types for commands and events and also for the internal state.

In the `initialState` method, a replicated entity needs to define its original state. In our case, it's straightforward to initialize
the initial state from our initialization parameters as given in the `AuctionSetup` instance. The minimum bid is in our case modelled as
an `initialBid`.

The `actions` defines how to react to external commands. In our case, for `OfferBid` and `AuctionFinished` we do nothing more than to emit
events corresponding to the command. For `GetHighestBid` we respond with details from the state. Note, that we overwrite the actual
offer of the highest bid here with the amount of the `highestCounterOffer`. This is done to follow the popular auction style where
the actual highest bid is never publicly revealed.

Let's have a look at our state class, `AuctionState` which also represents the CRDT in our example.

Scala
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/AuctionExampleSpec.scala) { #auction-state }

Java
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java) { #auction-state }

The state consists of a flag that keeps track of whether the auction is still active, the currently highest bid,
and the highest counter offer so far.

In the `eventHandler`, we handle persisted events to drive the state change. When a new bid is registered,

 * it needs to be decided whether the new bid is the winning bid or not
 * the state needs to be updated accordingly

The point of CRDTs is that the state must be end up being the same regardless of the order the events have been processed.
We can see how this works in the auction example: we are only interested in the highest bid, so, if we can define an
ordering on all bids, it should suffice to compare the new bid with currently highest to eventually end up with the globally
highest regardless of the order in which the events come in.

The ordering between bids is crucial, therefore. We need to ensure that it is deterministic and does not depend on local state
outside of our state class so that all replicas come to the same result. We define the ordering as this:

 * A higher bid wins.
 * If there's a tie between the two highest bids, the bid that was registered earlier wins. For that we keep track of the
   (local) timestamp the bid was registered.
 * We need to make sure that no timestamp is used twice in the same DC (missing in this example).
 * If there's a tie between the timestamp, we define an arbitrary but deterministic ordering on the DCs, in our case
   we just compare the name strings of the DCs. That's why we need to keep the identifier of the DC where a bid was registered
   for every `Bid`.

If the new bid was higher, we keep this one as the new highest and keep the amount of the former highest as the `highestCounterOffer`.
If the new bid was lower, we just update the `highestCounterOffer` if necessary.

Using those rules, the order of incoming does not matter. Replicas in all DCs should eventually converge to the same result.

#### Open questions

The auction example shows basic features of an auction. There are a few additional considerations

 * Replica only eventually converge to the same result. That might lead to surprising results because highest bids from other replicas than
   the local one might only turn up with a delay. Another surprising result might be that two bids with the same amount issued each to different
   replicas in quick succession might be ordered differently due clock differences between replicas. In a real bidding system, it needs to be made sure
   that no replica has a competitive advantage over another one.
 
#### Complete example source code

For reference here's the complete example, including imports and tests:

Scala
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/AuctionExampleSpec.scala)

Java
:   @@snip [AuctionExample](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/AuctionExampleTest.java)
