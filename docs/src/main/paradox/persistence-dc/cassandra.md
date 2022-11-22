# Cassandra

## Setting up Cassandra for multi data centers

See [Cassandra documentation](https://docs.datastax.com/en/cassandra/3.0/cassandra/initialize/initMultipleDS.html) for production deployments.

For local development and test a single local Cassandra server can be used.

The [CassandraLauncher](https://github.com/akka/akka-persistence-cassandra/blob/master/cassandra-launcher/src/main/scala/akka/persistence/cassandra/testkit/CassandraLauncher.scala) can be useful in tests.

[CCM](https://github.com/pcmanus/ccm) is useful for running a local Cassandra cluster.

## Cassandra usage in Akka Persistence Multi-DC

Multi-DC persistence can be used with Cassandra in two different ways:

* Use Cassandra's data center replication under the covers to deliver events to all `ReplicatedEntity`s with the same
`entityId` across all data centers. This is the default mode and Cassandra must be configured for [multiple data centers](https://docs.datastax.com/en/cassandra/3.0/cassandra/initialize/initMultipleDS.html) for production deployments.
* Use separate Cassandra cluster for each data center and retrieve the events from another data centers with ordinary Cassandra queries to the Cassandra cluster in the other data center. This mode is described in the [Cross reading](#cross-reading) section.

It is important to understand the additional read and write load this will put on your Cassandra cluster as well as the additional storage requirements. The documentation assumes that you are already familiar with how [akka-persistence-cassandra](https://github.com/akka/akka-persistence-cassandra) stores events and uses `nRF` to refer to the Cassandra replication factor, `nDC` to refer to the number of data centers and `nRE` to refer to the number of replicated entities. 

## Storage and replication

Each `ReplicatedEntity` instance has it's own unique `persistenceId` and thereby it's own event log. The persisted events are written with `LOCAL_QUORUM` write consistency and also read with `LOCAL_QUORUM` during recovery. `LOCAL_QUORUM` means that it requires successful writes/reads from a majority of Cassandra replicas in the local data center.

The `persistenceId` is constructed by concatenating the `entityId` with the identifier of the data center where the `ReplicatedEntity` is running. In another data center another `ReplicatedEntity` instance for the same `entityId` may be running and it will have a different `persistenceId`, and event log.

When a `ReplicatedEntity` is started it starts a stream that queries events from the event log of corresponding replicated entities in the other data centers. To do that it needs to know the `persistenceId` for the other instances. Those are known by the configuration property `akka.persistence.multi-dc.all-data-centers` and the concatenation convention.

This stream is infinite and restarted with backoff in case of failures. The stream is essentially periodically polling Cassandra for new data for each `persistenceId`. To be able to scale to many entities the polling frequency is adjusted dynamically based on which entities that are active. More about that in a moment.

The events found by this stream is what we call replicated events. The actual replication is ordinary Cassandra replication across data centers.

When an event is persisted by a `ReplicatedEntity` some additional meta data is stored together with the event. The meta data is stored in the `meta` column in the journal (`messages`) table used by `akka-persistence-cassandra`. The reason for storing the meta data in a separate column instead of wrapping the original event is that it should be seamless to migrate away from this tool, if needed, and still be able to read the events without any additional dependencies.

The meta data for each event contains:

* timestamp
* data center identifier
* version vector (corresponding to the sequence numbers in each DC)

After applying the replicated event it is also persisted in the event log of the consuming `ReplicatedEntity`. Additional meta data is stored with this event also, which shows that it is a handled event to break the replication cycle. Those handled events are replayed when a `ReplicatedEntity` is recovering and that is the way it knows the sequence number to start at when starting the replicated events stream.

This means that each event is stored one additional time in each data center. That additional event is also replicated to other data centers. In Cassandra you define a replication factor for each data center. Counting all copies of an event when using a replication factor of 3 it looks like:

* 1 DC: 3 copies
* 2 DCs: 12 copies
* 3 DCs: 27 copies

The number of copies can be reduced by using the [cross reading](#cross-reading) mode instead of using Cassandra's data center replication. The cross reading mode is recommend when using more than 3 data centers. 

As mentioned above the replicated events stream is polling Cassandra for new data for each `persistenceId`. To reduce this polling to the entities that are active a notification mechanism is used. When an event has been stored a notification is stored in a separate table in Cassandra. Those notifications are aggregated and written in the background with consistency level `ONE`. Delivery of the notifications doesn't have to be reliable. In the other data center those notifications are read periodically to find which entities that are active and would have new events to read. For inactive entities the polling of new events are only done at a low frequency, in case the notifications are not delivered.

## Journal polling

Each `ReplicatedEntity` in each data center needs to poll for events that happen in other data centers.

Each active `ReplicatedEntity` polls the `journal` table every `low-frequency-read-events-interval` which by default is 30s. The
data center name is in the partition key so there is a query per other data center. This will adds `nRE * (nDC - 1) / 0.5` reads per minute in each data center.

For example, with the default configuration, if you have 10,000 replicated entities per data center and 3 data centers, you'll get:

`(10,000 * 2 * 2) = 40,000` reads per minute at `LOCAL_QUORUM` in each data center. Or if evenly distributed ~666 TPS.

## Notifications 

Periodic notifications are stored in a table to allow querying of the `journal` table to be infrequent. 
This is achieved by having all the relevant rows in the notifications table in a small number (last X `timebucket`s) of deterministic partitions.

Periodic writes are done by each ActorSystem summarising which replicated entities have persisted events to the following table:

@@snip [NotificationsTable](/akka-persistence-multi-dc/src/main/scala/akka/persistence/multidc/internal/CassandraReplicatedStatements.scala) { #notifications-table }

Using `timebucket` as a partition key is normally discouraged as it produces a hot spot for reads/writes in Cassandra. However 
this is a very low throughput table i.e. the number of queries does not increase with the number of replicated entities. 


### Notification writes

A write is done to this table every `cassandra-journal-multi-dc.notification.write-interval` * nr of ActorSystems. This write is a summary
 of all the events the `ReplicatedEntity`s have persisted. 

By default this is once a second so write notifications will add one write per Actor system per second to your Cassandra cluster.

Unless you have a huge Akka cluster then this write overhead will be negligible. 
For example, with the default configuration, a 60 node Akka Cluster will produce 60 reads per second against Cassandra at consistency `ONE`.

### Notification reads

With the default configuration each ActorSystem will have a notification reader polling every 500ms with the following query:

@@snip [NotificationsReadQuery](/akka-persistence-multi-dc/src/main/scala/akka/persistence/multidc/internal/CassandraReplicatedStatements.scala) { #notifications-reads }


Where timeBucket is the partition key and timestamp is a clustering column. This is a single partition query and the total number
will be 2 per actor system per minute. Unless you have a huge cluster this read load will be negligible. 

The read will be either for the current `timebucket` or a recent one to pick up any missed notifications. 

For example, with the default configuration, a 60 node Akka Cluster will produce 120 reads per second. 
These will all be for the same small set of partitions but as the read load is small this should not case any issues.

## Cross reading

### Separate Cassandra clusters

You can configure Multi-DC persistence to use a separate Cassandra cluster for each data center and not use 
Cassandra's data center replication for the events. The events are then retrieved from another data center 
with ordinary Cassandra queries to the Cassandra cluster in the other data center. Those replicated events
are then also persisted in the event log of the consuming `ReplicatedEntity`. Those handled events are 
replayed when a `ReplicatedEntity` is recovering, i.e. the cross reading is not used for recovery.

As described in the [Storage and replication](#storage-and-replication) section the number of data copies 
increases dramatically with the number of data centers. The cross reading mode is recommend when using more 
than 3 data centers. It means that each event is stored one additional time in each data center, which also
should be multiplied with the ordinary Cassandra replication factor. For example, replication factor of 3 and
4 DCs mean 12 copies in total.

To enable the cross reading you have to add this type of configuration:

```
akka.persistence.multi-data-center.cross-reading-replication {
  enabled = on
  
  cassandra-journal {
    # One section per DC that defines the contact-points, keyspace and such of that DC,
    # for example:
    eu-west {
      contact-points = ["eu-west-node1", "eu-west-node2"]
      keyspace = "akka_west"
      local-datacenter = "eu-west"
      data-center-replication-factors = ["eu-west:3"]
    }
    eu-central {
      contact-points = ["eu-central-node1", "eu-central-node2"]
      keyspace = "akka_central"
      local-datacenter = "eu-central"
      data-center-replication-factors = ["eu-central:3"]
    }
  }
}

# below is config for eu-west, similar for eu-central (replace west with central) 
cassandra-journal-multi-dc {
  contact-points = ["eu-west-node1", "eu-west-node2"]
  keyspace = "akka_west"
  local-datacenter = "eu-west"
  replication-strategy = "NetworkTopologyStrategy"
  data-center-replication-factors = ["eu-west:3"]
}
```

You have to define one section for each data center that was defined in `akka.persistence.multi-data-center.all-data-centers`.
The properties will override any property in `cassandra-query-journal-multi-dc` and `cassandra-journal-multi-dc`.

Note that these connections to Cassandra nodes in other data centers are only used for reading data,
never for writing.

### One Cassandra cluster, separate keyspaces

There is also a hybrid alternative, where the [notifications](#notifications) table is replicated by Cassandra's 
data center replication and the events are retrieved with cross reading. One Cassandra cluster is used.
To enable this mode you have to add this type of configuration:

```
akka.persistence.multi-data-center.cross-reading-replication {
  enabled = on
  local-notification = on
  
  cassandra-journal {
    # One section per DC that defines the contact-points, keyspace and such of that DC,
    # for example:
    eu-west {
      contact-points = ["eu-west-node1", "eu-west-node2"]
      keyspace = "akka_west"
      local-datacenter = "eu-west"
      data-center-replication-factors = ["eu-west:3"]
    }
    eu-central {
      contact-points = ["eu-central-node1", "eu-central-node2"]
      keyspace = "akka_central"
      local-datacenter = "eu-central"
      data-center-replication-factors = ["eu-central:3"]
    }
  }
}

# below is config for eu-west, similar for eu-central (replace west with central) 
cassandra-journal-multi-dc {
  contact-points = ["eu-west-node1", "eu-west-node2"]
  keyspace = "akka_west"
  local-datacenter = "eu-west"
  replication-strategy = "NetworkTopologyStrategy"
  data-center-replication-factors = ["eu-west:3"]

  # this is needed when using cross-reading-replication.local-notification = on
  notification {
    keyspace = "akka_notification"
    replication-strategy = "NetworkTopologyStrategy"
    data-center-replication-factors = ["eu-west:3", "eu-central:3"]
  }
}
```

Note that when `local-notification = on` you should define another `keyspace` for the notifications table,
so that cross DC `data-center-replication-factors` can be used for that `keyspace`. 
