package io.nekohasekai.sfa.latency

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.UnderlyingNetworkSnapshot
import io.nekohasekai.sfa.bg.UnderlyingNetworkTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class NetworkIdentity(
    val key: String,
    val transport: String,
)

object NetworkIdentityProvider {
    private val registrations = ConcurrentHashMap<Any, Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _changes = MutableStateFlow(NetworkIdentity("unknown", "unknown"))
    val changes = _changes.asStateFlow()
    private var collectionJob: Job? = null

    fun current(): NetworkIdentity = UnderlyingNetworkTracker.current()?.toIdentity() ?: UNKNOWN

    @Synchronized
    fun start(owner: Any) {
        if (registrations.putIfAbsent(owner, Unit) != null) return
        UnderlyingNetworkTracker.start(Application.application)
        if (collectionJob == null) {
            collectionJob = scope.launch {
                UnderlyingNetworkTracker.snapshots.collect(::publish)
            }
        }
        publish(UnderlyingNetworkTracker.current())
    }

    @Synchronized
    fun stop(owner: Any) {
        if (registrations.remove(owner) == null || registrations.isNotEmpty()) return
        collectionJob?.cancel()
        collectionJob = null
    }

    private fun publish(snapshot: UnderlyingNetworkSnapshot?) {
        val identity = snapshot?.toIdentity() ?: UNKNOWN
        if (_changes.value != identity) _changes.value = identity
    }

    private fun UnderlyingNetworkSnapshot.toIdentity(): NetworkIdentity = NetworkIdentity("${transport.key}:$networkHandle", transport.key)

    private val UNKNOWN = NetworkIdentity("unknown", "unknown")
}
