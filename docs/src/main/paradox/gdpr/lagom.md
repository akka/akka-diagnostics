# Lagom specifics

GDPR for Akka Persistence can be used with @java[[Lagom PersistentEntity](https://www.lagomframework.com/documentation/current/java/PersistentEntity.html)]
@scala[[Lagom PersistentEntity](https://www.lagomframework.com/documentation/current/scala/PersistentEntity.html)], and provides encryption for `WithDataSubjectId` when you are using Jackson or Play JSON for serialization.


##  Lagom PersistentEntity

The `PersistentEntity` defines the type for events and therefore we recommend using the `WithDataSubjectId`
inside the events rather than wrapping the events with `WithDataSubjectId`. To wrap the full events, the
event type of the `PersistentEntity` would have to be @java[`WithDataSubjectId<Object>`]@scala[`WithDataSubjectId[AnyRef]`]
and the benefits of the type information would not be available.

Using `WithDataSubjectId` inside the events and snapshots requires no additional effort when the JSON serializer
is used.

Enable the
@java[`GdprModule` for Jackson serializion as described in @ref[Serialization with Jackson](#serialization-with-jackson)]
@scala[Play JSON format as described @ref[Serialization with Play JSON](#serialization-with-play-json)].

Here is an example of a `PersistentEntity` that is using `WithDataSubjectId` in an event and snapshot.

The event:

Java
:   @@snip [event]($root$/../akka-gdpr-lagom-example/hello-impl/src/main/java/com/example/hello/impl/HelloEvent.java) { #event }

The snapshot:

Java
:   @@snip [snapshot]($root$/../akka-gdpr-lagom-example/hello-impl/src/main/java/com/example/hello/impl/HelloState.java) { #snapshot }

The entity:

Java
:   @@snip [entity]($root$/../akka-gdpr-lagom-example/hello-impl/src/main/java/com/example/hello/impl/HelloEntity.java) { #entity }



## Serialization with Jackson

When using [Jackson](https://github.com/FasterXML/jackson) for serialization of event and snapshots the `akka-gdpr-jackson` module can be used to
automatically encrypt `WithDataSubjectId` both at the top level or as parts inside the data structure.

Add this dependency for Jackson serialization:

@@dependency[sbt,Maven,Gradle] {
  group=com.lightbend.akka
  artifact=akka-gdpr-jackson_$scala.binaryVersion$
  version=$version$
}

Then register the Jackson module `akka.persistence.gdpr.jackson.GdprModule` in the `ObjectMapper`.

When using [Lagom JSON Serialization with Jackson](https://www.lagomframework.com/documentation/current/java/Serialization.html#Enabling-JSON-Serialization)
this is done with configuration:

@@snip [gdpr-module]($root$/../akka-gdpr-lagom-example/hello-impl/src/main/resources/application.conf) { #gdpr-module }

Register the Jackson serializer for the `WithDataSubjectId` class. Add the following configuration when using Lagom:

```
akka.actor {
  serialization-bindings {
    "akka.persistence.gdpr.WithDataSubjectId" = lagom-json
  }
}
```

The `WithDataSubjectId` is serialized as JSON:

```
{
  "dataSubjectId": "bobId",
  "payload": "CgVib2JJZBKRAXQVOzc8ZaMy3Z/N9VUfb1/uoIPEAi7R+fLwgkJhQxbBfqKRxFuYpm7dpjip5tDpSiN2UQIue+71i70m63E4RQb6vq6IUzx3nX57GkKe01qMd3R4/sTUNwjIldC+bob9TH+DBAI5QG9K4oBEiA3iWdcYTFCNhS8BBg6hUwxtYwbmWi5q9c/TtvmWLznlCNFC9e0YGyIQamF2YS5sYW5nLlN0cmluZw=="
}
```

Where the `payload` is Jackson's binary encoding (Base64) of the Protobuf representation of the `WithDataSubjectId`
as described in @ref[How the module works](./using.md#how-the-module-works), including encryption of the actual payload data.

## Serialization with Play JSON

When using [Lagom JSON Serialization with Play JSON](https://www.lagomframework.com/documentation/current/scala/Serialization.html#Enabling-JSON-Serialization)
for serialization of event and snapshots the `akka-gdpr-playjson` module can be used to
automatically encrypt `WithDataSubjectId` both at the top level or as parts inside the data structure.

Add this dependency for Play JSON serialization:

@@dependency[sbt,Maven,Gradle] {
  group=com.lightbend.akka
  artifact=akka-gdpr-playjson_$scala.binaryVersion$
  version=$version$
}

Register the `Format` for `WithDataSubjectId` class in Lagom's `JsonSerializerRegistry` as described in the
[Lagom documentation](https://www.lagomframework.com/documentation/current/scala/Serialization.html#Enabling-JSON-Serialization).

The `Format` for `WithDataSubjectId` is provided by `akka-gdpr-playjson` in `GdprFormat.withDataSubjectIdFormat`
and can be defined in a `JsonSerializerRegistry` like this:

Scala
:   @@snip [Snapshot]($root$/../akka-gdpr-playjson/src/test/scala/akka/persistence/gdpr/playjson/PlayJsonSerializerSpec.scala) { #example }

Note how the `import GdprFormat.withDataSubjectIdFormat` adds the format for `WithDataSubjectId[_]` in implicit
scope and can be used for top level `WithDataSubjectId`. For nested `WithDataSubjectId` you must define a more
specific type with `GdprFormat.specificWithDataSubjectIdFormat`. For example
`specificWithDataSubjectIdFormat[String]` and `specificWithDataSubjectIdFormat[PersonalInformation]` are
used in the above example.

The `WithDataSubjectId` is serialized as JSON:

```
{
  "dataSubjectId": "bobId",
  "payload": "CgVib2JJZBKRAXQVOzc8ZaMy3Z/N9VUfb1/uoIPEAi7R+fLwgkJhQxbBfqKRxFuYpm7dpjip5tDpSiN2UQIue+71i70m63E4RQb6vq6IUzx3nX57GkKe01qMd3R4/sTUNwjIldC+bob9TH+DBAI5QG9K4oBEiA3iWdcYTFCNhS8BBg6hUwxtYwbmWi5q9c/TtvmWLznlCNFC9e0YGyIQamF2YS5sYW5nLlN0cmluZw=="
}
```

Where the `payload` is Jackson's binary encoding (Base64) of the Protobuf representation of the `WithDataSubjectId`
as described in @ref[How the module works](./using.md#how-the-module-works), including encryption of the actual payload data.





