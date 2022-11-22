# Migrating existing data

First, read @ref:[Shred or delete?](./shredding.md) and @ref[Encrypting data](./encryption.md) to understand which existing events you might want to modify. Remember that in general, when using event sourcing, it is not recommended to modify events&mdash;they are meant to be immutable.

However, if you have an existing application that you want to move to use data encryption for payloads,
and eventually also make use of @ref[data shredding](./shredding.md), you can use 
`akka-persistence-update` to *update* (modify, in place) events that have been stored using Akka Persistence or Lagom's Persistent Entities.

## Dependencies

To create such a migration you will want to depend on the `akka-persistence-update`
module in addition to the `akka-gdpr` module, since we will be using its encryption capabilities:

@@dependency[sbt,Maven,Gradle] {
  group="com.lightbend.akka"
  artifact="akka-persistence-update_$scala.binaryVersion$"
  version="$version$"
}

@@dependency[sbt,Maven,Gradle] {
  group="com.lightbend.akka"
  artifact="akka-gdpr_$scala.binaryVersion$"
  version="$version$"
}

## Using `JournalUpdater` to encrypt data for a given persistenceId

The `JournalUpdater` API can perform sweeping changes to events in-place.These APIs are intended to be used for offline migrations of data. Specifically in this case, for GDPR compliance,
you may want to encrypt events using GDPR for Akka Persistence. The `akka-gdpr` module automatically encrypts events that are wrapped using
`akka.persistence.gdpr.WithDataSubjectId`. See @ref[Wrapping data in WithDataSubjectId](./using.md#wrapping-data-in-withdatasubjectid) for more information.

In order to encrypt all persisted events you may use the `transformAllPersistenceIds` operation and replace the 
existing payload with the payload wrapped in an `WithDataSubjectId`. GDPR for Akka Persistence will handle the key lookup and encryption
of such wrapped event transparently for you from there on.

When using Lagom it's recommended to use the `WithDataSubjectId` inside the events rather than wrapping the
events with `WithDataSubjectId` as described in the section @ref[Lagom PersistentEntity](./lagom.md#lagom-persistententity).

@@@ note

Note that for the updater to be able to perform its duties, the underlying Akka Persistence Journal
has to implement such update operations. Currently only the Cassandra and JDBC journals implement this feature.

The [Cassandra journal](https://github.com/akka/akka-persistence-cassandra) must be version 0.85 or later.
That version is not used by default by Lagom but it is possible and existing applications can be
[migrated](https://github.com/akka/akka-persistence-cassandra#migrations-to-080-and-later).
Contact [Lightbend Support](http://support.lightbend.com/) for detailed instructions and help with
updating the Cassandra journal version with Lagom.

The [JDBC journal](https://github.com/dnvriend/akka-persistence-jdbc) must be version 3.4.0 or later,
and that is the version that Lagom 1.4.6 is using.

@@@

Scala
:   @@snip [Wrapping]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationExamples.scala) { #migration }

Java
:   @@snip [Wrapping]($root$/../akka-persistence-update-cassandra/src/test/java/examples/gdpr/GdprMigration.java) { #migration }


You can use the snippet above to create a runnable migration app that you can execute against your systems.
We recommend first doing so on a staging environment to be sure that the key assignment logic is sound and
that replays of such migrated system continue to work properly.

You can also selectively run migrations on a specific `persistenceId` like this:

Scala
:   @@snip [justone]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationExamples.scala) { #migration-single-id }

Java
:   @@snip [justone]($root$/../akka-persistence-update-cassandra/src/test/java/examples/gdpr/GdprMigration.java) { #migration-single-id }

Finally, you can run the migration id-by-id, rather than scanning through all events, which does not guarantee 
ordering of events being ordered by persistence id. This approach may be more useful if you needed to abort a migration
and have a number of known-to-be-already-migrated events or if you know which persistenceIds need to be migrated to the
encrypted format:

Scala
:   @@snip [onebyone]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationExamples.scala) { #migration-id-by-id }

Java
:   @@snip [onebyone]($root$/../akka-persistence-update-cassandra/src/test/java/examples/gdpr/GdprMigration.java) { #migration-id-by-id }

@@@ note

See also discussion about @ref[Data Subject Id](./data-subject-id.md) and how to treat them.

@@@


## Migrating snapshots

In addition you may want to delete or migrate to their encrypted forms any snapshots a persistent actor has created. The following sections discuss:

* @ref[Deleting snapshots](#deleting-snapshots)
* @ref[Replacing with a new encrypted snapshot](#replacing-with-a-new-encrypted-snapshot)

### Deleting snapshots

Note that snapshots in general are a performance optimization, and unlike events, it is fine to drop them all up-front
when deciding to move to encrypted payloads in the snapshot store. During normal operations the system will then continue 
as usual, and any snapshot that is going to be persisted wrapped in an `akka.persistence.gdpr.WithDataSubjectId` will be
encrypted with the proper key then as well. This will in turn enable you to use the @ref[data shredding](./shredding.md) 
technique instead of deleting snapshots for any future removal deletion requests. 

<!-- There is no consistent API outside of the PersistentActor itself to perform a delete on snapshots, so we recommend that -->
<!-- In general it seels like the Updater should also be able to do this, but perhaps we're ok with just such docs here. -->

Here is a sample app that you can use to delete snapshots for all, or a subset of (by filtering the all persistence ids 
stream) your persistent actors. 

First we prepare an actor that is designed only to delete snapshots, and ignore all existing events: 

Scala
:   @@snip [snap]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationSnapshotExamples.scala) { #only-delete-snapshots-actor }

And then we make use of it by executing it for all existing persistence ids:

Scala
:   @@snip [snap]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationSnapshotExamples.scala) { #only-delete-snapshots }

### Replacing with a new encrypted snapshot

Other than deleting all snapshots and rely on the running application after doing so to recover the snapshots in normal 
operation, you may want to proactively make all actors create new snapshots using GDPR for Akka Persistence encryption so that they can
be subject to @ref:[data shredding](shredding.md).

Do to this you can use the following pattern, and instead of the example Actor here use your actual actors.
You would have to make sure to start the right type of actor for the specific persistence id though.

Note that the persistent actor performs an actual replay and recovery of its state, unlike in the "only delete the 
snapshots" application. It does so since once it has completed recovery, it will persist a snapshot yet wrap it using the 
`WithDataSubjectId` same as we were doing for events previously. GDPR for Akka Persistence will take care of properly encrypting the
payload with the right key associated to the given subject id transparently. Once this has complete successfully,
you can issue a `deleteSnapshots` similar to how this was done in the "deleting snapshots" section, however this time we
want to delete all-but-the-last snapshot -- since that one is already encrypted and we want to keep it for future use.

When using this technique do remember to make sure to start the right kind of actor for each of the ids, as otherwise you 
would accidentally attempt replaying and recovering state using wrong logic (that would not align with the data persisted for 
the given persistence id).

Also note that some actors may use the name of the actor and for those you must use the correct name when
starting the actor from a stream rather than in the normal application. Persistent actors running in Cluster
Sharding may use the actor name as the entity identifier. That is also the case for Lagom `PersistentEntity`.
Typically such name can be derived from the `persistenceId`.


Scala
:   @@snip [snap]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationSnapshotExamples.scala) { #replace-snapshots-actor }


Running the application is similar to what we did previously, we start the "updater" actor for each of the ids and 
wait for it to reply that it has completed its task by using the ask pattern:

Scala
:   @@snip [snap]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationSnapshotExamples.scala) { #replace-snapshots-actor }



