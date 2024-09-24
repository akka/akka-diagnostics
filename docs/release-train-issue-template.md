Release Akka Diagnostics $VERSION$

<!--
# Release Train Issue Template for Akka Diagnostics 

(Liberally copied and adopted from Scala itself https://github.com/scala/scala-dev/blob/b11cd2e4a4431de7867db6b39362bea8fa6650e7/notes/releases/template.md)

For every release, use the `scripts/create-release-issue.sh` to make a copy of this file named after the release, and expand the variables.

Variables to be expanded in this template:
- $VERSION$=???

Key links:
  - akka/akka-diagnostics milestone: https://github.com/akka/akka-diagnostics/milestone/?
-->

### Cutting the release

- [ ] Check that open PRs and issues assigned to the milestone are reasonable
- [ ] Update the Change date and version in the LICENSE.
- [ ] Create a new milestone for the [next version](https://github.com/akka/akka-diagnostics/milestones)
- [ ] Close the [$VERSION$ milestone](https://github.com/akka/akka-diagnostics/milestones?direction=asc&sort=due_date)
- [ ] Make sure all important PRs have been merged
- [ ] Update the revision in Fossa in the Akka Group for the Akka umbrella version, e.g. `22.10`. Note that the revisions for the release is udpated by Akka Group > Projects > Edit. For recent dependency updates the Fossa validation can be triggered from the GitHub actions "Dependency License Scanning".
- [ ] Wait until [main build finished](https://github.com/akka/akka-diagnostics/actions) after merging the latest PR
- [ ] Update the [draft release](https://github.com/akka/akka-diagnostics/releases) with the next tag version `v$VERSION$`, title and release description. Use the `Publish release` button, which will create the tag.
- [ ] Check that GitHub Actions release build has executed successfully (GitHub Actions will start a [CI build](https://github.com/akka/akka-diagnostics/actions) for the new tag and publish artifacts to https://repo.akka.io/maven)

### Check availability

- [ ] Check [API](https://doc.akka.io/api/akka-diagnostics/$VERSION$/) documentation
- [ ] Check [reference](https://doc.akka.io/libraries/akka-diagnostics/$VERSION$/) documentation. Check that the reference docs were deployed and show a version warning (see section below on how to fix the version warning).
- [ ] Check the release on https://repo.akka.io/maven/com/lightbend/akka/akka-diagnostics_2.13/$VERSION$/akka-diagnostics_2.13-$VERSION$.pom

### When everything is on https://repo.akka.io/maven
- [ ] Log into `gustav.akka.io` as `akkarepo`
    - [ ] If this updates the `current` version, run `./update-akka-diagnostics-current-version.sh $VERSION$`
    - [ ] otherwise check changes and commit the new version to the local git repository
         ```
         cd ~/www
         git status
         git add libraries/akka-diagnostics/current libraries/akka-diagnostics/$VERSION$
         git add api/akka-diagnostics/current api/akka-diagnostics/$VERSION$
         git commit -m "Akka Diagnostics $VERSION$"
         ```

### Announcements

For important patch releases, and only if critical issues have been fixed:

- [ ] Send a release notification to [Lightbend discuss](https://discuss.akka.io)
- [ ] Tweet using the [@akkateam](https://twitter.com/akkateam/) account (or ask someone to) about the new release
- [ ] Announce internally (with links to Tweet, discuss)

For minor or major releases:

- [ ] Include noteworthy features and improvements in Akka umbrella release announcement at akka.io. Coordinate with PM and marketing.

### Afterwards

- [ ] Update the akka-dependencies bom and version for [Akka module versions](https://doc.akka.io/libraries/akka-dependencies/current/) in [akka-dependencies repo](https://github.com/akka/akka-dependencies)
- Close this issue
