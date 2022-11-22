# Encrypting data

As described in @ref[How to use the akka-gdpr module](using.md), you can take advantage of the provided encryption implementation or plug in your own. The `akka-gdpr` module also provides options for key management and for testing during development. The sections on this page describe how to add a dependency on `akka-gdpr` and provide more detail on working with `GdprEncryption`, `AbstractGdprEncryption`, `JavaKeyStoreGdprEncryption`,  and `TestGdprEncryption`.

*Note:* Implementations of `GdprEncryption`, `AbstractGdprEncryption` and `KeyManagement` must be thread-safe,
as they may be accessed by multiple threads concurrently.


## GdprEncryption

The GDPR for Akka Persistence module defines a `GdprEncryption` extension that you need to implement to plug in encryption and key
management. The @scala[`akka.persistence.gdpr.scaladsl.GdprEncryption`]@java[`akka.persistence.gdpr.javadsl.GdprEncryption`]
extension has three methods to implement:

* `encrypt` - Encrypt the given payload with the key identified by `dataSubjectId`.
* `decrypt` - Decrypt the given payload with the key identified by `dataSubjectId`, unless the key has been removed.
* `shred` - Remove the given `dataSubjectId`.

If you need full control over encryption algorithms you can implement this interface. However, it is expected
most implementations will extend @scala[`akka.persistence.gdpr.scaladsl.AbstractGdprEncryption`]@java[`akka.persistence.gdpr.javadsl.AbstractGdprEncryption`].

## AbstractGdprEncryption

The `AbstractGdprEncryption` implements all of the methods from `GdprEncryption` but adds a `KeyManagement` interface, which enables you to plug in logic for creating new keys and retrieving keys.

The `KeyManagment` interface has three methods to implement:

* `shred` - Remove the key identified by the given `dataSubjectId` permanently.
* `getOrCreateKey` - Create a `SecretKey` if it doesn't exist already.
* `getKey` - Retrieve the key identified by the given `dataSubjectId`, returning None if the key has been shredded.

An example without actual implementation of the methods may look like this:

Scala
:   @@snip [example]($root$/../akka-gdpr/src/test/scala/docs/gdpr/scaladsl/ExampleGdprEncryption.scala) { #example }

Java
:   @@snip [example]($root$/../akka-gdpr/src/test/java/docs/gdpr/javadsl/ExampleGdprEncryption.java) { #example }

## JavaKeyStoreGdprEncryption

An existing implementation of `GdprEncryption` is included in `JavaKeyStoreGdprEncryption`.
It has support for PKCS12 and JCEKS keystores. It can only be used for single node applications
since it saves to a local file so won't be available for other nodes in the cluster.
If your application is distributed you'll need to create a `KeyManagement` implementation for your distributed secret
store e.g. [Vault](https://www.vaultproject.io/). `JavaKeyStoreGdprEncryption` can be enabled with configuration:

```
akka.persistence.gdpr.encryption-provider = "akka.persistence.gdpr.jca-provider"
```

It's also important to be aware of that the the `JavaKeyStoreGdprEncryption` doesn't have any redundancy, so if
the storage is corrupted for some reason all encrypted data is "lost".

To configure `JavaKeyStoreGdprEncryption` to use a local PKCS12 or JCEKS key store you need to specify:

* Location of your keystore file
* Password for your keystore
* Key size (keys will be generated for new data subjects)
* Keystore type

@@snip [reference.conf](/akka-gdpr/src/main/resources/reference.conf) { #jca }

It's recommended to configure the `key-size` to 256, which may require installation of
[Java Cryptography Extension (JCE)](http://www.oracle.com/technetwork/java/javase/downloads/jce8-download-2133166.html).

```
akka.persistence.gdpr.key-size = 256
```


## TestGdprEncryption

For unit testing purposes `TestGdprEncryption` is provided. Don't use this test module in production, since it is only
holding the generated keys in memory. `TestGdprEncryption` can be can be enabled with the following configuration:

```
akka.persistence.gdpr.encryption-provider = "akka.persistence.gdpr.test-provider"
```
## Pointing to your encryption implementation

Define the `akka.persistence.gdpr.encryption-provider` setting to point to a configuration
block for your encryption implementation. The only mandatory field in that block is `class`, which is a fully qualified
class name of your implementation of `GdprEncryption`. The class must have a constructor that takes in an
`ExtendedActorSystem` and the config path as a `String`.

@@snip [reference.conf](/akka-gdpr/src/main/resources/reference.conf) { #provider }

We recommend configuring the `key-size` to 256, which may require installation of
[Java Cryptography Extension (JCE)](http://www.oracle.com/technetwork/java/javase/downloads/jce8-download-2133166.html).

```
akka.persistence.gdpr.key-size = 256
```


