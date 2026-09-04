package io.nekohasekai.sfa.mihomo

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import io.nekohasekai.sfa.mihomo.MihomoNativeBridge.TunCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class AndroidMihomoController private constructor(context: Context) : MihomoController {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _runtimeState = MutableStateFlow(MihomoRuntimeState.Stopped)
    private val _traffic = MutableStateFlow(MihomoTraffic())
    private val _connections = MutableStateFlow<List<MihomoConnection>>(emptyList())

    @Volatile
    private var api: MihomoApiClient? = null
    private var trafficJob: Job? = null
    private var connectionsJob: Job? = null
    private var currentTun: MihomoTunDevice? = null
    private var currentConfig: MihomoConfig? = null
    private var currentHttpProxyPort: Int = 0

    @Volatile
    private var activeOfflineProbe: HeadlessProbeSession? = null

    private data class ApiGeneration(
        val client: MihomoApiClient,
    )

    private data class ControllerEndpoint(
        val port: Int,
        val address: String,
        val secret: String,
    )

    override val runtimeState: StateFlow<MihomoRuntimeState> = _runtimeState.asStateFlow()
    override val traffic: StateFlow<MihomoTraffic> = _traffic.asStateFlow()
    override val connections: StateFlow<List<MihomoConnection>> = _connections.asStateFlow()

    override suspend fun validateConfig(config: MihomoConfig) = withContext(Dispatchers.IO) {
        // Import validation only decodes YAML and does not mutate the running core.
        // Keeping it outside the runtime lease prevents a slow start/provider load
        // from making a separate subscription import wait indefinitely.
        MihomoNativeBridge.initialize(appContext)
        MihomoNativeBridge.validate(config.content, controllerEndpoint.address, controllerEndpoint.secret)
    }

    override suspend fun openProbe(config: MihomoConfig): MihomoProbeSession = withContext(Dispatchers.IO) {
        val owner = Any()
        mutex.lock(owner)
        var ownsProbeCore = false
        try {
            check(_runtimeState.value == MihomoRuntimeState.Stopped && api == null) {
                "Mihomo VPN must be stopped before running an offline latency test"
            }
            // From this point no live runtime owns the native core. Only this
            // session may clean it up if initialization or loading fails.
            ownsProbeCore = true
            MihomoNativeBridge.initialize(appContext)
            val generation = loadApiGeneration(config)
            HeadlessProbeSession(generation.client, owner).also { activeOfflineProbe = it }
        } catch (exception: Throwable) {
            if (ownsProbeCore) {
                runCatching { MihomoNativeBridge.stopCore() }
            }
            mutex.unlock(owner)
            throw exception
        }
    }

    override suspend fun start(request: MihomoStartRequest) = withContext(Dispatchers.IO) {
        // Opening the stopped-state node screen can start an exclusive latency
        // probe. A user VPN start has priority and must not wait for the entire
        // subscription probe to finish.
        activeOfflineProbe?.close()
        mutex.withLock {
            check(_runtimeState.value == MihomoRuntimeState.Stopped) { "Mihomo is already active" }
            _runtimeState.value = MihomoRuntimeState.Starting
            try {
                require(request.tun == null || request.tunFactory == null) {
                    "Mihomo start request must not provide both a TUN and a TUN factory"
                }
                MihomoNativeBridge.initialize(appContext)
                // Parse and fully load the profile before Android routes device traffic
                // into our TUN. Mihomo may synchronously fetch rule providers or geodata
                // during this call; establishing the VPN first would send those requests
                // into a descriptor that has no native reader yet and black-hole startup.
                val httpProxyPort = request.httpProxyPort ?: 0
                val generation = loadApiGeneration(request.config, httpProxyPort)
                val tunDevice = request.tunFactory?.invoke() ?: request.tun
                (tunDevice?.callback ?: request.socketCallback)?.let { callback ->
                    // Install outbound socket routing before a native TUN listener or
                    // Android system proxy can accept traffic.
                    MihomoNativeBridge.prepareTun(callback.asNativeCallback())
                }
                tunDevice?.let { tun ->
                    // Native/sing-tun exclusively owns this duplicated descriptor. The
                    // VpnService keeps the original ParcelFileDescriptor for its lifecycle.
                    val nativeFd = ParcelFileDescriptor.fromFd(tun.fileDescriptor).detachFd()
                    MihomoNativeBridge.startTun(
                        nativeFd,
                        tun.stack,
                        tun.gateway,
                        tun.dns,
                    )
                    currentTun = tun
                }
                installApiGeneration(generation)
                currentConfig = request.config
                currentHttpProxyPort = httpProxyPort
                _runtimeState.value = MihomoRuntimeState.Running
            } catch (exception: Exception) {
                _runtimeState.value = MihomoRuntimeState.Failed
                stopInternal()
                throw exception
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_runtimeState.value == MihomoRuntimeState.Stopped) return@withLock
            _runtimeState.value = MihomoRuntimeState.Stopping
            stopInternal()
            _runtimeState.value = MihomoRuntimeState.Stopped
        }
    }

    override suspend fun reload(request: MihomoStartRequest) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(api != null) { "Mihomo is not running" }
            val previousConfig = currentConfig ?: error("Mihomo running config is unavailable")
            val previousHttpProxyPort = currentHttpProxyPort
            val requestedHttpProxyPort = request.httpProxyPort ?: previousHttpProxyPort
            try {
                installApiGeneration(loadApiGeneration(request.config, requestedHttpProxyPort))
                currentConfig = request.config
                currentHttpProxyPort = requestedHttpProxyPort
            } catch (reloadError: Exception) {
                val rollbackError =
                    runCatching {
                        installApiGeneration(loadApiGeneration(previousConfig, previousHttpProxyPort))
                        currentConfig = previousConfig
                        currentHttpProxyPort = previousHttpProxyPort
                    }.exceptionOrNull()
                if (rollbackError != null) {
                    reloadError.addSuppressed(rollbackError)
                    _runtimeState.value = MihomoRuntimeState.Failed
                    stopInternal()
                }
                throw reloadError
            }
        }
    }

    override suspend fun getProxyGroups(): List<MihomoProxyGroup> = withContext(Dispatchers.IO) { requireApi().getProxyGroups() }

    override suspend fun getMode(): String = withContext(Dispatchers.IO) { requireApi().getMode() }

    override suspend fun setMode(mode: String) = withContext(Dispatchers.IO) {
        requireApi()
        MihomoNativeBridge.setMode(mode)
    }

    override suspend fun selectProxy(group: String, proxy: String) = withContext(Dispatchers.IO) { requireApi().selectProxy(group, proxy) }

    override suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult = withContext(Dispatchers.IO) {
        requireApi().testDelay(proxy, url, timeoutMillis)
    }

    override suspend fun updateProxyProvider(name: String) = withContext(Dispatchers.IO) { requireApi().updateProxyProvider(name) }

    override suspend fun closeConnection(id: String) = withContext(Dispatchers.IO) {
        requireApi().closeConnection(id)
        _connections.value = _connections.value.filterNot { it.id == id }
    }

    override suspend fun closeAllConnections() = withContext(Dispatchers.IO) {
        requireApi().closeAllConnections()
        _connections.value = emptyList()
    }

    override fun observeLogs(level: String): Flow<MihomoLogEntry> = requireApi().observeLogs(level)

    private suspend fun waitForReady(api: MihomoApiClient) {
        var lastError: Throwable? = null
        val ready = withTimeoutOrNull(API_READY_TIMEOUT_MILLIS) {
            while (true) {
                runCatching(api::requireReady)
                    .onSuccess { return@withTimeoutOrNull true }
                    .onFailure { lastError = it }
                delay(API_READY_RETRY_DELAY_MILLIS)
            }
        }
        if (ready == true) return
        throw IllegalStateException("Mihomo API did not become ready", lastError)
    }

    private suspend fun loadApiGeneration(
        config: MihomoConfig,
        httpProxyPort: Int = 0,
    ): ApiGeneration {
        MihomoNativeBridge.validate(config.content, controllerEndpoint.address, controllerEndpoint.secret)
        MihomoNativeBridge.load(config.content, controllerEndpoint.address, controllerEndpoint.secret, httpProxyPort)
        // Mihomo's controller remains on one authenticated loopback endpoint
        // for the process. Native config application is synchronous after the
        // first start, so readiness cannot observe an older config generation.
        val client = MihomoApiClient(controllerEndpoint.port, controllerEndpoint.secret)
        waitForReady(client)
        if (httpProxyPort > 0) client.requireHttpProxyPort(httpProxyPort)
        return ApiGeneration(client)
    }

    private fun installApiGeneration(generation: ApiGeneration) {
        api = generation.client
        observeRuntime(generation.client)
    }

    private fun observeRuntime(api: MihomoApiClient) {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            while (true) {
                api.observeTraffic()
                    .catch { delay(API_RECONNECT_DELAY_MILLIS) }
                    .collectLatest {
                        if (this@AndroidMihomoController.api === api) {
                            _traffic.value = it
                        }
                    }
                delay(API_RECONNECT_DELAY_MILLIS)
            }
        }
        connectionsJob?.cancel()
        connectionsJob = scope.launch {
            while (true) {
                try {
                    val latest = api.getConnections()
                    if (this@AndroidMihomoController.api === api) {
                        _connections.value = latest
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // The controller can be briefly unavailable during reload.
                }
                delay(CONNECTION_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopInternal() {
        trafficJob?.cancel()
        trafficJob = null
        connectionsJob?.cancel()
        connectionsJob = null
        MihomoNativeBridge.stopTun()
        currentTun = null
        MihomoNativeBridge.stopCore()
        api = null
        currentConfig = null
        currentHttpProxyPort = 0
        _traffic.value = MihomoTraffic()
        _connections.value = emptyList()
    }

    private fun requireApi(): MihomoApiClient = api ?: error("Mihomo is not running")

    private inner class HeadlessProbeSession(
        private val client: MihomoApiClient,
        private val owner: Any,
    ) : MihomoProbeSession {
        private val closed = AtomicBoolean(false)

        override suspend fun getProxyGroups(): List<MihomoProxyGroup> = withContext(Dispatchers.IO) {
            check(!closed.get()) { "Mihomo offline probe is closed" }
            client.getProxyGroups()
        }

        override suspend fun testDelay(
            proxy: String,
            url: String,
            timeoutMillis: Long,
        ): MihomoDelayResult = withContext(Dispatchers.IO) {
            check(!closed.get()) { "Mihomo offline probe is closed" }
            client.testDelay(proxy, url, timeoutMillis)
        }

        override suspend fun close() {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!closed.compareAndSet(false, true)) return@withContext
                try {
                    MihomoNativeBridge.stopCore()
                } finally {
                    if (activeOfflineProbe === this@HeadlessProbeSession) {
                        activeOfflineProbe = null
                    }
                    mutex.unlock(owner)
                }
            }
        }
    }

    companion object {
        internal fun create(context: Context): AndroidMihomoController = AndroidMihomoController(context)

        private val controllerEndpoint: ControllerEndpoint by lazy {
            val address = java.net.InetAddress.getByName("127.0.0.1")
            val port = ServerSocket(0, 0, address).use { it.localPort }
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
                .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }
            ControllerEndpoint(port, "127.0.0.1:$port", secret)
        }

        private const val CONNECTION_REFRESH_INTERVAL_MILLIS = 1_000L
        private const val API_RECONNECT_DELAY_MILLIS = 500L
        private const val API_READY_RETRY_DELAY_MILLIS = 200L
        private const val API_READY_TIMEOUT_MILLIS = 5_000L
    }

    private fun MihomoTunCallback.asNativeCallback() = object : TunCallback {
        override fun protectSocket(fd: Int): Boolean = this@asNativeCallback.protectSocket(fd)

        override fun querySocketUid(protocol: Int, source: String, target: String): Int = this@asNativeCallback.querySocketUid(protocol, source, target)
    }
}
