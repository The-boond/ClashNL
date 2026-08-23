package io.nekohasekai.sfa.compose.screen.connections

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Connection
import io.nekohasekai.sfa.compose.model.ConnectionSort
import io.nekohasekai.sfa.compose.model.ConnectionStateFilter
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.ktx.toList
import io.nekohasekai.sfa.mihomo.MihomoConnection
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoRuntimeState
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    val allConnections: List<Connection> = emptyList(),
    val isLoading: Boolean = false,
    val stateFilter: ConnectionStateFilter = ConnectionStateFilter.Active,
    val sort: ConnectionSort = ConnectionSort.ByDate,
    val searchText: String = "",
    val isSearchActive: Boolean = false,
)

sealed class ConnectionsEvent : ScreenEvent {
    data class ConnectionClosed(val id: String) : ConnectionsEvent()
    data object AllConnectionsClosed : ConnectionsEvent()
}

class ConnectionsViewModel :
    BaseViewModel<ConnectionsUiState, ConnectionsEvent>(),
    CommandClient.Handler {
    private val commandClient = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Connections,
        this,
    )
    private val mihomoController = MihomoRuntimeRepository.controller(Application.application)

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped

    private val _visibleCount = MutableStateFlow(0)

    private var connectionsStore: Connections? = null
    private val connectionsMutex = Mutex()
    private val connectionsGeneration = AtomicLong(0)
    private var activeRemoteServerId: Long? = null

    override fun createInitialState() = ConnectionsUiState()

    private data class ConnectionState(
        val foreground: Boolean,
        val screenOn: Boolean,
        val visibleCount: Int,
        val status: Status,
        val remoteServerId: Long?,
        val remoteConnected: Boolean,
    )

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                AppLifecycleObserver.isScreenOn,
                _visibleCount,
                _serviceStatus,
                combine(
                    RemoteControlManager.remoteServer,
                    RemoteControlManager.isConnected,
                ) { remoteServer, remoteConnected -> remoteServer?.id to remoteConnected },
            ) { foreground, screenOn, visibleCount, status, (remoteServerId, remoteConnected) ->
                ConnectionState(foreground, screenOn, visibleCount, status, remoteServerId, remoteConnected)
            }.collect { state ->
                if (activeRemoteServerId != state.remoteServerId) {
                    activeRemoteServerId = state.remoteServerId
                    withContext(Dispatchers.Default) {
                        connectionsMutex.withLock {
                            connectionsStore = null
                        }
                        connectionsGeneration.incrementAndGet()
                    }
                }
                val shouldConnect = state.remoteServerId != null && state.foreground && state.screenOn &&
                    state.visibleCount > 0 && state.remoteConnected
                if (shouldConnect) {
                    updateState { copy(isLoading = true) }
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }

        viewModelScope.launch {
            combine(
                RemoteControlManager.remoteServer,
                mihomoController.runtimeState,
                mihomoController.connections,
            ) { remoteServer, runtimeState, connections ->
                Triple(remoteServer, runtimeState, connections)
            }.collect { (remoteServer, runtimeState, connections) ->
                if (remoteServer == null) {
                    if (runtimeState == MihomoRuntimeState.Running) {
                        publishLocalConnections(connections)
                    } else {
                        updateState {
                            copy(connections = emptyList(), allConnections = emptyList(), isLoading = false)
                        }
                    }
                }
            }
        }
    }

    fun setVisible(visible: Boolean) {
        _visibleCount.value = (_visibleCount.value + if (visible) 1 else -1).coerceAtLeast(0)
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }

    private suspend fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        if (status != Status.Started) {
            withContext(Dispatchers.Default) {
                connectionsMutex.withLock {
                    connectionsStore = null
                }
                connectionsGeneration.incrementAndGet()
            }
            updateState {
                copy(connections = emptyList(), allConnections = emptyList(), isLoading = false)
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) return
        lastServiceStatus = status
        viewModelScope.launch {
            _serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    fun setStateFilter(filter: ConnectionStateFilter) {
        updateState { copy(stateFilter = filter) }
        requestConnectionsRefresh()
    }

    fun setSort(sort: ConnectionSort) {
        updateState { copy(sort = sort) }
        requestConnectionsRefresh()
    }

    fun setSearchText(text: String) {
        updateState { copy(searchText = text) }
        requestConnectionsRefresh()
    }

    fun toggleSearch() {
        val newSearchActive = !currentState.isSearchActive
        updateState {
            copy(
                isSearchActive = newSearchActive,
                searchText = if (newSearchActive) searchText else "",
            )
        }
        if (!newSearchActive) {
            requestConnectionsRefresh()
        }
    }

    fun closeConnection(connectionId: String) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (remoteServerId != null) {
                    if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
                    CommandTarget.standaloneClient().closeConnection(connectionId)
                } else {
                    if (RemoteControlManager.remoteServer.value != null) return@launch
                    mihomoController.closeConnection(connectionId)
                }
                withContext(Dispatchers.Main) {
                    sendEvent(ConnectionsEvent.ConnectionClosed(connectionId))
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun closeAllConnections() {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (remoteServerId != null) {
                    if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
                    CommandTarget.standaloneClient().closeConnections()
                } else {
                    if (RemoteControlManager.remoteServer.value != null) return@launch
                    mihomoController.closeAllConnections()
                }
                withContext(Dispatchers.Main) {
                    sendEvent(ConnectionsEvent.AllConnectionsClosed)
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    override fun onConnected() {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value?.id == remoteServerId) {
                updateState { copy(isLoading = false) }
            }
        }
    }

    override fun onDisconnected() {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Default) {
            connectionsMutex.withLock {
                connectionsStore = null
            }
            connectionsGeneration.incrementAndGet()
            withContext(Dispatchers.Main) {
                if (RemoteControlManager.remoteServer.value?.id == remoteServerId) {
                    updateState {
                        copy(connections = emptyList(), allConnections = emptyList(), isLoading = false)
                    }
                }
            }
        }
    }

    override fun writeConnectionEvents(events: ConnectionEvents) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val generation = connectionsGeneration.get()
            val snapshot = connectionsMutex.withLock {
                if (connectionsStore == null) {
                    connectionsStore = Connections()
                }
                val store = connectionsStore ?: return@withLock null
                store.applyEvents(events)
                buildConnectionLists(store, uiState.value)
            } ?: return@launch
            if (connectionsGeneration.get() != generation) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (
                    connectionsGeneration.get() != generation ||
                    RemoteControlManager.remoteServer.value?.id != remoteServerId
                ) {
                    return@withContext
                }
                updateState {
                    copy(
                        connections = snapshot.connections,
                        allConnections = snapshot.allConnections,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun requestConnectionsRefresh() {
        if (RemoteControlManager.remoteServer.value == null) {
            viewModelScope.launch(Dispatchers.Default) {
                if (mihomoController.runtimeState.value == MihomoRuntimeState.Running) {
                    publishLocalConnections(mihomoController.connections.value)
                } else {
                    withContext(Dispatchers.Main) {
                        updateState {
                            copy(connections = emptyList(), allConnections = emptyList(), isLoading = false)
                        }
                    }
                }
            }
            return
        }
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val generation = connectionsGeneration.get()
            val snapshot = connectionsMutex.withLock {
                val store = connectionsStore ?: return@withLock null
                buildConnectionLists(store, uiState.value)
            } ?: return@launch
            if (connectionsGeneration.get() != generation) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (
                    connectionsGeneration.get() != generation ||
                    RemoteControlManager.remoteServer.value?.id != remoteServerId
                ) {
                    return@withContext
                }
                updateState {
                    copy(
                        connections = snapshot.connections,
                        allConnections = snapshot.allConnections,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun buildConnectionLists(
        connections: Connections,
        currentState: ConnectionsUiState,
    ): ConnectionLists {
        val allConnectionList = connections.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }

        connections.filterState(currentState.stateFilter.libboxValue)

        when (currentState.sort) {
            ConnectionSort.ByDate -> connections.sortByDate()
            ConnectionSort.ByTraffic -> connections.sortByTraffic()
            ConnectionSort.ByTrafficTotal -> connections.sortByTrafficTotal()
        }

        val connectionList = connections.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }
            .filter { it.performSearch(currentState.searchText) }

        return ConnectionLists(
            connections = connectionList,
            allConnections = allConnectionList,
        )
    }

    private suspend fun publishLocalConnections(connections: List<MihomoConnection>) {
        val snapshot = buildLocalConnectionLists(connections, uiState.value)
        withContext(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value == null) {
                updateState {
                    copy(
                        connections = snapshot.connections,
                        allConnections = snapshot.allConnections,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun buildLocalConnectionLists(
        connections: List<MihomoConnection>,
        currentState: ConnectionsUiState,
    ): ConnectionLists {
        val allConnectionList = connections.map(Connection::from)
        val activeConnections = when (currentState.stateFilter) {
            ConnectionStateFilter.Closed -> emptyList()
            ConnectionStateFilter.All, ConnectionStateFilter.Active -> allConnectionList
        }
        val sortedConnections = when (currentState.sort) {
            ConnectionSort.ByDate -> activeConnections.sortedByDescending { it.createdAt }
            ConnectionSort.ByTraffic -> activeConnections.sortedByDescending { it.upload + it.download }
            ConnectionSort.ByTrafficTotal -> activeConnections.sortedByDescending { it.uploadTotal + it.downloadTotal }
        }
        return ConnectionLists(
            connections = sortedConnections.filter { it.performSearch(currentState.searchText) },
            allConnections = allConnectionList,
        )
    }

    private data class ConnectionLists(
        val connections: List<Connection>,
        val allConnections: List<Connection>,
    )
}
