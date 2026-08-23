package io.nekohasekai.sfa.compose.screen.log

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.compose.util.AnsiColorUtils
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.mihomo.MihomoLogEntry
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoRuntimeState
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList

class LogViewModel :
    BaseLogViewModel(),
    CommandClient.Handler {
    companion object {
        private val maxLines = 3000
        private const val LOG_RECONNECT_DELAY_MILLIS = 1_000L
    }

    private val bufferedLogs = LinkedList<ProcessedLogEntry>()
    private val commandClient =
        CommandClient(
            scope = viewModelScope,
            connectionType = CommandClient.ConnectionType.Log,
            handler = this,
        )
    private val mihomoController = MihomoRuntimeRepository.controller(Application.application)

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                mihomoController.runtimeState,
            ) { foreground, remoteServer, remoteConnected, runtimeState ->
                SessionTarget(
                    connectRemote = foreground && remoteServer != null && remoteConnected,
                    connectLocal = foreground && remoteServer == null && runtimeState == MihomoRuntimeState.Running,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collectLatest { target ->
                if (target.connectRemote) {
                    _uiState.update { it.copy(isConnected = false) }
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
                if (target.connectLocal) {
                    observeLocalLogs()
                } else if (!target.connectRemote) {
                    _uiState.update { it.copy(isConnected = false) }
                }
            }
        }
    }

    private data class SessionTarget(
        val connectRemote: Boolean,
        val connectLocal: Boolean,
        val remoteServerId: Long?,
    )

    private fun processLogEntry(entry: LogEntry): ProcessedLogEntry {
        val level = LogLevel.entries.find { it.priority == entry.level } ?: LogLevel.Default
        return ProcessedLogEntry(
            id = logIdGenerator.incrementAndGet(),
            entry = LogEntryData(level = level, message = entry.message),
            annotatedString = AnsiColorUtils.ansiToAnnotatedString(entry.message),
        )
    }

    private fun processLogEntry(entry: MihomoLogEntry): ProcessedLogEntry {
        val level = when (entry.level.lowercase()) {
            "panic" -> LogLevel.PANIC
            "fatal" -> LogLevel.FATAL
            "error" -> LogLevel.ERROR
            "warning", "warn" -> LogLevel.WARNING
            "info" -> LogLevel.INFO
            "debug" -> LogLevel.DEBUG
            "trace" -> LogLevel.TRACE
            else -> LogLevel.Default
        }
        return ProcessedLogEntry(
            id = logIdGenerator.incrementAndGet(),
            entry = LogEntryData(level = level, message = entry.message),
            annotatedString = AnsiColorUtils.ansiToAnnotatedString(entry.message),
        )
    }

    private suspend fun observeLocalLogs() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                _uiState.update { it.copy(isConnected = true) }
                mihomoController.observeLogs().collect { entry ->
                    appendProcessedLogs(listOf(processLogEntry(entry)), remoteServerId = null)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
            } finally {
                _uiState.update { it.copy(isConnected = false) }
            }
            kotlinx.coroutines.delay(LOG_RECONNECT_DELAY_MILLIS)
        }
    }

    override fun updateServiceStatus(status: Status) {
        _uiState.update { it.copy(serviceStatus = status) }

        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        when (status) {
            Status.Stopped, Status.Stopping -> {
                _uiState.update { it.copy(isConnected = false) }
            }

            else -> {}
        }
    }

    override fun onConnected() {
        if (RemoteControlManager.remoteServer.value == null) {
            return
        }
        _uiState.update { it.copy(isConnected = true) }
    }

    override fun onDisconnected() {
        if (RemoteControlManager.remoteServer.value == null) {
            return
        }
        _uiState.update { it.copy(isConnected = false) }
    }

    override fun setDefaultLogLevel(level: Int) {
        val logLevel = LogLevel.entries.find { it.priority == level } ?: error("Unknown log level: $level")
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { it.copy(defaultLogLevel = logLevel) }
            updateDisplayedLogs()
        }
    }

    override fun clearLogs() {
        viewModelScope.launch(Dispatchers.Main) {
            allLogs.clear()
            bufferedLogs.clear()
            _uiState.update { it.copy(isPaused = false) }
            updateDisplayedLogs()
        }
    }

    override fun requestClearLogs() {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id
        if (remoteServerId == null) {
            clearLogs()
            return
        }
        viewModelScope.launch {
            if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
            val sent =
                withContext(Dispatchers.IO) {
                    if (RemoteControlManager.remoteServer.value?.id != remoteServerId) {
                        return@withContext null
                    }
                    runCatching {
                        CommandTarget.standaloneClient().clearLogs()
                    }.isSuccess
                }
            if (sent == null) return@launch
            // With the service stopped there is no broadcast to clear the UI,
            // so the local buffer is cleared directly.
            if (!sent && RemoteControlManager.remoteServer.value?.id == remoteServerId) {
                clearLogs()
            }
        }
    }

    override fun appendLogs(message: List<LogEntry>) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        appendProcessedLogs(message.map { processLogEntry(it) }, remoteServerId)
    }

    private fun appendProcessedLogs(processedLogs: List<ProcessedLogEntry>, remoteServerId: Long?) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentRemoteServerId = RemoteControlManager.remoteServer.value?.id
            if (
                (remoteServerId == null && currentRemoteServerId != null) ||
                (remoteServerId != null && currentRemoteServerId != remoteServerId)
            ) {
                return@launch
            }
            if (_uiState.value.isPaused) {
                bufferedLogs.addAll(processedLogs)
            } else {
                val totalSize = allLogs.size + processedLogs.size
                val removeCount = (totalSize - maxLines).coerceAtLeast(0)

                if (removeCount > 0) {
                    repeat(removeCount) {
                        allLogs.removeFirst()
                    }
                }

                allLogs.addAll(processedLogs)
                updateDisplayedLogs()

                if (_autoScrollEnabled.value && !_uiState.value.isPaused && !_uiState.value.isSearchActive) {
                    scrollToBottom()
                }
            }
        }
    }

    override fun togglePause() {
        val currentState = _uiState.value
        if (currentState.isPaused && bufferedLogs.isNotEmpty()) {
            val totalSize = allLogs.size + bufferedLogs.size
            val removeCount = (totalSize - maxLines).coerceAtLeast(0)

            if (removeCount > 0) {
                repeat(removeCount) {
                    allLogs.removeFirst()
                }
            }

            allLogs.addAll(bufferedLogs)
            bufferedLogs.clear()
        }

        _uiState.update { it.copy(isPaused = !it.isPaused) }
        updateDisplayedLogs()
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }
}
