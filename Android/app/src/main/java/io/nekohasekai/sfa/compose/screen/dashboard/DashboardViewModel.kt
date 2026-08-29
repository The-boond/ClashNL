package io.nekohasekai.sfa.compose.screen.dashboard

import android.os.Build
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.MihomoVpnService
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.mihomo.MihomoNetworkMode
import io.nekohasekai.sfa.mihomo.MihomoProxyGroup
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoRuntimeState
import io.nekohasekai.sfa.mihomo.MihomoTraffic
import io.nekohasekai.sfa.repository.ProfileRemoteRepository
import io.nekohasekai.sfa.runtime.ProfileRuntime
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteControlManager
import io.nekohasekai.sfa.utils.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

enum class CardGroup {
    Subscriptions,
    CurrentProxy,
    NetworkSettings,
    ProxyMode,
    TrafficStats,
    IPInfo,
    ClashInfo,
    SystemInfo,
}

val configurableDashboardCards =
    listOf(
        CardGroup.NetworkSettings,
        CardGroup.ProxyMode,
        CardGroup.TrafficStats,
    )

enum class CardWidth {
    Half,
    Full,
}

data class DashboardUiState(
    val serviceStatus: Status = Status.Stopped,
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: Long = -1L,
    val selectedProfileName: String? = null,
    val isLoading: Boolean = false,
    val hasGroups: Boolean = false,
    val groupsCount: Int = 0,
    val currentProxyGroup: String? = null,
    val currentProxyName: String? = null,
    val connectionsCount: Int = 0,
    val serviceStartTime: Long? = null,
    val deprecatedNotes: List<DeprecatedNote> = emptyList(),
    val showDeprecatedDialog: Boolean = false,
    val showAddProfileSheet: Boolean = false,
    val showProfilePickerSheet: Boolean = false,
    val updatingProfileId: Long? = null,
    val updatedProfileId: Long? = null,
    // Status
    val memory: String = "",
    val goroutines: String = "",
    val isStatusVisible: Boolean = false,
    // Traffic
    val trafficVisible: Boolean = false,
    val connectionsIn: String = "0",
    val connectionsOut: String = "0",
    val uplink: String = "0 B/s",
    val downlink: String = "0 B/s",
    val uplinkTotal: String = "0 B",
    val downlinkTotal: String = "0 B",
    val uplinkHistory: List<Float> = List(30) { 0f },
    val downlinkHistory: List<Float> = List(30) { 0f },
    // Clash Mode
    val clashModeVisible: Boolean = true,
    val clashModes: List<String> = listOf("rule", "global", "direct"),
    val selectedClashMode: String = "rule",
    // Network mode
    val networkMode: MihomoNetworkMode = MihomoNetworkMode.VirtualNic,
    // System Proxy
    val systemProxyVisible: Boolean = false,
    val systemProxyEnabled: Boolean = false,
    val systemProxySwitching: Boolean = false,
    // Public IP information (loaded only after the dashboard is opened)
    val publicIp: String? = null,
    val publicIpLocation: String? = null,
    val publicIpColo: String? = null,
    val publicIpLoading: Boolean = false,
    val publicIpError: String? = null,
    // Card visibility settings
    val visibleCards: Set<CardGroup> =
        configurableDashboardCards.toSet(),
    val cardOrder: List<CardGroup> =
        configurableDashboardCards,
    val cardWidths: Map<CardGroup, CardWidth> =
        mapOf(
            CardGroup.NetworkSettings to CardWidth.Full,
            CardGroup.ProxyMode to CardWidth.Full,
            CardGroup.TrafficStats to CardWidth.Full,
        ),
    val showCardSettingsDialog: Boolean = false,
) {
    data class DeprecatedNote(val message: String, val migrationLink: String?)
}

internal data class DashboardProxySelection(
    val group: String,
    val node: String,
)

internal fun dashboardProxySelection(
    mode: String,
    groups: List<MihomoProxyGroup>,
): DashboardProxySelection? {
    val groupsByName = groups.associateBy { it.name }
    val root = when (mode.lowercase()) {
        "direct" -> return DashboardProxySelection("DIRECT", "DIRECT")
        "global" -> groupsByName["GLOBAL"]
        else -> groups.firstOrNull {
            it.name != "GLOBAL" && it.selectable && it.selected.isNotBlank()
        } ?: groups.firstOrNull {
            it.name != "GLOBAL" && it.selected.isNotBlank()
        }
    } ?: return null

    var node = root.selected
    val visited = mutableSetOf(root.name)
    while (node.isNotBlank()) {
        val nested = groupsByName[node] ?: break
        if (!visited.add(nested.name) || nested.selected.isBlank()) break
        node = nested.selected
    }
    return node.takeIf(String::isNotBlank)?.let { DashboardProxySelection(root.name, it) }
}

// DashboardViewModel now only uses UiEvent for all events
// No need for DashboardEvent anymore as all events are handled globally

class DashboardViewModel :
    BaseViewModel<DashboardUiState, UiEvent>(),
    CommandClient.Handler {
    companion object {
        private const val IP_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        private val LOCAL_CLASH_MODES = listOf("rule", "global", "direct")
    }

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus: StateFlow<Status> = _serviceStatus.asStateFlow()

    internal val commandClient =
        CommandClient(
            viewModelScope,
            listOf(
                CommandClient.ConnectionType.Status,
                CommandClient.ConnectionType.ClashMode,
                CommandClient.ConnectionType.Groups,
            ),
            this,
        )
    private val mihomoController = MihomoRuntimeRepository.controller(Application.application)
    private var localServiceStartTime: Long? = null
    private var localUplinkTotal = 0L
    private var localDownlinkTotal = 0L
    private var localGroupsRefreshJob: Job? = null
    private val localModeSelectionMutex = Mutex()
    private val localModeSelectionSequence = AtomicLong()
    private val ipRefreshSequence = AtomicLong()

    override fun createInitialState(): DashboardUiState {
        val savedOrder = loadItemOrder()
        val disabledItems = loadDisabledItems()

        // Calculate visible items (all items minus disabled)
        val allItems = configurableDashboardCards.toSet()
        val visibleCards = allItems - disabledItems

        return DashboardUiState(
            cardOrder = savedOrder,
            visibleCards = visibleCards,
            clashModeVisible = true,
            clashModes = LOCAL_CLASH_MODES,
            selectedClashMode = persistedClashMode(),
            networkMode = MihomoNetworkMode.fromStorage(Settings.mihomoNetworkMode),
        )
    }

    init {
        loadProfiles()
        ProfileManager.registerCallback(::onProfilesChanged)

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
            ) { foreground, remoteServer, remoteConnected ->
                SessionTarget(
                    connect = foreground && remoteServer != null && remoteConnected,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
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
            ) { remoteServer, runtimeState -> remoteServer to runtimeState }
                .distinctUntilChanged()
                .collect { (remoteServer, runtimeState) ->
                    if (remoteServer == null) {
                        updateServiceStatus(runtimeState.toServiceStatus())
                    }
                }
        }

        viewModelScope.launch {
            combine(
                RemoteControlManager.remoteServer,
                mihomoController.traffic,
            ) { remoteServer, traffic -> remoteServer to traffic }
                .collect { (remoteServer, traffic) ->
                    if (remoteServer == null && mihomoController.runtimeState.value == MihomoRuntimeState.Running) {
                        updateLocalTraffic(traffic)
                    }
                }
        }

        viewModelScope.launch {
            combine(
                RemoteControlManager.remoteServer,
                mihomoController.connections,
            ) { remoteServer, connections -> remoteServer to connections }
                .collect { (remoteServer, connections) ->
                    if (remoteServer == null) {
                        val connectionCount = connections.size
                        updateState {
                            copy(
                                connectionsCount = connectionCount,
                                connectionsIn = connectionCount.toString(),
                                connectionsOut = connectionCount.toString(),
                            )
                        }
                    }
                }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    private fun MihomoRuntimeState.toServiceStatus(): Status = when (this) {
        MihomoRuntimeState.Stopped, MihomoRuntimeState.Failed -> Status.Stopped
        MihomoRuntimeState.Starting -> Status.Starting
        MihomoRuntimeState.Running -> Status.Started
        MihomoRuntimeState.Stopping -> Status.Stopping
    }

    override fun onCleared() {
        super.onCleared()
        ProfileManager.unregisterCallback(::onProfilesChanged)
        commandClient.disconnect()
    }

    private fun onProfilesChanged() {
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profiles = ProfileManager.list()
                var selectedId = Settings.selectedProfile
                var selectionChanged = false
                if (profiles.none { it.id == selectedId && it.typed.core == io.nekohasekai.sfa.database.ProfileCore.Mihomo }) {
                    selectedId = profiles.firstOrNull {
                        it.typed.core == io.nekohasekai.sfa.database.ProfileCore.Mihomo
                    }?.id ?: -1L
                    Settings.selectedProfile = selectedId
                    selectionChanged = true
                }
                if (selectionChanged) {
                    UpdateProfileWork.reconfigureUpdater()
                }

                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            profiles = profiles,
                            selectedProfileId = selectedId,
                            selectedProfileName = profiles.find { it.id == selectedId }?.name,
                        )
                    }
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    private fun checkDeprecatedNotes(remoteServerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Check if deprecated warnings are disabled
                if (
                    Settings.disableDeprecatedWarnings ||
                    RemoteControlManager.remoteServer.value?.id != remoteServerId
                ) {
                    return@launch
                }

                val notes = CommandTarget.standaloneClient().deprecatedNotes
                if (notes.hasNext()) {
                    val notesList = mutableListOf<DashboardUiState.DeprecatedNote>()
                    while (notes.hasNext()) {
                        val note = notes.next()
                        notesList.add(
                            DashboardUiState.DeprecatedNote(
                                message = note.message(),
                                migrationLink = note.migrationLink,
                            ),
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (RemoteControlManager.remoteServer.value?.id == remoteServerId) {
                            updateState {
                                copy(
                                    deprecatedNotes = notesList,
                                    showDeprecatedDialog = notesList.isNotEmpty(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun toggleService() {
        when (currentState.serviceStatus) {
            Status.Starting, Status.Started -> stopService()
            Status.Stopped -> {
                // Lock startup-sensitive settings before the service snapshots them.
                updateServiceStatus(Status.Starting)
                sendGlobalEvent(UiEvent.RequestStartService)
            }
            else -> { /* Ignore while transitioning */ }
        }
    }

    fun cancelPendingServiceStart() {
        if (
            currentState.serviceStatus == Status.Starting &&
            mihomoController.runtimeState.value == MihomoRuntimeState.Stopped
        ) {
            updateServiceStatus(Status.Stopped)
        }
    }

    private fun stopService() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ProfileRuntime.stopActive(Application.application)
                // Status will be updated via updateServiceStatus callback
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun dismissDeprecatedNote() {
        val notes = currentState.deprecatedNotes
        if (notes.isNotEmpty()) {
            updateState {
                copy(
                    deprecatedNotes = notes.drop(1),
                    showDeprecatedDialog = notes.size > 1,
                )
            }
        }
    }

    fun selectProfile(profileId: Long) {
        if (currentState.isLoading) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateState { copy(isLoading = true) }
                val profile = ProfileManager.get(profileId) ?: return@launch
                require(profile.typed.core == io.nekohasekai.sfa.database.ProfileCore.Mihomo) {
                    "此版本仅支持 Mihomo 配置，请重新导入 Clash/Mihomo YAML 订阅"
                }
                val serviceWasRunning = _serviceStatus.value == Status.Started
                val previousProfileId = Settings.selectedProfile

                Settings.selectedProfile = profileId

                try {
                    if (serviceWasRunning) {
                        ProfileRuntime.reloadSelectedIfRunning(Application.application)
                        refreshLocalGroups()
                    }
                } catch (exception: Exception) {
                    Settings.selectedProfile = previousProfileId
                    if (serviceWasRunning && previousProfileId >= 0) {
                        runCatching { ProfileRuntime.reloadSelectedIfRunning(Application.application) }
                    }
                    throw exception
                }

                withContext(Dispatchers.Main) {
                    loadProfiles()
                }
            } catch (e: Exception) {
                sendError(e)
            } finally {
                updateState { copy(isLoading = false) }
            }
        }
    }

    fun editProfile(profile: Profile) {
        sendGlobalEvent(UiEvent.EditProfile(profile.id))
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deletingSelected = profile.id == Settings.selectedProfile
                if (deletingSelected && _serviceStatus.value != Status.Stopped) {
                    ProfileRuntime.stopActive(Application.application)
                }
                // Update UI immediately for responsiveness
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            profiles = profiles.filter { p -> p.id != profile.id },
                        )
                    }
                }
                // Then delete from database
                ProfileManager.delete(profile)
                if (deletingSelected) {
                    Settings.selectedProfile = ProfileManager.list()
                        .firstOrNull { it.typed.core == io.nekohasekai.sfa.database.ProfileCore.Mihomo }
                        ?.id ?: -1L
                }
                UpdateProfileWork.reconfigureUpdater()
            } catch (e: Exception) {
                // Reload profiles if deletion fails
                loadProfiles()
                sendError(e)
            }
        }
    }

    fun updateProfile(profile: Profile) {
        if (profile.typed.type != TypedProfile.Type.Remote) return

        viewModelScope.launch(Dispatchers.IO) {
            // Set updating state
            withContext(Dispatchers.Main) {
                updateState { copy(updatingProfileId = profile.id) }
            }

            try {
                val contentChanged = ProfileRemoteRepository.update(profile).contentChanged

                // Reload profiles
                loadProfiles()

                // Show success state
                withContext(Dispatchers.Main) {
                    updateState { copy(updatingProfileId = null, updatedProfileId = profile.id) }
                }

                // Clear success state after delay
                withContext(Dispatchers.Main) {
                    delay(1500)
                    updateState { copy(updatedProfileId = null) }
                }

                // Restart service if this is the selected profile and content changed
                if (contentChanged && profile.id == Settings.selectedProfile) {
                    ProfileRuntime.reloadSelectedIfRunning(Application.application)
                }
            } catch (e: Exception) {
                sendErrorMessage("Failed to update profile: ${e.message}")
                // Clear updating state on error
                withContext(Dispatchers.Main) {
                    updateState { copy(updatingProfileId = null) }
                }
            }
        }
    }

    fun moveProfile(from: Int, to: Int) {
        val currentProfiles = currentState.profiles.toMutableList()

        if (from < to) {
            for (i in from until to) {
                Collections.swap(currentProfiles, i, i + 1)
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(currentProfiles, i, i - 1)
            }
        }

        // Update UI immediately
        updateState { copy(profiles = currentProfiles) }

        // Update user order in database
        viewModelScope.launch(Dispatchers.IO) {
            currentProfiles.forEachIndexed { index, profile ->
                profile.userOrder = index.toLong()
            }
            ProfileManager.update(currentProfiles)
        }
    }

    fun showAddProfileSheet() {
        updateState { copy(showAddProfileSheet = true) }
    }

    fun hideAddProfileSheet() {
        updateState { copy(showAddProfileSheet = false) }
    }

    fun showProfilePickerSheet() {
        updateState { copy(showProfilePickerSheet = true) }
    }

    fun hideProfilePickerSheet() {
        updateState { copy(showProfilePickerSheet = false) }
    }

    fun updateServiceStatus(status: Status) {
        _serviceStatus.value = status
        updateState {
            copy(
                serviceStatus = status,
                isStatusVisible =
                if (RemoteControlManager.remoteServer.value != null) {
                    isStatusVisible
                } else {
                    status == Status.Starting || status == Status.Started
                },
            )
        }
        handleServiceStatusChange(status)
    }

    private fun handleServiceStatusChange(status: Status) {
        val isRemote = RemoteControlManager.remoteServer.value != null
        when (status) {
            Status.Started -> {
                if (isRemote) {
                    return
                }
                val firstRunningUpdate = localServiceStartTime == null
                if (firstRunningUpdate) {
                    localServiceStartTime = System.currentTimeMillis()
                    localUplinkTotal = 0L
                    localDownlinkTotal = 0L
                }
                updateState {
                    copy(
                        serviceStartTime = localServiceStartTime,
                        isStatusVisible = true,
                        trafficVisible = true,
                        memory = "",
                        goroutines = "",
                        clashModeVisible = true,
                        clashModes = LOCAL_CLASH_MODES,
                        selectedClashMode = persistedClashMode(),
                        networkMode = MihomoNetworkMode.fromStorage(Settings.mihomoNetworkMode),
                        systemProxyVisible = false,
                        systemProxyEnabled = false,
                        systemProxySwitching = false,
                    )
                }
                refreshLocalGroups()
                if (firstRunningUpdate) {
                    refreshIpInfo(force = true)
                }
            }

            Status.Stopped -> {
                if (isRemote) {
                    return
                }
                localModeSelectionSequence.incrementAndGet()
                ipRefreshSequence.incrementAndGet()
                localGroupsRefreshJob?.cancel()
                localGroupsRefreshJob = null
                localServiceStartTime = null
                localUplinkTotal = 0L
                localDownlinkTotal = 0L
                updateState {
                    copy(
                        hasGroups = false,
                        groupsCount = 0,
                        currentProxyGroup = null,
                        currentProxyName = null,
                        connectionsCount = 0,
                        serviceStartTime = null,
                        clashModeVisible = true,
                        clashModes = LOCAL_CLASH_MODES,
                        selectedClashMode = persistedClashMode(),
                        networkMode = MihomoNetworkMode.fromStorage(Settings.mihomoNetworkMode),
                        systemProxyVisible = false,
                        systemProxyEnabled = false,
                        systemProxySwitching = false,
                        trafficVisible = false,
                        memory = "",
                        goroutines = "",
                        connectionsIn = "0",
                        connectionsOut = "0",
                        uplink = "0 B/s",
                        downlink = "0 B/s",
                        uplinkTotal = "0 B",
                        downlinkTotal = "0 B",
                        uplinkHistory = List(30) { 0f },
                        downlinkHistory = List(30) { 0f },
                        publicIp = null,
                        publicIpLocation = null,
                        publicIpColo = null,
                        publicIpLoading = false,
                        publicIpError = null,
                    )
                }
            }

            Status.Starting, Status.Stopping -> {
                if (!isRemote) {
                    updateState {
                        copy(
                            clashModeVisible = true,
                            clashModes = LOCAL_CLASH_MODES,
                            selectedClashMode = persistedClashMode(),
                            networkMode = MihomoNetworkMode.fromStorage(Settings.mihomoNetworkMode),
                            systemProxyVisible = false,
                            systemProxyEnabled = false,
                            systemProxySwitching = false,
                        )
                    }
                }
            }
        }
    }

    fun refreshLocalGroups() {
        localGroupsRefreshJob?.cancel()
        localGroupsRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            val groups = runCatching { mihomoController.getProxyGroups() }.getOrNull() ?: return@launch
            val mode = runCatching { mihomoController.getMode() }.getOrNull()
                ?.trim()
                ?.lowercase()
                ?.takeIf { it in LOCAL_CLASH_MODES }
                ?: return@launch
            val selection = dashboardProxySelection(mode, groups)
            withContext(Dispatchers.Main) {
                if (
                    RemoteControlManager.remoteServer.value == null &&
                    mihomoController.runtimeState.value == MihomoRuntimeState.Running
                ) {
                    updateState {
                        copy(
                            hasGroups = groups.isNotEmpty(),
                            groupsCount = groups.size,
                            currentProxyGroup = selection?.group,
                            currentProxyName = selection?.node,
                            clashModeVisible = true,
                            clashModes = LOCAL_CLASH_MODES,
                            selectedClashMode = mode.lowercase(),
                        )
                    }
                }
            }
        }
    }

    private fun updateLocalTraffic(traffic: MihomoTraffic) {
        localUplinkTotal += traffic.up.coerceAtLeast(0L)
        localDownlinkTotal += traffic.down.coerceAtLeast(0L)
        updateState {
            copy(
                trafficVisible = true,
                uplink = "${formatBytes(traffic.up)}/s",
                downlink = "${formatBytes(traffic.down)}/s",
                uplinkTotal = formatBytes(localUplinkTotal),
                downlinkTotal = formatBytes(localDownlinkTotal),
                uplinkHistory = uplinkHistory.drop(1) + traffic.up.toFloat(),
                downlinkHistory = downlinkHistory.drop(1) + traffic.down.toFloat(),
            )
        }
    }

    fun toggleSystemProxy(enabled: Boolean) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id
        if (remoteServerId == null) {
            updateState {
                copy(
                    systemProxyVisible = false,
                    systemProxyEnabled = false,
                    systemProxySwitching = false,
                )
            }
            return
        }
        if (currentState.systemProxySwitching) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
                updateState { copy(systemProxySwitching = true) }
                Settings.systemProxyEnabled = enabled
                CommandTarget.standaloneClient().setSystemProxyEnabled(enabled)
                delay(1000L)
                withContext(Dispatchers.Main) {
                    if (RemoteControlManager.remoteServer.value?.id == remoteServerId) {
                        updateState {
                            copy(
                                systemProxyEnabled = enabled,
                                systemProxySwitching = false,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                sendError(e)
                updateState { copy(systemProxySwitching = false) }
            }
        }
    }

    fun selectClashMode(mode: String) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id
        if (remoteServerId == null) {
            val normalizedMode = mode.trim().lowercase()
            if (normalizedMode !in LOCAL_CLASH_MODES) return
            if (currentState.serviceStatus == Status.Starting || currentState.serviceStatus == Status.Stopping) return
            if (
                currentState.serviceStatus == Status.Stopped &&
                mihomoController.runtimeState.value == MihomoRuntimeState.Stopped
            ) {
                Settings.mihomoClashMode = normalizedMode
                updateState { copy(selectedClashMode = normalizedMode) }
                return
            }
            if (mihomoController.runtimeState.value != MihomoRuntimeState.Running) return
            val selectionToken = localModeSelectionSequence.incrementAndGet()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    localModeSelectionMutex.withLock {
                        if (
                            selectionToken != localModeSelectionSequence.get() ||
                            RemoteControlManager.remoteServer.value != null ||
                            mihomoController.runtimeState.value != MihomoRuntimeState.Running
                        ) {
                            return@withLock
                        }
                        mihomoController.setMode(normalizedMode)
                        Settings.mihomoClashMode = normalizedMode
                        // Existing keep-alive connections retain their old route.
                        // Closing them makes the newly selected mode observable immediately.
                        runCatching { mihomoController.closeAllConnections() }
                        if (selectionToken != localModeSelectionSequence.get()) return@withLock
                        withContext(Dispatchers.Main) {
                            updateState { copy(selectedClashMode = normalizedMode) }
                            refreshLocalGroups()
                            refreshIpInfo(force = true)
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    sendError(exception)
                }
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
                CommandTarget.standaloneClient().setClashMode(mode)
                // Update UI state directly without reconnecting
                withContext(Dispatchers.Main) {
                    if (RemoteControlManager.remoteServer.value?.id == remoteServerId) {
                        updateState {
                            copy(selectedClashMode = mode)
                        }
                    }
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun selectNetworkMode(mode: MihomoNetworkMode) {
        if (RemoteControlManager.remoteServer.value != null || currentState.serviceStatus != Status.Stopped) return
        if (mode == MihomoNetworkMode.SystemProxy && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sendErrorMessage(Application.application.getString(R.string.network_mode_system_proxy_unavailable))
            return
        }
        Settings.mihomoNetworkMode = mode.storageValue
        updateState { copy(networkMode = mode) }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
            updateState { copy(isStatusVisible = true) }
            checkDeprecatedNotes(remoteServerId)
        }
    }

    override fun onDisconnected() {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
            updateState {
                copy(
                    memory = "",
                    goroutines = "",
                    isStatusVisible = false,
                )
            }
        }
    }

    override fun updateStatus(status: StatusMessage) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
            updateState {
                // Update history by adding new values and removing old ones
                val newUplinkHistory = (uplinkHistory.drop(1) + status.uplink.toFloat())
                val newDownlinkHistory = (downlinkHistory.drop(1) + status.downlink.toFloat())

                // Format the total values
                val newUplinkTotal = formatBytes(status.uplinkTotal)
                val newDownlinkTotal = formatBytes(status.downlinkTotal)

                copy(
                    memory = formatBytes(status.memory),
                    goroutines = status.goroutines.toString(),
                    // Only set trafficVisible to true, never back to false from status updates
                    trafficVisible = if (status.trafficAvailable) true else trafficVisible,
                    connectionsCount = status.connectionsIn,
                    connectionsIn = status.connectionsIn.toString(),
                    connectionsOut = status.connectionsOut.toString(),
                    uplink = "${formatBytes(status.uplink)}/s",
                    downlink = "${formatBytes(status.downlink)}/s",
                    // Only update total values if they've actually changed
                    uplinkTotal = if (newUplinkTotal != uplinkTotal) newUplinkTotal else uplinkTotal,
                    downlinkTotal = if (newDownlinkTotal != downlinkTotal) newDownlinkTotal else downlinkTotal,
                    uplinkHistory = newUplinkHistory,
                    downlinkHistory = newDownlinkHistory,
                )
            }
        }
    }

    override fun initializeClashMode(modeList: List<String>, currentMode: String) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
            updateState {
                copy(
                    clashModeVisible = modeList.size > 1,
                    clashModes = modeList,
                    selectedClashMode = currentMode,
                )
            }
        }
    }

    override fun updateClashMode(newMode: String) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
            updateState {
                copy(selectedClashMode = newMode)
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        val remoteServerId = RemoteControlManager.remoteServer.value?.id ?: return
        viewModelScope.launch(Dispatchers.Main) {
            if (RemoteControlManager.remoteServer.value?.id != remoteServerId) return@launch
            val hasGroups = newGroups.isNotEmpty()
            val activeGroup =
                newGroups.firstOrNull { it.selectable && it.selected.isNotBlank() }
                    ?: newGroups.firstOrNull { it.selected.isNotBlank() }
            updateState {
                copy(
                    hasGroups = hasGroups,
                    groupsCount = newGroups.size,
                    currentProxyGroup = activeGroup?.tag,
                    currentProxyName = activeGroup?.selected,
                )
            }
        }
    }

    fun refreshIpInfo(force: Boolean = false) {
        if (currentState.publicIpLoading && !force) return
        if (!force && (currentState.publicIp != null || currentState.publicIpError != null)) return

        val refreshToken = ipRefreshSequence.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(publicIpLoading = true, publicIpError = null) }
            try {
                val proxyPort = MihomoVpnService.activeHttpProxyPort
                val trace =
                    HTTPClient().use { client ->
                        if (proxyPort > 0) {
                            client.getStringViaLocalHttpProxy(IP_TRACE_URL, proxyPort)
                        } else {
                            client.getStringViaActiveNetwork(IP_TRACE_URL)
                        }
                    }
                val values =
                    trace.lineSequence()
                        .mapNotNull { line ->
                            val separator = line.indexOf('=')
                            if (separator <= 0) {
                                null
                            } else {
                                line.substring(0, separator) to line.substring(separator + 1)
                            }
                        }.toMap()
                val ip = values["ip"]?.takeIf { it.isNotBlank() }
                    ?: error("Public IP response did not contain an address")
                withContext(Dispatchers.Main) {
                    if (refreshToken != ipRefreshSequence.get()) return@withContext
                    updateState {
                        copy(
                            publicIp = ip,
                            publicIpLocation = values["loc"]?.takeIf { it.isNotBlank() },
                            publicIpColo = values["colo"]?.takeIf { it.isNotBlank() },
                            publicIpLoading = false,
                            publicIpError = null,
                        )
                    }
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    if (refreshToken != ipRefreshSequence.get()) return@withContext
                    updateState {
                        copy(
                            publicIpLoading = false,
                            publicIpError = exception.message ?: "IP query failed",
                        )
                    }
                }
            }
        }
    }

    fun toggleCardSettingsDialog() {
        updateState {
            copy(showCardSettingsDialog = !showCardSettingsDialog)
        }
    }

    fun toggleCardVisibility(cardGroup: CardGroup) {
        if (cardGroup !in configurableDashboardCards) return
        updateState {
            val newVisibleCards =
                if (visibleCards.contains(cardGroup)) {
                    visibleCards - cardGroup
                } else {
                    visibleCards + cardGroup
                }
            // Save disabled items to settings
            saveDisabledItems(newVisibleCards)
            // Also save the current order if not already saved (indicates user has configured dashboard)
            if (Settings.dashboardItemOrder.isBlank()) {
                saveItemOrder(cardOrder)
            }
            copy(visibleCards = newVisibleCards)
        }
    }

    fun closeCardSettingsDialog() {
        updateState {
            copy(showCardSettingsDialog = false)
        }
    }

    fun reorderCards(newOrder: List<CardGroup>) {
        updateState {
            saveItemOrder(newOrder)
            copy(cardOrder = newOrder)
        }
    }

    fun resetCardOrder() {
        // Clear saved settings to restore defaults
        Settings.dashboardItemOrder = ""
        Settings.dashboardDisabledItems = emptySet()

        updateState {
            copy(
                cardOrder = getDefaultItemOrder(),
                visibleCards = configurableDashboardCards.toSet(),
            )
        }
    }

    // Helper functions for serialization
    private fun getDefaultItemOrder() = configurableDashboardCards

    private fun loadItemOrder(): List<CardGroup> {
        val savedOrder = Settings.dashboardItemOrder
        if (savedOrder.isBlank()) {
            return getDefaultItemOrder()
        }

        return try {
            val jsonArray = JSONArray(savedOrder)
            val order = mutableListOf<CardGroup>()

            for (i in 0 until jsonArray.length()) {
                val itemName = jsonArray.getString(i)
                stringToCardGroup(itemName)
                    ?.takeIf { it in configurableDashboardCards }
                    ?.takeIf { it !in order }
                    ?.let { order.add(it) }
            }

            // Add any new items that aren't in the saved order
            val savedItems = order.toSet()
            order.addAll(getDefaultItemOrder().filterNot(savedItems::contains))
            order
        } catch (e: JSONException) {
            getDefaultItemOrder()
        }
    }

    private fun saveItemOrder(order: List<CardGroup>) {
        val jsonArray = JSONArray()
        order.forEach { item ->
            jsonArray.put(cardGroupToString(item))
        }
        Settings.dashboardItemOrder = jsonArray.toString()
    }

    private fun loadDisabledItems(): Set<CardGroup> {
        val savedDisabled = Settings.dashboardDisabledItems
        val disabledItems =
            savedDisabled
                .filterNot { it in setOf("UploadTraffic", "DownloadTraffic", "Connections", "SystemProxy") }
                .mapNotNull { stringToCardGroup(it) }
                .filterTo(mutableSetOf()) { it in configurableDashboardCards }

        // The former three traffic tiles are now one module. Preserve it as
        // disabled only when the user had disabled all three legacy tiles.
        if (
            setOf("UploadTraffic", "DownloadTraffic", "Connections")
                .all(savedDisabled::contains)
        ) {
            disabledItems += CardGroup.TrafficStats
        }
        return disabledItems
    }

    private fun saveDisabledItems(visibleCards: Set<CardGroup>) {
        val allItems = configurableDashboardCards.toSet()
        val disabledItems = allItems - visibleCards
        Settings.dashboardDisabledItems = disabledItems.map { cardGroupToString(it) }.toSet()
    }

    private fun cardGroupToString(card: CardGroup): String = card.name

    private fun stringToCardGroup(name: String): CardGroup? = when (name) {
        "Profiles" -> CardGroup.Subscriptions
        "ClashMode" -> CardGroup.ProxyMode
        "UploadTraffic", "DownloadTraffic", "Connections" -> CardGroup.TrafficStats
        "SystemProxy" -> CardGroup.NetworkSettings
        "Debug" -> CardGroup.ClashInfo
        else ->
            try {
                CardGroup.valueOf(name)
            } catch (_: IllegalArgumentException) {
                null
            }
    }

    private fun persistedClashMode(): String = Settings.mihomoClashMode.trim().lowercase().takeIf { it in LOCAL_CLASH_MODES } ?: "rule"
}
