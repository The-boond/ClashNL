package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.repository.ProfileRemoteRepository
import io.nekohasekai.sfa.runtime.ProfileRuntime
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val TAG = "UpdateProfileWork"

        suspend fun reconfigureUpdater() {
            runCatching {
                reconfigureUpdater0()
            }.onFailure {
                Log.e(TAG, "reconfigureUpdater", it)
            }
        }

        private suspend fun reconfigureUpdater0() {
            val remoteProfiles =
                ProfileManager.list()
                    .filter(ProfileUpdateSchedule::isEligible)
            if (remoteProfiles.isEmpty()) {
                WorkManager.getInstance(Application.application).cancelUniqueWork(WORK_NAME)
                return
            }

            val minDelay =
                remoteProfiles.minOf { ProfileUpdateSchedule.intervalMinutes(it.typed.autoUpdateInterval) }
            val nowMillis = System.currentTimeMillis()
            val minInitDelay =
                remoteProfiles.minOf {
                    ProfileUpdateSchedule.remainingDelaySeconds(
                        intervalMinutes = it.typed.autoUpdateInterval,
                        lastUpdatedMillis = it.typed.lastUpdated.time,
                        nowMillis = nowMillis,
                    )
                }
            WorkManager.getInstance(Application.application).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                        setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    }
                    .build(),
            )
        }
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            var selectedProfileUpdated = false
            val remoteProfiles =
                ProfileManager.list()
                    .filter(ProfileUpdateSchedule::isEligible)
            if (remoteProfiles.isEmpty()) return Result.success()
            var success = true
            val selectedProfile = Settings.selectedProfile
            for (profile in remoteProfiles) {
                if (
                    !ProfileUpdateSchedule.isDue(
                        intervalMinutes = profile.typed.autoUpdateInterval,
                        lastUpdatedMillis = profile.typed.lastUpdated.time,
                        nowMillis = System.currentTimeMillis(),
                    )
                ) {
                    continue
                }
                try {
                    val result = ProfileRemoteRepository.update(profile)
                    if (result.contentChanged && profile.id == selectedProfile) {
                        selectedProfileUpdated = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "update profile ${profile.name}", e)
                    success = false
                }
            }
            if (selectedProfileUpdated) {
                runCatching {
                    ProfileRuntime.reloadSelectedIfRunning(applicationContext)
                }
            }
            return if (success) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}

internal object ProfileUpdateSchedule {
    private const val MIN_INTERVAL_MINUTES = 15L

    fun isEligible(profile: Profile): Boolean = profile.typed.type == TypedProfile.Type.Remote &&
        profile.typed.core == ProfileCore.Mihomo &&
        profile.typed.autoUpdate

    fun intervalMinutes(value: Int): Long = value.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES)

    fun remainingDelaySeconds(
        intervalMinutes: Int,
        lastUpdatedMillis: Long,
        nowMillis: Long,
    ): Long {
        val intervalSeconds = intervalMinutes(intervalMinutes) * 60L
        val nowSeconds = nowMillis / 1_000L
        val lastUpdatedSeconds = lastUpdatedMillis / 1_000L
        val elapsedSeconds =
            if (nowSeconds > lastUpdatedSeconds) {
                nowSeconds - lastUpdatedSeconds
            } else {
                0L
            }
        return (intervalSeconds - elapsedSeconds).coerceAtLeast(0L)
    }

    fun isDue(
        intervalMinutes: Int,
        lastUpdatedMillis: Long,
        nowMillis: Long,
    ): Boolean = remainingDelaySeconds(intervalMinutes, lastUpdatedMillis, nowMillis) == 0L
}
