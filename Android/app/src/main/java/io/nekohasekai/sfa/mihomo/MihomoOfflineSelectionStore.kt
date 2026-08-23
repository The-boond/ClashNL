package io.nekohasekai.sfa.mihomo

import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.utils.ProfileConfigStore
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

/**
 * Persists Mihomo choices per app profile. Keeping this app-owned state avoids
 * leaking selections between subscriptions that reuse common group names.
 */
object MihomoOfflineSelectionStore {
    private val ledger = MihomoSelectionLedger(::read, ::write)

    fun selections(profile: Profile): Map<String, String> = ledger.selections(profileKey(profile))

    fun select(profile: Profile, group: String, proxy: String) = ledger.select(profileKey(profile), group, proxy)

    suspend fun applyPending(profile: Profile, controller: MihomoController) {
        ledger.applyPending(profileKey(profile)) { group, proxy ->
            try {
                controller.selectProxy(group, proxy)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // A refreshed subscription may have removed a saved group or node.
                // Keep the remaining independent selections usable.
            }
        }
    }

    private fun read(profileKey: String): Map<String, String> = runCatching {
        val file = File(profileKey)
        if (!file.isFile) return@runCatching emptyMap()
        val objectValue = JSONObject(ProfileConfigStore.read(file))
        objectValue.keys().asSequence().associateWith { key -> objectValue.getString(key) }
    }.getOrDefault(emptyMap())

    private fun write(profileKey: String, selections: Map<String, String>) {
        val content = JSONObject(selections).toString()
        ProfileConfigStore.writeIfChanged(File(profileKey), content)
    }

    private fun profileKey(profile: Profile): String = runCatching {
        selectionFile(profile).canonicalPath
    }.getOrElse {
        selectionFile(profile).absolutePath
    }

    private fun selectionFile(profile: Profile): File = File(profile.typed.path + ".mihomo-selections.json")
}

/**
 * Serializes sidecar updates and gives each profile a monotonic revision. An
 * apply that raced with a newer write must replay the newest complete snapshot
 * before returning, so a slow stale controller request cannot win last.
 */
internal class MihomoSelectionLedger(
    private val read: (String) -> Map<String, String>,
    private val write: (String, Map<String, String>) -> Unit,
) {
    private data class Snapshot(
        val revision: Long,
        val selections: Map<String, String>,
    )

    private val lock = Any()
    private val revisions = mutableMapOf<String, Long>()

    fun selections(profileKey: String): Map<String, String> = synchronized(lock) {
        read(profileKey).toMap()
    }

    fun select(profileKey: String, group: String, proxy: String) = synchronized(lock) {
        val updated = read(profileKey).toMutableMap().apply { put(group, proxy) }
        write(profileKey, updated)
        revisions[profileKey] = revision(profileKey) + 1L
    }

    suspend fun applyPending(profileKey: String, applySelection: suspend (String, String) -> Unit) {
        while (true) {
            val snapshot = synchronized(lock) {
                Snapshot(
                    revision = revision(profileKey),
                    selections = read(profileKey).toMap(),
                )
            }
            snapshot.selections.forEach { (group, proxy) -> applySelection(group, proxy) }
            if (synchronized(lock) { revision(profileKey) == snapshot.revision }) return
        }
    }

    private fun revision(profileKey: String): Long = revisions[profileKey] ?: 0L
}
