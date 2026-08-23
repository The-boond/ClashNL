package io.nekohasekai.sfa.mihomo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MihomoSelectionLedgerTest {
    @Test
    fun staleApplyReplaysLatestSelectionAfterConcurrentWrite() = runBlocking {
        val profileKey = "profile"
        var sidecar: Map<String, String> = mapOf("Proxy" to "stale")
        val ledger = MihomoSelectionLedger(
            read = { sidecar },
            write = { _, selections -> sidecar = selections.toMap() },
        )
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val completedRuntimeSelections = mutableListOf<String>()
        var liveRuntimeSelection = ""
        val applySelection: suspend (String, String) -> Unit = { _, proxy ->
            if (proxy == "stale") {
                staleStarted.complete(Unit)
                releaseStale.await()
            }
            liveRuntimeSelection = proxy
            completedRuntimeSelections += proxy
        }

        withTimeout(2_000L) {
            val pendingApply = launch {
                ledger.applyPending(profileKey, applySelection)
            }
            staleStarted.await()

            // This models the UI persisting and applying a newer selection while
            // service startup still has an older sidecar snapshot in flight.
            ledger.select(profileKey, "Proxy", "latest")
            applySelection("Proxy", "latest")
            assertEquals("latest", liveRuntimeSelection)

            // The stale request completes last, briefly reverting the runtime.
            // applyPending must notice the revision and replay "latest" again.
            releaseStale.complete(Unit)
            pendingApply.join()
        }

        assertEquals("latest", sidecar["Proxy"])
        assertEquals("latest", liveRuntimeSelection)
        assertEquals(listOf("latest", "stale", "latest"), completedRuntimeSelections)
    }
}
