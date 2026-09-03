package io.nekohasekai.sfa.mihomo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class MihomoSelectionChange(
    val revision: Long,
    val profileId: Long,
    val group: String,
    val proxy: String,
)

/** Process-wide signal emitted only after Mihomo accepts a live proxy selection. */
object MihomoSelectionRevision {
    private val sequence = AtomicLong()
    private val _changes = MutableStateFlow<MihomoSelectionChange?>(null)
    val changes = _changes.asStateFlow()

    val currentRevision: Long
        get() = _changes.value?.revision ?: 0L

    fun publish(profileId: Long, group: String, proxy: String) {
        _changes.value =
            MihomoSelectionChange(
                revision = sequence.incrementAndGet(),
                profileId = profileId,
                group = group,
                proxy = proxy,
            )
    }
}
