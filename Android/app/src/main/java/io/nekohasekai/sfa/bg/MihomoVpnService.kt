package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoStartRequest
import io.nekohasekai.sfa.mihomo.MihomoTunCallback
import io.nekohasekai.sfa.mihomo.MihomoTunDevice
import io.nekohasekai.sfa.runtime.ProfileRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Android-only owner for a Mihomo TUN. It owns the ParcelFileDescriptor and
 * exposes Android callbacks to the controller, never to Compose callers.
 *
 * It is registered alongside the legacy VPN service. [ProfileRuntime] selects
 * this service only for profiles explicitly marked as [ProfileCore.Mihomo].
 */
class MihomoVpnService :
    VpnService(),
    MihomoTunCallback {
    companion object {
        private const val TAG = "MihomoVpnService"
        private const val ACTION_START = "io.nekohasekai.sfa.mihomo.START"
        private const val ACTION_STOP = "io.nekohasekai.sfa.mihomo.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "mihomo_vpn"
        private const val NOTIFICATION_ID = 10_321

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MihomoVpnService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, MihomoVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val controller by lazy { MihomoRuntimeRepository.controller(this) }
    private var tun: ParcelFileDescriptor? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateUnderlyingNetwork(network)

        override fun onLost(network: Network) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerUnderlyingNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                lifecycleMutex.withLock { stopCoreAndTun() }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (status.value != Status.Stopped) return START_NOT_STICKY
        status.value = Status.Starting
        showForegroundNotification()
        scope.launch {
            lifecycleMutex.withLock {
                runCatching { startSelectedProfile() }
                    .onSuccess {
                        Settings.startedByUser = true
                        status.postValue(Status.Started)
                    }
                    .onFailure {
                        Log.e(TAG, "Unable to start Mihomo VPN", it)
                        stopCoreAndTun()
                        Settings.startedByUser = false
                        status.postValue(Status.Stopped)
                        stopSelf(startId)
                    }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        runBlocking { lifecycleMutex.withLock { stopCoreAndTun() } }
        status.value = Status.Stopped
        stopSelf()
    }

    override fun onDestroy() {
        runBlocking { lifecycleMutex.withLock { stopCoreAndTun() } }
        Settings.startedByUser = false
        ProfileRuntime.markStopped(ProfileCore.Mihomo)
        status.value = Status.Stopped
        binder.close()
        unregisterNetworkCallback()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == Action.SERVICE) binder else super.onBind(intent)

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun querySocketUid(protocol: Int, source: String, target: String): Int = -1

    private suspend fun startSelectedProfile() {
        check(prepare(this) == null) { "android: missing VPN permission" }
        val profileId = Settings.selectedProfile
        check(profileId >= 0) { "No profile is selected" }
        val profile = ProfileManager.get(profileId) ?: error("Selected profile no longer exists")
        check(profile.typed.core == ProfileCore.Mihomo) {
            "Selected profile is not a Mihomo profile"
        }
        val content = File(profile.typed.path).readText()
        val device = establishTun()
        try {
            controller.start(
                MihomoStartRequest(
                    config = MihomoConfig(content),
                    tun = MihomoTunDevice(
                        fileDescriptor = device.fd,
                        stack = "system",
                        gateway = "172.19.0.1/30,fdfe:dcba:9876::1/126",
                        dns = "0.0.0.0,::",
                        callback = this,
                    ),
                ),
            )
            tun = device
        } catch (exception: Exception) {
            device.close()
            throw exception
        }
    }

    private fun establishTun(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(9_000)
            .addAddress("172.19.0.1", 30)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("172.19.0.2")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        if (Settings.allowBypass) builder.allowBypass()
        applyPerAppRules(builder)
        return builder.establish() ?: error("android: VPN was not prepared or was revoked")
    }

    private fun applyPerAppRules(builder: Builder) {
        if (!Settings.perAppProxyEnabled) return
        val packages = Settings.getEffectivePerAppProxyList()
        for (packageName in packages) {
            try {
                when (Settings.getEffectivePerAppProxyMode()) {
                    Settings.PER_APP_PROXY_INCLUDE -> builder.addAllowedApplication(packageName)
                    Settings.PER_APP_PROXY_EXCLUDE -> builder.addDisallowedApplication(packageName)
                }
            } catch (_: NameNotFoundException) {
                Log.w(TAG, "Configured app is not installed: $packageName")
            }
        }
    }

    private suspend fun stopCoreAndTun() {
        runCatching { controller.stop() }
        tun?.close()
        tun = null
    }

    private fun registerUnderlyingNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager().registerNetworkCallback(request, networkCallback)
    }

    private fun unregisterNetworkCallback() {
        runCatching { connectivityManager().unregisterNetworkCallback(networkCallback) }
    }

    private fun updateUnderlyingNetwork(network: Network) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            setUnderlyingNetworks(arrayOf(network))
        }
    }

    private fun showForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clashnl_menu)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Mihomo VPN is running")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun connectivityManager(): ConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}
