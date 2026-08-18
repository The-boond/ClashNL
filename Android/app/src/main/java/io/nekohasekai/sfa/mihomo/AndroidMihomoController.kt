package io.nekohasekai.sfa.mihomo

import android.content.Context
import android.util.Base64
import io.nekohasekai.sfa.mihomo.MihomoNativeBridge.TunCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.security.SecureRandom

class AndroidMihomoController(context: Context) : MihomoController {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _runtimeState = MutableStateFlow(MihomoRuntimeState.Stopped)
    private val _traffic = MutableStateFlow(MihomoTraffic())
    private val _connections = MutableStateFlow<List<MihomoConnection>>(emptyList())
    private var api: MihomoApiClient? = null
    private var controllerAddress: String? = null
    private var apiSecret: String? = null
    private var trafficJob: Job? = null
    private var connectionsJob: Job? = null
    private var currentTun: MihomoTunDevice? = null

    override val runtimeState: StateFlow<MihomoRuntimeState> = _runtimeState.asStateFlow()
    override val traffic: StateFlow<MihomoTraffic> = _traffic.asStateFlow()
    override val connections: StateFlow<List<MihomoConnection>> = _connections.asStateFlow()

    override suspend fun validateConfig(config: MihomoConfig) = withContext(Dispatchers.IO) {
        MihomoNativeBridge.initialize(appContext)
        MihomoNativeBridge.validate(config.content, "127.0.0.1:${reserveLoopbackPort()}", newSecret())
    }

    override suspend fun start(request: MihomoStartRequest) = mutex.withLock {
        check(_runtimeState.value == MihomoRuntimeState.Stopped) { "Mihomo is already active" }
        _runtimeState.value = MihomoRuntimeState.Starting
        try {
            MihomoNativeBridge.initialize(appContext)
            val port = reserveLoopbackPort()
            val secret = newSecret()
            val address = "127.0.0.1:$port"
            MihomoNativeBridge.validate(request.config.content, address, secret)
            MihomoNativeBridge.load(request.config.content, address, secret)
            val client = MihomoApiClient(port, secret)
            waitForReady(client)
            request.tun?.let { tun ->
                MihomoNativeBridge.startTun(
                    tun.fileDescriptor,
                    tun.stack,
                    tun.gateway,
                    tun.dns,
                    tun.callback.asNativeCallback(),
                )
                currentTun = tun
            }
            api = client
            controllerAddress = address
            apiSecret = secret
            observeRuntime(client)
            _runtimeState.value = MihomoRuntimeState.Running
        } catch (exception: Exception) {
            _runtimeState.value = MihomoRuntimeState.Failed
            stopInternal()
            throw exception
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            if (_runtimeState.value == MihomoRuntimeState.Stopped) return@withLock
            _runtimeState.value = MihomoRuntimeState.Stopping
            stopInternal()
            _runtimeState.value = MihomoRuntimeState.Stopped
        }
    }

    override suspend fun reload(request: MihomoStartRequest) = mutex.withLock {
        val client = api ?: error("Mihomo is not running")
        val address = controllerAddress ?: error("Mihomo controller address is unavailable")
        val secret = apiSecret ?: error("Mihomo controller secret is unavailable")
        MihomoNativeBridge.validate(request.config.content, address, secret)
        MihomoNativeBridge.load(request.config.content, address, secret)
        waitForReady(client)
    }

    override suspend fun getProxyGroups(): List<MihomoProxyGroup> = requireApi().getProxyGroups()

    override suspend fun selectProxy(group: String, proxy: String) {
        requireApi().selectProxy(group, proxy)
    }

    override suspend fun testDelay(proxy: String, url: String, timeoutMillis: Long): MihomoDelayResult = requireApi().testDelay(proxy, url, timeoutMillis)

    override suspend fun updateProxyProvider(name: String) {
        requireApi().updateProxyProvider(name)
    }

    private suspend fun waitForReady(api: MihomoApiClient) {
        var lastError: Throwable? = null
        repeat(25) {
            runCatching(api::requireReady).onSuccess { return }.onFailure { lastError = it }
            delay(200)
        }
        throw IllegalStateException("Mihomo API did not become ready", lastError)
    }

    private fun observeRuntime(api: MihomoApiClient) {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            api.observeTraffic().collectLatest { _traffic.value = it }
        }
        connectionsJob?.cancel()
        connectionsJob = scope.launch {
            while (true) {
                runCatching { api.getConnections() }
                    .onSuccess { _connections.value = it }
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
        controllerAddress = null
        apiSecret = null
        _traffic.value = MihomoTraffic()
        _connections.value = emptyList()
    }

    private fun requireApi(): MihomoApiClient = api ?: error("Mihomo is not running")

    private fun reserveLoopbackPort(): Int = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun newSecret(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }

    private companion object {
        const val CONNECTION_REFRESH_INTERVAL_MILLIS = 1_000L
    }

    private fun MihomoTunCallback.asNativeCallback() = object : TunCallback {
        override fun protectSocket(fd: Int): Boolean = this@asNativeCallback.protectSocket(fd)

        override fun querySocketUid(protocol: Int, source: String, target: String): Int = this@asNativeCallback.querySocketUid(protocol, source, target)
    }
}
