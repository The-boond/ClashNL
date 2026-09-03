package io.nekohasekai.sfa

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.getSystemService
import io.nekohasekai.sfa.bg.AppChangeReceiver
import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.UnderlyingNetworkTracker
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.compose.theme.AppThemeMode
import io.nekohasekai.sfa.database.LegacyProfileMigration
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.HookModuleUpdateNotifier
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.utils.PrivilegeSettingsClient
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.security.KeyStore
import io.nekohasekai.sfa.Application as BoxApplication

class Application : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        val legacyManagedProfileId = removeLegacyAccountCredentials()
        AppCompatDelegate.setDefaultNightMode(
            AppThemeMode.fromValue(Settings.appThemeMode).nightMode,
        )
        Settings.dynamicNotification = false
        Settings.updateSource = "github"
        // Remote control is backed by the retired libbox runtime. Never let a
        // persisted remote session load it into Mihomo's process on startup.
        Settings.activeRemoteServerId = 0L
        AppLifecycleObserver.register(this)
        UnderlyingNetworkTracker.start(this)

        HookStatusClient.register(this)
        PrivilegeSettingsClient.register(this)

        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null)
        val tempDir = cacheDir
        tempDir.mkdirs()
        if (workingDir != null) {
            workingDir.mkdirs()
            CrashReportManager.install(workingDir, baseDir)
            OOMReportManager.install(workingDir)
        }

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            cleanUpLegacyProfiles(legacyManagedProfileId)
            UpdateProfileWork.reconfigureUpdater()
            HookModuleUpdateNotifier.sync(this@Application)
        }

        if (Vendor.isPerAppProxyAvailable()) {
            registerReceiver(
                AppChangeReceiver(),
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                },
            )
        }
    }

    /** Permanently discard credentials left by versions that included account login. */
    private fun removeLegacyAccountCredentials(): Long {
        val preferences = getSharedPreferences("clashnl_account_session", Context.MODE_PRIVATE)
        val managedProfileId = when (val value = preferences.all["managed_profile_id"]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: -1L
            else -> -1L
        }
        if (!preferences.edit().clear().commit()) {
            Log.w("Application", "Unable to clear legacy account preferences")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { deleteSharedPreferences("clashnl_account_session") }
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias("clashnl_account_session_rsa_v1")) {
                    deleteEntry("clashnl_account_session_rsa_v1")
                }
            }
        }.onFailure {
            Log.w("Application", "Unable to remove legacy account key", it)
        }
        return managedProfileId
    }

    private suspend fun cleanUpLegacyProfiles(managedProfileId: Long) {
        if (managedProfileId >= 0L) {
            runCatching {
                ProfileManager.get(managedProfileId)?.let { ProfileManager.delete(it) }
            }.onFailure {
                Log.e("Application", "Unable to remove legacy account-managed profile", it)
            }
        }

        val profiles =
            runCatching { ProfileManager.list() }.getOrElse {
                Log.e("Application", "Unable to inspect legacy profiles", it)
                return
            }
        val plan = LegacyProfileMigration.plan(profiles, Settings.selectedProfile)
        val profilesToUpdate =
            profiles.filter { profile -> profile.id in plan.disableAutoUpdateProfileIds }
                .onEach { profile -> profile.typed.autoUpdate = false }
        if (profilesToUpdate.isNotEmpty()) {
            runCatching { ProfileManager.update(profilesToUpdate) }.onFailure {
                Log.e("Application", "Unable to disable legacy profile auto-update", it)
            }
        }
        if (plan.replaceSelection) {
            Settings.selectedProfile = plan.replacementProfileId
            Settings.startedByUser = false
            Settings.activeProfileCore = ""
        }
    }

    /** Legacy diagnostics settings no longer configure a sing-box runtime. */
    fun reloadSetupOptions() = Unit

    companion object {
        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
