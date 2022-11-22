# Using akka-gdpr

The `akka-gdpr` module contains utilities to help you manage data shredding, including:

* A `GdprEncryption` extension point that you can use to plug your own encryption. Alternatively, you can use the `AbstractGdprEncryption` class that provides encryption but requires you to implement key management. 
* A serializer that encrypts events and adds a data subject id, when you wrap events with `WithDataSubjectId`. You must provide your own serializer for events that reference multiple data subjects. If you are using Jackson or Play JSON for serialization, the `akka-gdpr` module allows you to use them for encryption. 

## Dependency

To use the GDPR for Akka Persistence feature, add a dependency for the *akka-gdpr* artifact:

sbt
:   @@@vars
```
// Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html
"com.lightbend.akka" %% "akka-gdpr" % "$version$"
```
@@@

Gradle
:   @@@vars
```
// Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html
dependencies {
  compile group: 'com.lightbend.akka', name: 'akka-gdpr_$scala.binaryVersion$', version: '$version$'
}
```
@@@

Maven
:   @@@vars
```
<!-- Add Lightbend Platform to your build as documented at https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-started/subscription-and-credentials.html -->
<dependency>
  <groupId>com.lightbend.akka</groupId>
  <artifactId>akka-gdpr_$scala.binaryVersion$</artifactId>
  <version>$version$</version>
</dependency>
```
@@@

@@include[../includes/common.md](../includes/common.md) { #find-credentials }

## How the module works

Once you have implemented the `GdprEncryption` extension, for example, by extending `AbstractGdprEncryption` and
implementing the `KeyManagement`, the `GdprSerializer` class in `akka-gdpr` handles the encryption for you. The `GdprSerializer` is automatically bound for serialization of `WithDataSubjectId` classes:

@@snip [reference.conf](/akka-gdpr/src/main/resources/reference.conf) { #serializers }

The `GdprSerializer` will do the following:

1. Each of your events is first serialized via Akka's serialization extension.

1. Then the payload is encrypted with AES in GCM mode with no padding. Initialization
vectors are created for each new payload using a `SecureRandom` and are the same length
as the chosen key. 

1. The Initialization vector is then stored at the start of the encrypted payload.

1. The  payload is then added to a protobuf message:

@@snip [Wrapping]($root$/../akka-gdpr/src/main/protobuf/GDPR.proto) { #proto }

We provide these details so that you can read the events and validate the cryptographic approach.


## General steps

General steps to use the `akka-gdpr` module include:

1. Add a dependency to your build as described in @ref[Adding the akka-gdpr module dependency](#dependency).

1. For encryption, choose from the following:  
     * The `GdprEncryption` extension, which allows you to have full control over encryption algorithms by defining three methods that you can implement. See @ref[GdprEncryption](./encryption.md#gdprencryption) for more details.
     * Extend the `AbstractGdprEncryption` class, which implements encryption and decryption for you, but requires you to use the `KeyManagement` interface to plug in the logic for creating and retrieving keys. See @ref[AbstractGdprEncryption](./encryption.md#abstractgdprencryption) for more details.
     * The `JavaKeyStoreGdprEncryption`class implements `GdprEncryption` with support for PKCS12 and JCEKS keystores. See @ref[JavaKeyStoreGdprEncryption](./encryption.md#javakeystoregdprencryption) (This is only appropriate for single node applications.)
   
1. Define the `akka.persistence.gdpr.encryption-provider` setting to point to a configuration
block for your encryption implementation, as described in @ref[Pointing to your encryption implementation](./encryption.md#pointing-to-your-encryption-implementation).

1. Use the `WithDataSubjectId` class by @ref[wrapping data](#wrapping-data-in-withdatasubjectid), or inside of events. For events that contain multiple data subjects, you will need to implement your own serializer, as described in @ref[Events with multiple subjects](#events-with-multiple-subjects).

1. When there is a request to forget a data subject you should call the `shred` method of the `GdprEncryption` extension:

Scala
:   @@snip [example]($root$/../akka-gdpr/src/test/scala/docs/gdpr/scaladsl/ExampleGdprEncryption.scala) { #shred-usage }

Java
:   @@snip [example]($root$/../akka-gdpr/src/test/java/docs/gdpr/javadsl/ExampleGdprEncryption.java) { #shred-usage }
 
For unit testing during development, you can use `TestGdprEncryption`, which holds generated keys in memory and can be enabled with configuration. See @ref[TestGdprEncryption](./encryption.md#testgdprencryption) for more details.

See also:

- @ref[Using akka-gdpr with Lagom PersistentEntity](./lagom.md)
- @ref[Using akka-gdpr with other data storage](./other-storage.md)

## Wrapping data in WithDataSubjectId

The GDPR for Akka Persistence module provides a `WithDataSubjectId` class. Wrap events that require encryption in this class. When `WithDataSubjectId` is serialized, the `payload` (the actual event) is encrypted with the key that is identified by the
`dataSubjectId` in the `WithDataSubjectId`. To shred the data, as described in @ref[The right to be forgotten](./shredding.md), remove the encryption key to make the encrypted payload unreadable.

When the encrypted payload of `WithDataSubjectId` is deserialized and the encryption key doesn't exist
any more, the @scala[`payload` is represented as `None`]@java[`getPayload()` is represented as `Optional.empty()`].
You need to take this value into account when replaying or processing the events.

If you're using `Tagged` events then the `Tagged` class should remain on the outside e.g.

@@snip [Wrapping]($root$/../akka-gdpr/src/test/scala/akka/persistence/gdpr/CustomerActor.scala) { #wrapping }

## Events with multiple subjects

As described above, events wrapped in `WithDataSubjectId` are automatically serialized with encryption.
When parts of the data in an event &mdash; or more typically in snapshots &mdash; belong to different subjects you have
to implement that in _your_ serializer. Each `WithDataSubjectId` part must be represented as bytes in the
stored representation and you should use the `WithDataSubjectIdSerialization` utility to serialize each
`WithDataSubjectId` to/from the bytes.

Here is an example of a serializer for a snapshot that contains two separate `WithDataSubjectId` parts.

The protobuf definition of the snapshot:

@@snip [TestMessages.proto](/akka-gdpr/src/test/protobuf/TestMessages.proto) { #proto }

The snapshot class:

Scala
:   @@snip [Snapshot]($root$/../akka-gdpr/src/test/scala/akka/persistence/gdpr/GdprSerializerPartsSpec.scala) { #snapshot }

Java
:   @@snip [Snapshot]($root$/../akka-gdpr/src/test/java/akka/persistence/gdpr/javadsl/GdprSerializerPartsTest.java) { #snapshot }

The serializer of the `Snapshot`:

Scala
:   @@snip [SnapshotSerializer]($root$/../akka-gdpr/src/test/scala/akka/persistence/gdpr/GdprSerializerPartsSpec.scala) {
  #import
  #snapshot-serializer
}

Java
:   @@snip [SnapshotSerializer]($root$/../akka-gdpr/src/test/java/akka/persistence/gdpr/javadsl/GdprSerializerPartsTest.java) {
  #import
  #snapshot-serializer
}

Note that the serializer should extend @java[`AsyncSerializerWithStringManifestCS`]@scala[`AsyncSerializerWithStringManifest`]
and implement methods `toBinaryAsync` and `fromBinaryAsync` that return @java[`CompletionStage`]@scala[`Future`].
The reason for using the asynchronous API instead of the ordinary `SerializerWithStringManifest` is that encryption
typically involves file or network IO; blocking the serialization thread while waiting for such things to complete
should be avoided. The encryption calls are managed by GDPR for Akka Persistence and are running on a separate dispatcher dedicated
for blocking tasks to avoid starvation of other parts of the system. To compose those asyncronous encryption tasks
the serializer must also be asynchronous and chain the calls using @java[`thenApply`]@scala[`map`] and
@java[`thenCompose`]@scala[`flatMap`] operations of the @java[`CompletionStage`]@scala[`Future`. In the above
example `for` comprehension is used for doing that.]



