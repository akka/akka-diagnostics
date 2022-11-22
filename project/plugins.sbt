addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.7.0")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.3")

addSbtPlugin("com.lightbend.paradox" % "sbt-paradox-dependencies" % "0.2.2")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.45")
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.4.1")
addSbtPlugin("com.lightbend.sbt" % "sbt-publish-rsync" % "0.2")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.1.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.1")
// for testing in k8s
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.17") // TODO review it is necessary
// https://github.com/sbt/sbt-native-packager/issues/1202
addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.7.0")
// for releasing
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
