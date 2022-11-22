# Shred or delete?

The @ref[Overview](./index.md) introduces the approach of forgetting personal information by shredding, that is, encrypting the data with a key for a
given data subject id (person) and deleting the key when that data subject is to be forgotten. It is important to analyze all places personal information might be stored, and to consider whether it will be reachable through the data subject id. 

Shredding will be sufficient in many cases, however, there are others where deletion will be required. And, you should ensure that each service is responsible for storing its information in a GDPR compliant way. Requests to be forgotten should be propagated between services in the same way that other changes are propagated.

The following topics discuss shredding and deletion:

- @ref[Data shredding use cases](#data-shredding-use-cases)
- @ref[In memory state](#in-memory-state)
- @ref[Projections and consumers](#projections-and-consumers)
- @ref[Message brokers](#message-brokers)
- @ref[Backups](#backups)
- @ref[Logs](#logs)



## Data shredding use cases

Suppose that we have a persistent entity representing a user. It might be implemented as an
@extref:[Akka Persistent Actor](akka:persistence.html), a
@java[@extref:[Lagom PersistentEntity](lagom:java/PersistentEntity.html)]
@scala[@extref:[Lagom PersistentEntity](lagom:scala/PersistentEntity.html)], or a
@ref[Multi-DC ReplicatedEntity](../persistence-dc/index.md). The first thought might be to try to
completely delete that entity by deleting all its events and snapshots. Unfortunately that isn't enough, because
the events may be stored in additional places, such as the tagged representation of the events in the Cassandra plugin
for Akka Persistence. Such denormalized views of the data will not be deleted when the original events are deleted.
It can be difficult to find and delete that data because it is typically indexed on something else than the entity
identifier.

Instead, we can *encrypt all the events and snapshots of the user entity with a specific encryption key* for
that user. When the user requests to be forgotten, only the encryption key needs to be removed and then
the information in all places become unreadable. In addition to removing the encryption key, the events and snapshots can still be deleted if desired, but that is
not required for GDPR compliance.

Let's say that we have another persistent entity that represents a blog post that many different users may add
comments to. Each comment is associated with a user that may request to be forgotten, which would mean making that user's comments anonymous.

In this example, again, it would be difficult to find all comments by a given user, since the comments are added to
the blog post rather than being indexed by the user identifier. We would have to maintain an additional
cross-index or traverse all blog posts to be able to find and change all comments by that user.

Instead, we *encrypt the author part of the event with a specific encryption key for the user* that
wrote the comment. The blog post entity would know that if the author part of a comment can't be decrypted because the key has
been removed, the comment should no longer show the author.

@@@ note

Also see the more detailed discussion about @ref[data subject ids](./data-subject-id.md).

@@@

## In memory state

It's also important to note that persistent entities keep their state in memory and if the corresponding persistent entity did not remove the encryption key, it will still have information in memory for the data subject that has been shredded. Therefore, we recommended periodically passivating and recovering
such persistent entities. 

When recovered from persistent storage, an entity will retrieve the shredded snapshot and events. For GDPR compliance the information doesn't have to be forgotten immediately, so a periodic recycle
process is acceptable. That can also be performed by periodically deploying a new version or same version of the software
with a rolling upgrade or a system restart.

## Projections and consumers

When using the [Command Query Responsibility Segregation (CQRS) pattern](https://msdn.microsoft.com/en-us/library/jj591573.aspx),
for example with @java[@extref:[Lagom's Read-side](lagom:java/ReadSide.html)]
@scala[@extref:[Lagom's Read-side](lagom:scala/ReadSide.html)], we must also
think about how to remove users' information in denormalized projections and downstream consumers.

If the same encryption key was used for the read-side projections and the 
persistent entities, it might be enough to remove the encryption key. However, performing an ordinary delete
might be better and is sometimes also necessary. This is particularly true in the case of a natural language index. For
example, the blog post comments discussed above might be indexed by Elastic Search.

Therefore we recommend a two step process. First, emit an event that the data subject is to be deleted. Read-side
processors can act on this event and delete the data from their projections. Once you are confident that that event
has been propagated, then the events themselves (including the delete event) can be shredded by removing the
encryption key. The second step can be performed as a background job that is run periodically. For example, you might remove the
encryption key one week after the deletion event.

This two step process also solves other potential issues, such as:

* Other encryption keys for the same data subject might be used in the read-sides.
* Events are propagated asynchronously to the read-side; in flight events related to the data subject
  should be processed before the removal of the encryption key.
* The deletion event can be propagated to other services using a message broker.

## Message brokers

When publishing information to other services with a message broker, such as Kafka, we must also consider
that the data is typically also stored in the message broker and that information must also be removed or shredded
when a data subject is to be forgotten.

Two potential solutions to removing personal data from a message broker each have trade-offs:

- We can publish the encrypted events, which
would then be unreadable when the encryption key has been removed. A drawback with this alternative
is that the consumers of the events must also have access to the encryption keys, that is, they must be using the same
key management solution. That might result in a too tight coupling between the services. Another drawback
is that events related to the data subject might become unreadable before consumers have processed them.

- The other alternative is to configure the message broker with a retention period so that the messages
are automatically removed after a while. This has the drawback that it will take longer until the
information has been completely removed, but that is acceptable from a GDPR compliance point of view.
This second solution is probably simpler to use in most situations, especially if you want
to use a retention period for other reasons as well.

## Backups

Encrypted data in backups is not a problem since it is unreadable when the keys have been removed. There must be
a retention policy for backups containing unencrypted personal data so that old backups with information about
removed data subjects are ereased.

You will probably also have backups of the encryption keys and a retention policy must be used for those also.

## Logs

The advice for logs is to not log personal data. If there is a chance that there are any traces in the
logs that might be sensitive information you should make sure that old logs are removed periodically.


