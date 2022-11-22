# Subject identifiers

@@@ note

The following is not legal advice, but simply general suggestions and ideas. The GDPR field is still in flux, and only with time will accepted patterns and common use cases emerge. 

@@@ 

As introduced in the @ref[overview](index.md), dealing with data subject identifiers is an important part of a GDPR strategy. There are multiple ways to implement identifiers correctly,  depending on your application:

- If your application already has some `userId` you may want to use this identifier for the data subject 
id. However, you should take care that such id does not *itself* carry personal information. For example, if the id includes the "nickname" of an user, you should *not* use it as a data subject id: the ID itself can be seen as personal data. If you would like to use user ids as your data subject ids you may want to consider using a SHA-1 of the user id and some additional seed, as illustrated in the example below.

- A good alternative is to generate UUIDs for data subjects. You can do so using the `java.util.UUID` class and obtain its `String` representation to be used in the `WithDataSubjectId` wrapper provided by `akka-gdpr`, as described in @ref[]. 

Also, check whether "a user" has more than one data subject id. For example, different systems may have assigned the same user different ids. When a request to remove data for a 
given user is issued to your application, it may need to deal with all of the user's data subject ids. 

Another point of discussion is whether metadata associated with a particular data subject id should be removed or not. Even when using data shredding, it is possible that information about when events were stored linked together with correlated data could be used to deduce some information about "that specific" data subject. At this point no rulings have established how far one should go with regards to sanitizing such metadata.

## Example

The following example illustrates using SHA-1 to encrypt a user id:
 
Scala
:   @@snip [shaid]($root$/../akka-persistence-update-cassandra/src/test/scala/examples/gdpr/GdprMigrationExamples.scala) { #sha-id }

Java
:   @@snip [shaid]($root$/../akka-persistence-update-cassandra/src/test/java/examples/gdpr/GdprMigration.java) { #sha-id }

