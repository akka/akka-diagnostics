package akka.dispatch.affinity

object PoolTypeAccessor {
  // shameful trixery to access to the Akka private type AffinityPool
  type AffinityPoolAccessor = AffinityPool
}
