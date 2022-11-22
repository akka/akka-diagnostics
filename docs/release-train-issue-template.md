# Release Train Issue Template for Akka Diagnostics 

(Liberally copied and adopted from Scala itself https://github.com/scala/scala-dev/blob/b11cd2e4a4431de7867db6b39362bea8fa6650e7/notes/releases/template.md)

For every release, make a copy of this file named after the release, and expand the variables.
Ideally replacing variables could become a script you can run on your local machine.

Variables to be expanded in this template:
- $AKKA_ENHANCEMENTS_VERSION$=???

### before the release

- [ ] Create a new milestone for the [next version](https://github.com/lightbend/akka-commercial-addons/milestones)
- [ ] Check [closed issues without a milestone](https://github.com/lightbend/akka-commercial-addons/issues?utf8=%E2%9C%93&q=is%3Aissue%20is%3Aclosed%20no%3Amilestone) and either assign them the 'upcoming' release milestone or `invalid/not release-bound`
- [ ] Make sure all important / big PRs have been merged by now
- [ ] Update docs/src/main/paradox/akka-persistence-enhancements-release-notes.md and docs/src/main/paradox/akka-resilience-enhancements-release-notes.md

### Preparing release notes in the documentation / announcement

- [ ] Prepare & PR release notes
- [ ] Move all [unclosed issues](https://github.com/lightbend/akka-commercial-addons/issues?q=is%3Aopen+is%3Aissue+milestone%3A$AKKA_ENHANCEMENTS_VERSION$) for this milestone to the next milestone

### Cutting the release

- [ ] Be sure to have a clean working dir on `master`.
- [ ] Be sure to use JDK8
- [ ] `git tag v$AKKA_DIAGNOSTICS_VERSION$`
- [ ] `sbt clean whitesourceUpdate`
- [ ] `sbt -Dakka.build.scalaVersion=2.11.12 publish`
- [ ] `sbt -Dakka.build.scalaVersion=2.12.15 publish`
- [ ] `sbt -Dakka.build.scalaVersion=2.13.8 publish`
- [ ] `sbt -Dakka.genjavadoc.enabled=true ++2.12.15 docs/publishRsync`
- [ ] `git push origin v$AKKA_DIAGNOSTICS_VERSION$`

### Check availability

- [ ] Check [API](https://doc.akka.io/api/akka-enhancements/$AKKA_DIAGNOSTICS_VERSION$/) documentation
- [ ] Check [reference](https://doc.akka.io/docs/akka-enhancements/$AKKA_DIAGNOSTICS_VERSION$/) documentation
- [ ] Check the release on [Bintray](https://bintray.com/lightbend/commercial-releases)

### Retarget documentation links
  - [ ] Log into `gustav.akka.io` as `akkarepo` 
    - [ ] update the `current` links on `repo.akka.io` to point to the latest version (**replace the minor appropriately for minor releases**)
         ```
         ./update-akka-enhancements.sh $AKKA_DIAGNOSTICS_VERSION$
         ```
    - [ ] check changes and commit the new version to the local git repository
         ```
         cd ~/www
         git add docs/akka-enhancements/ api/akka-enhancements/ japi/akka-enhancements/
         git commit -m "Akka Enhancements $AKKA_DIAGNOSTICS_VERSION$"
         ```

### Announcements

- [ ] Announce internally

### Afterwards

- [ ] Update version for [Library build dependencies](https://developer.lightbend.com/docs/lightbend-platform/introduction/getting-help/build-dependencies.html) in [private project](https://github.com/lightbend/lightbend-platform-docs/blob/master/docs/modules/getting-help/pages/build-dependencies.adoc)
- [ ] Close the [$AKKA_DIAGNOSTICS_VERSION$ milestone](https://github.com/lightbend/akka-enhancements/milestones?direction=asc&sort=due_date)
- Close this issue
