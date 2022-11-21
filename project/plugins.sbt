addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.2")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.3")


addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.32")
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.4.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-publish-rsync" % "0.1")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.1.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")

// for testing in k8s
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.17")
// https://github.com/sbt/sbt-native-packager/issues/1202