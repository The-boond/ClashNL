package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.runtime.ProfileRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!Settings.startedByUser) return@launch
                CrashReportManager.refresh()
                if (CrashReportManager.unreadCount.value > 0) {
                    Settings.startedByUser = false
                    return@launch
                }
                ProfileRuntime.startSelected(context.applicationContext)
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to restore Mihomo VPN", exception)
                Settings.startedByUser = false
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
