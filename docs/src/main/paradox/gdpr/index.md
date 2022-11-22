# GDPR for Akka Persistence

@@@ index

* [Subject identifiers](data-subject-id.md)
* [Shred or delete?](shredding.md)
* [General steps](using.md)
* [Encrypting data](encryption.md)
* [Migrating existing data](migration.md)
* [Lagom specifics](lagom.md)
* [Other data storage](other-storage.md)


@@@

The General Data Protection Regulation (GDPR) took effect in May of 2018. It requires organizations that collect personal data to inform their users about the *purpose* of collection and to obtain *permission* to store that information. Compliance requires communication with end-users as well as changes to persistence patterns. Existing systems that persist data must now allow personal data to be deleted at a user's request, and new systems must be built with that capability.

In this document, we describe GDPR for Akka Persistence modules and recommend techniques that will help you build or modify a system that allows for safe deletion of personal data. However, keep in mind that data deletion alone does not satisfy GDPR regulations and no toolkit or framework will make you automatically compliant. The explanation of permissions to users and the potential reasons for collecting data must also align with GDPR rules.

We recommend reading the official [gdpr-info.eu](https://gdpr-info.eu) document even if you are focusing on internal system capabilities. Chapter 3, [Rights of the data subject](https://gdpr-info.eu/chapter-3/), describes the right of users to inquire about the information stored about them, and ensures the "Right to be Forgotten" (Art 17).

@@@ warning

This module is currently marked as @extref:[May Change](akka:common/may-change.html) in the sense that the API might be changed based on feedback from initial usage. However, the module is ready for use in production and future changes will not break the serialization format of
messages or of stored data.

@@@

@@include[proprietary.md](../includes/proprietary.md)

## Introduction

With respect to persistence, systems complying with GDPR must have the capability: 1) to identify all personal data belonging to a particular user, and 2) to delete or to "forget" that data. Data shredding is a technique that "forgets" information instead of deleting or modifying it. Shredding can be accomplished by encrypting data with a key for a given person and deleting the key when that data should be forgotten.

Event Sourcing systems store the entire event sequence that results in the total state of entities.
Many events containing personal information lead up to the current state, rather than just a single record. In addition, the same information might be stored in other locations, such as denormalized projections, snapshots and backups. This makes it more difficult to modify or delete all state, therefore we offer tools based on shredding.
GDPR for Akka Persistence modules make it easier to implement this functionality when using Akka or Lagom event sourcing. However, you can also use the encrypted key with non-event sourced data, such as CRUD with a relational database.

GDPR for Akka Persistence modules include APIs to help you achieve the following:

- Identify data associated with a particular subject (person). One of the most important concepts in GDPR is the "data subject id".
In @ref:[Subject identifiers](./data-subject-id.md) we offer some advice
on selecting the right way to represent such identifiers.

- Achieve the "right to forget". Data shredding can be used to forget information in events. This involves encrypting events with a key for a given data subject id and deleting the key when that data subject is to be forgotten. The section @ref:[Shred or delete?](./shredding.md) goes into further detail about shredding and @ref[Using akka-gdpr](./using.md) explains how the `akka-gdpr` module works and general steps for using it.

- Retrieve events related to a particular subject (person). You can use an `eventsByTag` query to retrieve all events
tagged with a given data subject id.

- Add an encrypted ID to existing data. With existing systems that do not currently comply with the new GDPR requirements, you can use `akka-persistence-update` to transform events in-place in a one time migration and to delete or update snapshots that may contain personal information. These techniques are described in the @ref:[ Migrating existing data](./migration.md) section.

GDPR for Akka Persistence modules work with the following:

- @extref:[Akka Persistent Actor](akka:persistence.html)
- @java[[Lagom PersistentEntity](https://www.lagomframework.com/documentation/current/java/PersistentEntity.html)]@scala[[Lagom PersistentEntity](https://www.lagomframework.com/documentation/current/scala/PersistentEntity.html)]
- @ref[Multi-DC ReplicatedEntity](../persistence-dc/index.md)

You can also use the encrypted ID functionality with other types of persistent storage, like relational databases.
