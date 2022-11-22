# Other data storage

You can use the GDPR for Akka Persistence encryption utilities even if you are not using Akka Persistence or
Lagom Persistence. However, first consider whether it's easier to perform ordinary removal of the data instead of encrypting and shredding it.

Here is an example of how to use the `WithDataSubjectIdSerialization` utility in a JDBC data access object.
The same technique can be used with @java[[Lagom's Read-side](https://www.lagomframework.com/documentation/current/java/ReadSide.html)]
@scala[[Lagom's Read-side](https://www.lagomframework.com/documentation/current/scala/ReadSide.html)], plain JPA, a NoSQL key value store, or many other data stores.

Each `WithDataSubjectId` part must be represented as bytes (blob) in the stored representation and you should
use the `WithDataSubjectIdSerialization` utility to serialize each `WithDataSubjectId` to/from the bytes.

Scala
:   @@snip [crud]($root$/../akka-gdpr/src/test/scala/akka/persistence/gdpr/CrudDocSpec.scala) { #crud }

Java
:   @@snip [crud]($root$/../akka-gdpr/src/test/java/akka/persistence/gdpr/javadsl/CrudDocTest.java) { #crud }

   