package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoNetworkMode
import io.nekohasekai.sfa.mihomo.MihomoOfflineSelectionStore
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoRuntimeState
import io.nekohasekai.sfa.mihomo.MihomoStartRequest
import io.nekohasekai.sfa.mihomo.MihomoTunCallback
import io.nekohasekai.sfa.mihomo.MihomoTunDevice
import io.nekohasekai.sfa.runtime.ProfileRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Android-only owner for a Mihomo TUN. It owns the ParcelFileDescriptor and
 * exposes Android callbacks to the controller, never to Compose callers.
 *
 * This is the app's only VPN runtime. [ProfileRuntime] accepts only profiles
 * explicitly marked as [ProfileCore.Mihomo].
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
        private val SUPPORTED_CLASH_MODES = setOf("rule", "global", "direct")

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MihomoVpnService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MihomoVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val controller by lazy { MihomoRuntimeRepository.controller(this) }
    private val commands = Channel<ServiceCommand>(Channel.UNLIMITED)
    private val startGeneration = AtomicLong()

    @Volatile
    private var runtimeStatus = Status.Stopped

    @Volatile
    private var activeRun: ActiveRun? = null
    private var tun: ParcelFileDescriptor? = null

    private data class ActiveRun(val generation: Long, val startId: Int)

    private sealed interface ServiceCommand {
        data class Start(val startId: Int, val generation: Long) : ServiceCommand

        data class Stop(
            val startId: Int?,
            val failedGeneration: Long? = null,
        ) : ServiceCommand
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (command in commands) {
                lifecycleMutex.withLock {
                    when (command) {
                        is ServiceCommand.Start -> handleStart(command)
                        is ServiceCommand.Stop -> {
                            if (command.failedGeneration == null || command.failedGeneration == activeRun?.generation) {
                                finishStop(command.startId)
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            controller.runtimeState.collect { state ->
                if (state == MihomoRuntimeState.Failed && runtimeStatus == Status.Started) {
                    val failedRun = activeRun
                    if (failedRun != null && controller.runtimeState.value == MihomoRuntimeState.Failed && runtimeStatus == Status.Started) {
                        commands.trySend(
                            ServiceCommand.Stop(
                                startId = failedRun.startId,
                                failedGeneration = failedRun.generation,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START.takeIf { Settings.startedByUser }
        when (action) {
            ACTION_START -> {
                // startForegroundService() requires this service to enter the foreground promptly.
                showForegroundNotification()
                commands.trySend(ServiceCommand.Start(startId, startGeneration.incrementAndGet()))
                return START_STICKY
            }

            ACTION_STOP -> {
                // The service may currently exist only because the Activity is bound to it.
                // Delivering an explicit command is therefore required; stopService() alone
                // would not run the core/TUN cleanup path.
                showForegroundNotification()
                commands.trySend(ServiceCommand.Stop(startId))
            }

            else -> {
                // A null restart intent with no persisted start request must still
                // pass through the serialized cleanup path in case Android killed
                // the process between updating state and closing the native core.
                showForegroundNotification()
                commands.trySend(ServiceCommand.Stop(startId))
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        commands.trySend(ServiceCommand.Stop(activeRun?.startId))
    }

    override fun onDestroy() {
        // Do not drain buffered START commands while Android is already
        // destroying this service instance.
        commands.cancel()
        scope.cancel()
        updateStatus(Status.Stopping)
        // A stopped controller can legitimately be leased by the Activity for
        // headless latency testing. This service does not own that core session,
        // so destruction must not wait for it or tear it down.
        if (controller.runtimeState.value != MihomoRuntimeState.Stopped || tun != null) {
            runBlocking { lifecycleMutex.withLock { stopCoreAndTun() } }
        }
        activeRun = null
        Settings.startedByUser = false
        ProfileRuntime.markStopped(ProfileCore.Mihomo)
        updateStatus(Status.Stopped)
        binder.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun handleStart(command: ServiceCommand.Start) {
        if (runtimeStatus == Status.Started && controller.runtimeState.value == MihomoRuntimeState.Running) {
            // A repeated START keeps the current core but advances the generation,
            // so an older queued failure cannot tear down this newer request.
            activeRun = ActiveRun(command.generation, command.startId)
            return
        }
        if (runtimeStatus != Status.Stopped || controller.runtimeState.value != MihomoRuntimeState.Stopped) {
            updateStatus(Status.Stopping)
            stopCoreAndTun()
            ProfileRuntime.markStopped(ProfileCore.Mihomo)
        }
        showForegroundNotification()
        updateStatus(Status.Starting)
        activeRun = ActiveRun(command.generation, command.startId)
        try {
            startSelectedProfile()
            Settings.startedByUser = true
            Settings.activeProfileCore = ProfileCore.Mihomo.name
            updateStatus(Status.Started)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start Mihomo VPN", exception)
            finishStop(command.startId)
            binder.broadcast { callback ->
                callback.onServiceAlert(Alert.StartService.ordinal, exception.message)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == Action.SERVICE) binder else super.onBind(intent)

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun querySocketUid(protocol: Int, source: String, target: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Process.INVALID_UID
        return runCatching {
            connectivityManager().getConnectionOwnerUid(
                protocol,
                source.toSocketAddress(),
                target.toSocketAddress(),
            )
        }.getOrElse {
            Log.d(TAG, "Unable to resolve connection owner: ${it.message}")
            Process.INVALID_UID
        }
    }

    private suspend fun startSelectedProfile() {
        check(prepare(this) == null) { "android: missing VPN permission" }
        val profileId = Settings.selectedProfile
        check(profileId >= 0) { "No profile is selected" }
        val profile = ProfileManager.get(profileId) ?: error("Selected profile no longer exists")
        check(profile.typed.core == ProfileCore.Mihomo) {
            "Selected profile is not a Mihomo profile"
        }
        val content = File(profile.typed.path).readText()
        var establishedDevice: ParcelFileDescriptor? = null
        try {
            when (MihomoNetworkMode.fromStorage(Settings.mihomoNetworkMode)) {
                MihomoNetworkMode.VirtualNic -> {
                    controller.start(
                        MihomoStartRequest(
                            config = MihomoConfig(content),
                            httpProxyPort = 0,
                            // The controller invokes this only after it owns the exclusive
                            // runtime lease and has loaded the profile. This prevents both a
                            // stopped-state probe race and routing startup downloads into an
                            // unserved TUN.
                            tunFactory = {
                                val device = establishTun()
                                establishedDevice = device
                                // Let Android keep tracking the current physical network.
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                    if (!setUnderlyingNetworks(null)) {
                                        Log.w(TAG, "Android did not accept automatic underlying-network selection")
                                    }
                                }
                                MihomoTunDevice(
                                    fileDescriptor = device.fd,
                                    stack = "system",
                                    gateway = "172.19.0.1/30,fdfe:dcba:9876::1/126",
                                    dns = "0.0.0.0,::",
                                    callback = this,
                                )
                            },
                        ),
                    )
                }

                MihomoNetworkMode.SystemProxy -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        error("System proxy mode requires Android 10 or newer")
                    }
                    val proxyPort = findAvailableLoopbackPort()
                    controller.start(
                        MihomoStartRequest(
                            config = MihomoConfig(content),
                            httpProxyPort = proxyPort,
                        ),
                    )
                    establishedDevice = establishSystemProxy(proxyPort)
                }
            }
            MihomoOfflineSelectionStore.applyPending(profile, controller)
            Settings.mihomoClashMode.takeIf { it in SUPPORTED_CLASH_MODES }?.let { mode ->
                controller.setMode(mode)
            }
            tun = checkNotNull(establishedDevice) { "Mihomo did not establish a VPN device" }
        } catch (exception: Exception) {
            establishedDevice?.close()
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun establishSystemProxy(proxyPort: Int): ParcelFileDescriptor {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1_500)
            .addAddress("172.19.0.1", 32)
            .addAddress("fdfe:dcba:9876::1", 128)
            // This split VPN has no routes. Apps which honor Android's HTTP
            // proxy use Mihomo; other protocols continue on the physical route.
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))
            .setMetered(false)
        if (Settings.allowBypass) builder.allowBypass()
        applyPerAppRules(builder)
        return builder.establish() ?: error("android: system proxy VPN was not prepared or was revoked")
    }

    private fun findAvailableLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

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

    private suspend fun finishStop(startId: Int? = null) {
        updateStatus(Status.Stopping)
        stopCoreAndTun()
        activeRun = null
        Settings.startedByUser = false
        ProfileRuntime.markStopped(ProfileCore.Mihomo)
        updateStatus(Status.Stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) {
            stopSelf()
        } else {
            stopSelfResult(startId)
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
            .setContentText("Mihomo is running")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun connectivityManager(): ConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun updateStatus(value: Status) {
        runtimeStatus = value
        status.postValue(value)
    }

    private fun String.toSocketAddress(): InetSocketAddress {
        val uri = URI("socket://$this")
        val host = requireNotNull(uri.host) { "Invalid socket address: $this" }
        require(uri.port in 0..65_535) { "Invalid socket port: $this" }
        return InetSocketAddress(host, uri.port)
    }
}
