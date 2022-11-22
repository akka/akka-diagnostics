# Shopping Cart Example

The provided CRDT data structures can be used as the root state of a `ReplicatedEntity` but they can also be nested inside another data structure. This requires a bit more careful thinking about the eventual consistency.
 
In this sample we model a shopping cart as a map of product ids and the number of that product added or removed in the shopping cart. By using the `Counter` CRDT and persisting its `Update` in our events we can be sure that an add or remove of items in any data center will eventually lead to all data centers ending up with the same number of each product. 
 
 With this model we can not have a `ClearCart` command as that could give different states in different data centers. It is quite easy to imagine such a scenario: commands arriving in the order `ClearCart`, `AddItem('a', 5)` in one data center and the order `AddItem('a', 5), ClearCart` in another.
 
 To clear a cart a client would instead have to remove as many items of each product as it sees in the cart at the time of removal.

Required imports:

Scala
:   @@snip [ShoppingCartExample](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/ShoppingCartExampleSpec.scala)  { #imports }

Java
:   @@snip [ShoppingCartExample](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/ShoppingCartExampleTest.java)  { #imports }

The `ReplicatedEntity` implementation:

Scala
:   @@snip [ShoppingCartExample](/akka-persistence-multi-dc-tests/src/test/scala/akka/persistence/multidc/scaladsl/ShoppingCartExampleSpec.scala)  { #full-example }

Java
:   @@snip [ShoppingCartExample](/akka-persistence-multi-dc-tests/src/test/java/akka/persistence/multidc/javadsl/ShoppingCartExampleTest.java)  { #full-example }
