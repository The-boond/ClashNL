package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = mutableListOf<() -> Unit>()

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH).parentFile?.mkdirs()
        Room
            .databaseBuilder(
                Application.application,
                ProfileDatabase::class.java,
                Path.PROFILES_DATABASE_PATH,
            )
            .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }

    suspend fun nextOrder(): Long = instance.profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = instance.profileDao().nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = instance.profileDao().get(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = instance.profileDao().insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        for (callback in callbacks.toList()) {
            callback()
        }
        return profile
    }

    suspend fun update(profile: Profile): Int {
        try {
            return instance.profileDao().update(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().update(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun updateTyped(profileId: Long, typed: TypedProfile): Int {
        try {
            return instance.profileDao().updateTyped(profileId, typed)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun updateEditable(profile: Profile): Int {
        try {
            return instance.profileDao().updateEditable(
                profileId = profile.id,
                name = profile.name,
                icon = profile.icon,
                typed = profile.typed,
            )
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            val deleted = instance.profileDao().delete(profile)
            if (deleted > 0) deleteProfileFiles(profile)
            return deleted
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            val deleted = instance.profileDao().delete(profiles)
            if (deleted > 0) profiles.forEach(::deleteProfileFiles)
            return deleted
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun list(): List<Profile> = instance.profileDao().list()

    private fun deleteProfileFiles(profile: Profile) {
        val configRoot = File(Application.application.filesDir, "configs").canonicalFile
        val configFile = runCatching { File(profile.typed.path).canonicalFile }.getOrNull() ?: return
        if (configFile.parentFile != configRoot) return
        listOf(
            configFile,
            File(configFile.path + ".bak"),
            File(configFile.path + ".mihomo-selections.json"),
            File(configFile.path + ".mihomo-selections.json.bak"),
        ).forEach { file -> runCatching { file.delete() } }
    }

    fun remoteServerDao(): RemoteServer.Dao = instance.remoteServerDao()
}
