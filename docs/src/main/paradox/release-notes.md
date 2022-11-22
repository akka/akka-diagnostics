# Release Notes

Deprecated page, release notes are now kept individually for @ref[Akka Persistence Enhancements](akka-persistence-enhancements-release-notes.md)
and @ref[Akka Resilience Enhancements](akka-resilience-enhancements-release-notes.md)

## Version 1.1.9

* @ref:[Kubernetes Lease](kubernetes-lease.md) now based on @extref:[Akka Coordination Lease](akka:coordination.html#lease)
  Compatible with Akka Version 2.5.22 and up.

## Version 1.1.8, March 13, 2019

* New @ref[SBR strategy based on distributed lease](split-brain-resolver.md#lease) (lock)
  implemented with Kubernetes API.
* Handle edge cases in @ref[Keep Majority](split-brain-resolver.md#keep-majority) and
  @ref[Keep Oldest](split-brain-resolver.md#keep-oldest) SBR strategies when there are membership
  changes at the same time as a network partition.
* @ref[Static Quorum](split-brain-resolver.md#static-quorum) SBR strategy will down all nodes if
  number of members exceeds the `quorum-size * 2 - 1` limit when a SBR decision is needed.
* Enable @ref[Down all when unstable](split-brain-resolver.md#down-all-when-unstable) by default.
* Fix issue in @ref[Config Checker](config-checker.md) when SBR dependency not on classpath.
* Better version reporting and remove some false warnings in @ref[Diagnostics Recorder](diagnostics-recorder.md).
* Fix issue in @ref[Starvation Detector](starvation-detector.md) when there are no threads to report.
* Updated dependencies to Akka 2.5.21 (improved downing).

## Version 1.1.7, January 4, 2019

* Updated dependencies to Akka 2.5.19 and akka-persistence-cassandra 0.92, which also
  updates the transitive dependency to Guava (older version has security vulnerability)

## Version 1.1.6, December 18, 2018

* New SBR `down-all` strategy. This strategy can be a safe alternative if the network environment is highly
  unstable with unreachability observations that can't be fully trusted, and including frequent occurrences
  of indirectly connected nodes. In such environments it can be better to shutdown all nodes and start up a
  new fresh cluster. See @ref[Down All](split-brain-resolver.md#down-all) documentation.
* New SBR configuration property `down-all-when-unstable` to down all nodes if failure detector observations
  continue to change for too long. @ref[Down all when unstable](split-brain-resolver.md#down-all-when-unstable)
  documentation.
* New SBR configuration property `keep-one-indirectly-connected` to allow downing of all indirectly connected
  nodes instead of keeping one, which can be a safer option in unstable environments. See
  @ref:[Indirectly connected nodes](split-brain-resolver.md#indirectly-connected-nodes) documentation.
* Highlight and clarify the scenarios that can lead to SBR decisions that result in forming separate clusters,
  i.e. the system will experience a split brain scenario.

## Version 1.1.5, November 23, 2018

* New SBR algorithm to handle scenarios where nodes are marked as unreachable via some network links but
  they are still indirectly connected via other nodes, i.e. it's not a clean network partition (or node crash).
  See @ref:[Indirectly connected nodes](split-brain-resolver.md#indirectly-connected-nodes) documentation.

## Version 1.1.4, November 8, 2018

* Fix issue that SBR might not be active on nodes with status `WeaklyUp`
* More SBR logging
* Clarify SBR `stable-after` and `down-removal-margin`
* Additional hints by Thread Starvation Detector what can be the cause of the problems
