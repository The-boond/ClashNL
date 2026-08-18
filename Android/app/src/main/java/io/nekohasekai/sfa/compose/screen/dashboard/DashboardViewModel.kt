package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.repository.ProfileRemoteRepository
import io.nekohasekai.sfa.runtime.ProfileRuntime
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.util.Collections
import java.util.Date

enum class CardGroup {
    Subscriptions,
    CurrentProxy,
    NetworkSettings,
    ProxyMode,
    TrafficStats,
    WebsiteTest,
    IPInfo,
    ClashInfo,
    SystemInfo,
}

val configurableDashboardCards =
    listOf(
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
    val clashModeVisible: Boolean = false,
    val clashModes: List<String> = emptyList(),
    val selectedClashMode: String = "",
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
            CardGroup.ProxyMode to CardWidth.Full,
            CardGroup.TrafficStats to CardWidth.Full,
        ),
    val showCardSettingsDialog: Boolean = false,
) {
    data class DeprecatedNote(val message: String, val migrationLink: String?)
}

// DashboardViewModel now only uses UiEvent for all events
// No need for DashboardEvent anymore as all events are handled globally

class DashboardViewModel :
    BaseViewModel<DashboardUiState, UiEvent>(),
    CommandClient.Handler {
    companion object {
        private const val IP_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
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

    override fun createInitialState(): DashboardUiState {
        val savedOrder = loadItemOrder()
        val disabledItems = loadDisabledItems()

        // Calculate visible items (all items minus disabled)
        val allItems = configurableDashboardCards.toSet()
        val visibleCards = allItems - disabledItems

        return DashboardUiState(
            cardOrder = savedOrder,
            visibleCards = visibleCards,
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
                _serviceStatus,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
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
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

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
                val selectedId = Settings.selectedProfile

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

    private fun checkDeprecatedNotes() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Check if deprecated warnings are disabled
                if (Settings.disableDeprecatedWarnings) {
                    return@launch
                }

                val notes = Libbox.newStandaloneCommandClient().deprecatedNotes
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

    fun toggleService() {
        when (currentState.serviceStatus) {
            Status.Starting, Status.Started -> stopService()
            Status.Stopped -> sendGlobalEvent(UiEvent.RequestStartService)
            else -> { /* Ignore while transitioning */ }
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
                val previousCore = ProfileRuntime.selectedCore()
                val coreChanged = previousCore != profile.typed.core
                val serviceWasRunning = _serviceStatus.value == Status.Started

                // Stop the currently active core before changing the selected
                // profile, so stopActive still resolves the old owner.
                if (serviceWasRunning && coreChanged) {
                    ProfileRuntime.stopActive(Application.application)
                    for (i in 0 until 50) {
                        if (_serviceStatus.value == Status.Stopped) break
                        delay(100L)
                    }
                }
                Settings.selectedProfile = profileId

                // Check if service is running
                if (serviceWasRunning) {
                    if (coreChanged) {
                        GlobalEventBus.emit(UiEvent.RequestReconnectService)
                        GlobalEventBus.emit(UiEvent.RequestStartService)
                    } else if (profile.typed.core == io.nekohasekai.sfa.database.ProfileCore.SingBox && Settings.rebuildServiceMode()) {
                        // Need full restart
                        ProfileRuntime.stopActive(Application.application)
                        for (i in 0 until 50) {
                            if (_serviceStatus.value == Status.Stopped) {
                                break
                            }
                            delay(100L)
                        }
                        GlobalEventBus.emit(UiEvent.RequestReconnectService)
                        GlobalEventBus.emit(UiEvent.RequestStartService)
                    } else {
                        // A same-core profile switch is a config reload.
                        ProfileRuntime.reloadSelectedIfRunning(Application.application)
                    }
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
            } catch (e: Exception) {
                // Reload profiles if deletion fails
                loadProfiles()
                sendError(e)
            }
        }
    }

    fun shareProfile(profile: Profile) {
        // Handled directly in ProfilesCard
    }

    fun shareProfileURL(profile: Profile) {
        // Handled directly in ProfilesCard
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
        viewModelScope.launch {
            _serviceStatus.emit(status)
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
    }

    private fun handleServiceStatusChange(status: Status) {
        val isRemote = RemoteControlManager.remoteServer.value != null
        when (status) {
            Status.Started -> {
                checkDeprecatedNotes()
                if (isRemote) {
                    return
                }
                reloadSystemProxyStatus()
                reloadStartedAt()
                refreshIpInfo(force = true)
            }

            Status.Stopped -> {
                if (isRemote) {
                    return
                }
                updateState {
                    copy(
                        hasGroups = false,
                        groupsCount = 0,
                        currentProxyGroup = null,
                        currentProxyName = null,
                        connectionsCount = 0,
                        serviceStartTime = null,
                        clashModeVisible = false,
                        systemProxyVisible = false,
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

            else -> {}
        }
    }

    private fun reloadStartedAt() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val startedAt = Libbox.newStandaloneCommandClient().startedAt
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(serviceStartTime = startedAt)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun reloadSystemProxyStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = Libbox.newStandaloneCommandClient().systemProxyStatus
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyVisible = status.available,
                            systemProxyEnabled = status.enabled,
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun toggleSystemProxy(enabled: Boolean) {
        if (currentState.systemProxySwitching) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateState { copy(systemProxySwitching = true) }
                Settings.systemProxyEnabled = enabled
                Libbox.newStandaloneCommandClient().setSystemProxyEnabled(enabled)
                delay(1000L)
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyEnabled = enabled,
                            systemProxySwitching = false,
                        )
                    }
                }
            } catch (e: Exception) {
                sendError(e)
                updateState { copy(systemProxySwitching = false) }
            }
        }
    }

    fun selectClashMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().setClashMode(mode)
                // Update UI state directly without reconnecting
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(selectedClashMode = mode)
                    }
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) {
            updateState { copy(isStatusVisible = true) }
            // Returning from remote control skipped the local reloads that
            // normally run when the service starts.
            if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value == Status.Started) {
                reloadSystemProxyStatus()
                reloadStartedAt()
            }
        }
    }

    override fun onDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
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
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                // Update history by adding new values and removing old ones
                val newUplinkHistory = (uplinkHistory.drop(1) + status.uplink.toFloat())
                val newDownlinkHistory = (downlinkHistory.drop(1) + status.downlink.toFloat())

                // Format the total values
                val newUplinkTotal = Libbox.formatBytes(status.uplinkTotal)
                val newDownlinkTotal = Libbox.formatBytes(status.downlinkTotal)

                copy(
                    memory = Libbox.formatBytes(status.memory),
                    goroutines = status.goroutines.toString(),
                    // Only set trafficVisible to true, never back to false from status updates
                    trafficVisible = if (status.trafficAvailable) true else trafficVisible,
                    connectionsCount = status.connectionsIn,
                    connectionsIn = status.connectionsIn.toString(),
                    connectionsOut = status.connectionsOut.toString(),
                    uplink = "${Libbox.formatBytes(status.uplink)}/s",
                    downlink = "${Libbox.formatBytes(status.downlink)}/s",
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
        viewModelScope.launch(Dispatchers.Main) {
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
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(selectedClashMode = newMode)
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        viewModelScope.launch(Dispatchers.Main) {
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
        if (currentState.publicIpLoading) return
        if (!force && (currentState.publicIp != null || currentState.publicIpError != null)) return

        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(publicIpLoading = true, publicIpError = null) }
            try {
                val trace = HTTPClient().use { it.getString(IP_TRACE_URL) }
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
                .filterNot { it in setOf("UploadTraffic", "DownloadTraffic", "Connections") }
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
}
