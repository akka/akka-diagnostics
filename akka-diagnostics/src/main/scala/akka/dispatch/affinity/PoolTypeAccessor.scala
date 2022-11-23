package akka

import akka.dispatch.affinity.AffinityPool

object PoolTypeAccessor {
  // shameful trixery to access to the Akka private type AffinityPool
  type AffinityPoolAccessor = AffinityPool
}
