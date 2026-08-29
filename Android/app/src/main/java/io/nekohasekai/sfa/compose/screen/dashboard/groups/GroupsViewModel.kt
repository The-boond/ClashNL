package io.nekohasekai.sfa.compose.screen.dashboard.groups

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.model.toGroup
import io.nekohasekai.sfa.compose.model.toList
import io.nekohasekai.sfa.config.ProfileNodeSelection
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.latency.LatencyKey
import io.nekohasekai.sfa.latency.LatencyProbeFactory
import io.nekohasekai.sfa.latency.LatencyRepository
import io.nekohasekai.sfa.latency.LatencyResultSource
import io.nekohasekai.sfa.latency.LatencyResultStatus
import io.nekohasekai.sfa.latency.LatencyTarget
import io.nekohasekai.sfa.latency.LatencyTestCoordinator
import io.nekohasekai.sfa.latency.LatencyTestMethod
import io.nekohasekai.sfa.latency.LiveCoreLatencyProbe
import io.nekohasekai.sfa.latency.LiveGroupUpdate
import io.nekohasekai.sfa.latency.MihomoLatencyProbe
import io.nekohasekai.sfa.latency.MihomoOfflineLatencyProbe
import io.nekohasekai.sfa.latency.NetworkIdentityProvider
import io.nekohasekai.sfa.latency.NodeLatencyResult
import io.nekohasekai.sfa.mihomo.MihomoConfig
import io.nekohasekai.sfa.mihomo.MihomoNativeBridge
import io.nekohasekai.sfa.mihomo.MihomoOfflineSelectionStore
import io.nekohasekai.sfa.mihomo.MihomoRuntimeRepository
import io.nekohasekai.sfa.mihomo.MihomoRuntimeState
import io.nekohasekai.sfa.repository.MihomoProxyGroupRepository
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.ProfileConfigStore
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
    val profileId: Long = -1L,
    val proxyMode: String = "rule",
    val isLoading: Boolean = false,
    val expandedGroups: Set<String> = emptySet(),
    val testingGroups: Set<String> = emptySet(),
    val testingNodes: Set<LatencyKey> = emptySet(),
)

sealed class GroupsEvent : ScreenEvent {
    data class GroupSelected(val groupTag: String, val itemTag: String) : GroupsEvent()
}

class GroupsViewModel(private val sharedCommandClient: CommandClient? = null) :
    BaseViewModel<GroupsUiState, GroupsEvent>(),
    CommandClient.Handler {
    private val commandClient: CommandClient
    private val isUsingSharedClient: Boolean
    private val latencyCoordinator = LatencyTestCoordinator()
    private val latencyJobs = ConcurrentHashMap<String, Job>()
    private val latencyRunSequence = AtomicLong(0)
    private val latencyRunTokens = ConcurrentHashMap<String, Long>()
    private val selectionSequence = AtomicLong(0)
    private val selectionTokens = ConcurrentHashMap<String, Long>()
    private val selectionJobs = ConcurrentHashMap<String, Job>()
    private val liveSequence = AtomicLong(0)
    private val offlineRefreshSequence = AtomicLong(0)
    private val autoTestRequestedProfiles = mutableSetOf<Long>()
    private val _liveGroupUpdates = MutableSharedFlow<LiveGroupUpdate>(replay = 1, extraBufferCapacity = 1)
    val liveGroupUpdates = _liveGroupUpdates
    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped
    private val _profileRevision = MutableStateFlow(0L)

    @Volatile
    private var isUsingMihomo = false

    private val profileCallback: () -> Unit = {
        viewModelScope.launch {
            cancelLatencyTests()
            _profileRevision.emit(_profileRevision.value + 1)
            if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value == Status.Stopped) {
                refreshOfflineGroups()
            }
        }
    }

    init {
        if (sharedCommandClient != null) {
            commandClient = sharedCommandClient
            isUsingSharedClient = true
        } else {
            commandClient =
                CommandClient(
                    viewModelScope,
                    CommandClient.ConnectionType.Groups,
                    this,
                )
            isUsingSharedClient = false
        }

        ProfileManager.registerCallback(profileCallback)
        NetworkIdentityProvider.start(this)

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
                _profileRevision,
            ) { foreground, remoteServer, remoteConnected, status, _ ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                    profileId = Settings.selectedProfile,
                )
            }.distinctUntilChanged().collect { target ->
                val useMihomo = target.connect && withContext(Dispatchers.IO) {
                    selectedMihomoProfileId() != null
                }
                if (useMihomo) {
                    isUsingMihomo = true
                    if (isUsingSharedClient) {
                        commandClient.removeHandler(this@GroupsViewModel)
                    } else {
                        commandClient.disconnect()
                    }
                    refreshMihomoGroups()
                } else if (target.connect) {
                    isUsingMihomo = false
                    if (isUsingSharedClient) {
                        commandClient.addHandler(this@GroupsViewModel)
                    } else {
                        updateState { copy(isLoading = true) }
                        commandClient.connect()
                    }
                } else {
                    isUsingMihomo = false
                    if (isUsingSharedClient) {
                        commandClient.removeHandler(this@GroupsViewModel)
                    } else {
                        commandClient.disconnect()
                    }
                    if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value == Status.Stopped) {
                        refreshOfflineGroups()
                    }
                }
            }
        }

        viewModelScope.launch {
            var lastNetworkKey: String? = null
            LatencyRepository.results.collect {
                val networkKey = currentNetworkKey()
                if (networkKey != lastNetworkKey) {
                    LatencyRepository.markNetworkChanged(networkKey)
                    lastNetworkKey = networkKey
                }
                updateState {
                    copy(groups = overlayGroups(groups, networkKey))
                }
            }
        }

        viewModelScope.launch {
            NetworkIdentityProvider.changes.collect { identity ->
                LatencyRepository.markNetworkChanged(identity.key)
                updateState { copy(groups = overlayGroups(groups, identity.key)) }
            }
        }

        viewModelScope.launch { refreshOfflineGroups() }
    }

    private data class SessionTarget(
        val connect: Boolean,
        val remoteServerId: Long?,
        val profileId: Long,
    )

    override fun createInitialState() = GroupsUiState()

    override fun onCleared() {
        latencyJobs.values.toList().forEach { it.cancel() }
        latencyJobs.clear()
        selectionJobs.values.toList().forEach { it.cancel() }
        selectionJobs.clear()
        ProfileManager.unregisterCallback(profileCallback)
        NetworkIdentityProvider.stop(this)
        super.onCleared()
        if (isUsingSharedClient) {
            commandClient.removeHandler(this)
        } else {
            commandClient.disconnect()
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) return
        if (status != Status.Started) {
            cancelLatencyTests()
        }
        if (status == Status.Stopped) {
            viewModelScope.launch { refreshOfflineGroups() }
        } else if (status == Status.Starting || status == Status.Stopping) {
            updateState { copy(testingGroups = emptySet(), testingNodes = emptySet()) }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) return
        lastServiceStatus = status
        if (status != Status.Stopped) {
            offlineRefreshSequence.incrementAndGet()
        }
        viewModelScope.launch {
            _serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    fun refreshOfflineGroups() {
        val refreshToken = offlineRefreshSequence.incrementAndGet()
        if (RemoteControlManager.remoteServer.value != null || _serviceStatus.value != Status.Stopped) return
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = Settings.selectedProfile
            val groups = if (profileId == -1L) {
                emptyList()
            } else {
                loadOfflineGroups(profileId)
            }
            withContext(Dispatchers.Main) {
                if (
                    offlineRefreshSequence.get() != refreshToken ||
                    Settings.selectedProfile != profileId ||
                    RemoteControlManager.remoteServer.value != null ||
                    _serviceStatus.value != Status.Stopped
                ) {
                    return@withContext
                }
                updateState {
                    copy(
                        groups = overlayGroups(groups, currentNetworkKey()),
                        profileId = profileId,
                        proxyMode = Settings.mihomoClashMode,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun refreshSelectedProfile(profileId: Long) {
        if (Settings.selectedProfile != profileId) return
        cancelLatencyTests()
        if (_serviceStatus.value == Status.Started && isUsingMihomo) {
            viewModelScope.launch { refreshMihomoGroups() }
        } else if (_serviceStatus.value == Status.Stopped) {
            refreshOfflineGroups()
        }
    }

    private suspend fun selectedMihomoProfileId(): Long? {
        if (RemoteControlManager.remoteServer.value != null || _serviceStatus.value != Status.Started) return null
        val profileId = Settings.selectedProfile
        if (profileId == -1L) return null
        return profileId.takeIf { ProfileManager.get(it)?.typed?.core == ProfileCore.Mihomo }
    }

    /**
     * Mihomo owns runtime group selections through its loopback controller.
     * This deliberately does not parse or rewrite the YAML profile.
     */
    private suspend fun refreshMihomoGroups() {
        val profileId = selectedMihomoProfileId() ?: return
        val controller = MihomoRuntimeRepository.controller(Application.application)
        if (controller.runtimeState.value != MihomoRuntimeState.Running) {
            withContext(Dispatchers.Main) {
                if (isUsingMihomo && Settings.selectedProfile == profileId) {
                    updateState { copy(groups = emptyList(), isLoading = false) }
                }
            }
            return
        }

        val currentByTag = uiState.value.groups.associateBy { it.tag }
        val mode = runCatching { controller.getMode() }
            .getOrDefault(Settings.mihomoClashMode)
            .trim()
            .lowercase()
        val groups = try {
            MihomoProxyGroupRepository(controller)
                .getGroups()
                .map { it.toGroup(currentByTag[it.name]) }
                .visibleForMode(mode)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (isUsingMihomo && Settings.selectedProfile == profileId) {
                    updateState { copy(isLoading = false) }
                    sendError(e)
                }
            }
            return
        }
        if (selectedMihomoProfileId() != profileId) return
        LatencyRepository.prune(
            profileId,
            groups.associate { group -> group.tag to group.items.map { it.tag }.toSet() },
        )
        withContext(Dispatchers.Main) {
            if (!isUsingMihomo || Settings.selectedProfile != profileId) return@withContext
            updateState {
                copy(
                    groups = overlayGroups(groups, currentNetworkKey()),
                    profileId = profileId,
                    proxyMode = mode,
                    expandedGroups = expandedGroups.intersect(groups.map { it.tag }.toSet()),
                    isLoading = false,
                )
            }
        }
    }

    private fun mihomoRepository(): MihomoProxyGroupRepository = MihomoProxyGroupRepository(MihomoRuntimeRepository.controller(Application.application))

    private suspend fun loadOfflineGroups(profileId: Long): List<Group> {
        val profile = ProfileManager.get(profileId)
        val profileFile = profile?.typed?.path?.let(::File)
        if (profileFile == null || !profileFile.isFile) {
            LatencyRepository.clearProfile(profileId)
            return emptyList()
        }
        if (profile.typed.core == ProfileCore.Mihomo) {
            return loadOfflineMihomoGroups(profile, profileFile)
        }
        val groups = runCatching {
            ProfileNodeSelection.read(profileFile.readText()).map { selector ->
                Group(
                    tag = selector.tag,
                    type = "selector",
                    selectable = true,
                    selected = selector.selected,
                    isExpand = true,
                    items = selector.items.map { item ->
                        GroupItem(
                            tag = item.tag,
                            type = item.type,
                            urlTestTime = 0,
                            urlTestDelay = 0,
                        )
                    },
                )
            }
        }.getOrElse { emptyList() }
        LatencyRepository.prune(
            profileId,
            groups.associate { group -> group.tag to group.items.map { it.tag }.toSet() },
        )
        return groups
    }

    private fun loadOfflineMihomoGroups(profile: io.nekohasekai.sfa.database.Profile, profileFile: File): List<Group> {
        val pendingSelections = MihomoOfflineSelectionStore.selections(profile)
        val groups = runCatching {
            MihomoNativeBridge.initialize(Application.application)
            MihomoNativeBridge.describeProxyGroups(profileFile.readText()).map { group ->
                val selected = pendingSelections[group.name]?.takeIf { pending -> group.proxies.any { it.name == pending } }
                    ?: group.selected
                Group(
                    tag = group.name,
                    type = group.type,
                    selectable = group.selectable,
                    selected = selected,
                    isExpand = true,
                    items = group.proxies.map { proxy ->
                        GroupItem(
                            tag = proxy.name,
                            type = "",
                            urlTestTime = 0,
                            urlTestDelay = 0,
                        )
                    },
                )
            }
        }.getOrElse { emptyList() }
        LatencyRepository.prune(
            profile.id,
            groups.associate { group -> group.tag to group.items.map { it.tag }.toSet() },
        )
        return groups
    }

    fun toggleGroupExpand(groupTag: String) {
        val newExpanded = !uiState.value.expandedGroups.contains(groupTag)
        updateState {
            val newExpandedGroups = if (newExpanded) expandedGroups + groupTag else expandedGroups - groupTag
            copy(expandedGroups = newExpandedGroups)
        }
        if (isUsingMihomo) return
        if (_serviceStatus.value != Status.Started && RemoteControlManager.remoteServer.value == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { CommandTarget.standaloneClient().setGroupExpand(groupTag, newExpanded) }
        }
    }

    /**
     * Starts a full subscription latency pass once when its card is first opened.
     * Groups are tested sequentially because stopped-state Mihomo probes own an
     * exclusive core session.
     */
    fun onSubscriptionOpened(profileId: Long) {
        if (!autoTestRequestedProfiles.add(profileId)) return
        viewModelScope.launch {
            val readyState = withTimeoutOrNull(15_000L) {
                uiState.first { state ->
                    Settings.selectedProfile == profileId &&
                        state.profileId == profileId &&
                        !state.isLoading &&
                        state.groups.isNotEmpty()
                }
            }
            if (readyState == null) {
                autoTestRequestedProfiles.remove(profileId)
                return@launch
            }
            readyState.groups
                .filter { it.selectable && it.items.isNotEmpty() }
                .forEach { group -> startUrlTest(group.tag)?.join() }
        }
    }

    fun toggleAllGroups() {
        val groups = uiState.value.groups
        val allCollapsed = uiState.value.expandedGroups.isEmpty()
        updateState {
            if (allCollapsed) {
                copy(expandedGroups = groups.map { it.tag }.toSet())
            } else {
                copy(expandedGroups = emptySet())
            }
        }
        if (isUsingMihomo) return
        if (_serviceStatus.value != Status.Started && RemoteControlManager.remoteServer.value == null) return
        viewModelScope.launch(Dispatchers.IO) {
            groups.forEach { group ->
                runCatching { CommandTarget.standaloneClient().setGroupExpand(group.tag, allCollapsed) }
            }
        }
    }

    fun selectGroupItem(groupTag: String, itemTag: String) {
        val currentGroup = uiState.value.groups.find { it.tag == groupTag }
        if (currentGroup?.selected == itemTag) return
        val profileId = Settings.selectedProfile
        if (profileId == -1L) return
        val token = selectionSequence.incrementAndGet()
        selectionTokens[groupTag] = token
        val previousJob = selectionJobs.remove(groupTag)

        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                // Preserve click order even when an older HTTP/remote command is
                // already in flight. Intermediate stale clicks exit after joining.
                previousJob?.join()
                val profile = ProfileManager.get(profileId)
                if (selectionTokens[groupTag] != token || Settings.selectedProfile != profileId) return@launch
                val useMihomo = profile?.typed?.core == ProfileCore.Mihomo
                val isLocal = RemoteControlManager.remoteServer.value == null
                val localStopped = isLocal && _serviceStatus.value == Status.Stopped
                if (!isLocal) {
                    val client = CommandTarget.standaloneClient()
                    client.selectOutbound(groupTag, itemTag)
                    runCatching { client.closeConnections() }
                } else if (useMihomo) {
                    MihomoOfflineSelectionStore.select(profile, groupTag, itemTag)
                    val controller = MihomoRuntimeRepository.controller(Application.application)
                    val runtimeState = if (controller.runtimeState.value == MihomoRuntimeState.Starting) {
                        withTimeoutOrNull(10_000L) {
                            controller.runtimeState.first { it != MihomoRuntimeState.Starting }
                        } ?: controller.runtimeState.value
                    } else {
                        controller.runtimeState.value
                    }
                    if (runtimeState == MihomoRuntimeState.Running) {
                        controller.selectProxy(groupTag, itemTag)
                        runCatching { controller.closeAllConnections() }
                        if (selectionTokens[groupTag] != token || Settings.selectedProfile != profileId) return@launch
                        refreshMihomoGroups()
                    }
                } else if (!localStopped) {
                    val client = CommandTarget.standaloneClient()
                    client.selectOutbound(groupTag, itemTag)
                    runCatching { client.closeConnections() }
                }
                if (isLocal && !useMihomo) {
                    if (selectionTokens[groupTag] != token || Settings.selectedProfile != profileId) return@launch
                    persistLocalSelection(profileId, groupTag, itemTag)
                }
                withContext(Dispatchers.Main) {
                    if (selectionTokens[groupTag] != token || Settings.selectedProfile != profileId) return@withContext
                    updateState {
                        copy(
                            groups = groups.map { group ->
                                if (group.tag == groupTag) group.copy(selected = itemTag) else group
                            },
                        )
                    }
                    sendEvent(GroupsEvent.GroupSelected(groupTag, itemTag))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (e: Exception) {
                if (selectionTokens[groupTag] == token && Settings.selectedProfile == profileId) {
                    sendError(e)
                }
            }
        }
        selectionJobs[groupTag] = job
        job.invokeOnCompletion {
            selectionJobs.remove(groupTag, job)
            selectionTokens.remove(groupTag, token)
        }
        job.start()
    }

    private suspend fun persistLocalSelection(profileId: Long, groupTag: String, itemTag: String) {
        if (RemoteControlManager.remoteServer.value != null) return
        val profile = ProfileManager.get(profileId) ?: return
        val profileFile = File(profile.typed.path)
        if (!profileFile.isFile) return
        runCatching {
            val updated = ProfileNodeSelection.select(profileFile.readText(), groupTag, itemTag)
            ProfileConfigStore.writeIfChanged(profileFile, updated)
        }
    }

    fun urlTest(groupTag: String) {
        startUrlTest(groupTag)
    }

    private fun startUrlTest(groupTag: String): Job? {
        val running = latencyJobs[groupTag]
        if (running != null) {
            running.cancel()
            return null
        }

        val profileId = Settings.selectedProfile
        if (profileId == -1L) return null
        val group = uiState.value.groups.firstOrNull { it.tag == groupTag } ?: return null
        val source = when {
            RemoteControlManager.remoteServer.value != null || _serviceStatus.value == Status.Started -> LatencyResultSource.LIVE_CORE
            _serviceStatus.value == Status.Stopped -> LatencyResultSource.OFFLINE_PROBE
            else -> {
                sendErrorMessage("请等待 Mihomo VPN 完成状态切换")
                return null
            }
        }
        if (source == LatencyResultSource.OFFLINE_PROBE && latencyJobs.isNotEmpty()) {
            sendErrorMessage("请等待当前节点测速完成")
            return null
        }
        val networkKey = currentNetworkKey()
        LatencyRepository.markNetworkChanged(networkKey)
        val targets = group.items.map { item ->
            LatencyTarget(profileId, group.tag, item.tag, networkKey, source)
        }
        if (targets.isEmpty()) return null
        val runToken = latencyRunSequence.incrementAndGet()
        latencyRunTokens[groupTag] = runToken

        updateState { copy(testingGroups = testingGroups + groupTag) }
        val probeFactory = when (source) {
            LatencyResultSource.LIVE_CORE ->
                if (RemoteControlManager.remoteServer.value != null) {
                    LatencyProbeFactory {
                        LiveCoreLatencyProbe(
                            updates = _liveGroupUpdates,
                            currentSequence = { liveSequence.get() },
                        )
                    }
                } else {
                    LatencyProbeFactory { MihomoLatencyProbe(mihomoRepository()) }
                }
            LatencyResultSource.OFFLINE_PROBE -> LatencyProbeFactory {
                check(_serviceStatus.value == Status.Stopped) { "Mihomo VPN is no longer stopped" }
                check(RemoteControlManager.remoteServer.value == null) { "Remote control cannot use a local offline probe" }
                check(Settings.selectedProfile == profileId) { "Selected profile changed before latency testing started" }
                val profile = ProfileManager.get(profileId) ?: error("Selected profile no longer exists")
                check(profile.typed.core == ProfileCore.Mihomo) { "Selected profile is not a Mihomo profile" }
                val profileFile = File(profile.typed.path)
                check(profileFile.isFile) { "Selected Mihomo profile file is missing" }
                val controller = MihomoRuntimeRepository.controller(Application.application)
                MihomoOfflineLatencyProbe(controller.openProbe(MihomoConfig(profileFile.readText())))
            }
        }
        val job = latencyCoordinator.start(viewModelScope, targets, probeFactory) { result ->
            viewModelScope.launch(Dispatchers.Main) {
                if (latencyRunTokens[result.groupTag] != runToken || Settings.selectedProfile != result.profileId) return@launch
                updateState {
                    val updatedGroups = groups.map { existingGroup ->
                        if (existingGroup.tag != result.groupTag) return@map existingGroup
                        existingGroup.copy(
                            items = existingGroup.items.map { item ->
                                if (item.tag == result.nodeTag) item.copy(latency = result) else item
                            },
                        )
                    }
                    val testingNodes = if (result.status == LatencyResultStatus.TESTING) {
                        testingNodes + result.key
                    } else {
                        testingNodes - result.key
                    }
                    copy(groups = updatedGroups, testingNodes = testingNodes)
                }
            }
        }
        latencyJobs[groupTag] = job
        job.invokeOnCompletion {
            val ownsJob = latencyJobs.remove(groupTag, job)
            val ownsToken = latencyRunTokens.remove(groupTag, runToken)
            if (!ownsJob || !ownsToken) return@invokeOnCompletion
            viewModelScope.launch(Dispatchers.Main) {
                if (latencyRunTokens.containsKey(groupTag) || Settings.selectedProfile != profileId) return@launch
                updateState {
                    copy(
                        testingGroups = testingGroups - groupTag,
                        testingNodes = testingNodes.filterNot { it.groupTag == groupTag }.toSet(),
                    )
                }
            }
        }
        return job
    }

    private fun cancelLatencyTests() {
        latencyRunTokens.clear()
        latencyJobs.values.toList().forEach { it.cancel() }
        latencyJobs.clear()
        updateState { copy(testingGroups = emptySet(), testingNodes = emptySet()) }
    }

    private fun currentNetworkKey(): String = runCatching { NetworkIdentityProvider.current().key }.getOrDefault("unknown")

    private fun overlayGroups(groups: List<Group>, networkKey: String): List<Group> = groups.map { group ->
        group.copy(
            items = group.items.map { item ->
                item.copy(
                    latency = LatencyRepository.getForDisplay(
                        profileId = Settings.selectedProfile,
                        groupTag = group.tag,
                        nodeTag = item.tag,
                        currentNetworkKey = networkKey,
                    ),
                )
            },
        )
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) { updateState { copy(isLoading = true) } }
    }

    override fun onDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
            if (!isUsingMihomo && (_serviceStatus.value == Status.Started || RemoteControlManager.remoteServer.value != null)) {
                updateState { copy(groups = emptyList(), isLoading = false, testingGroups = emptySet(), testingNodes = emptySet()) }
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        if (isUsingMihomo) return
        viewModelScope.launch(Dispatchers.Default) {
            val currentGroups = uiState.value.groups
            val currentByTag = currentGroups.associateBy { it.tag }
            val mergedGroups = newGroups.map { newGroupData ->
                val existingGroup = currentByTag[newGroupData.tag]
                val existingItems = existingGroup?.items?.associateBy { it.tag }.orEmpty()
                val updatedItems = newGroupData.items.toList().map { newItemData ->
                    val existingItem = existingItems[newItemData.tag]
                    if (existingItem != null && existingItem.type == newItemData.type) {
                        existingItem.copy(
                            urlTestTime = newItemData.urlTestTime,
                            urlTestDelay = newItemData.urlTestDelay,
                        )
                    } else {
                        GroupItem(newItemData)
                    }
                }
                Group(
                    tag = newGroupData.tag,
                    type = newGroupData.type,
                    selectable = newGroupData.selectable,
                    selected = newGroupData.selected,
                    isExpand = newGroupData.isExpand,
                    items = updatedItems,
                )
            }
            val profileId = Settings.selectedProfile
            val networkKey = currentNetworkKey()
            mergedGroups.forEach { group ->
                group.items.forEach { item ->
                    if (profileId == -1L || item.urlTestTime <= 0 || item.urlTestDelay <= 0) return@forEach
                    val testedAt = if (item.urlTestTime < 100_000_000_000L) {
                        item.urlTestTime * 1_000L
                    } else {
                        item.urlTestTime
                    }
                    // Core history has no network identity. Once this app has a
                    // result for the node, retain that identity instead of
                    // re-labeling historical Core data as a fresh result after
                    // a network switch or service restart.
                    if (LatencyRepository.hasResultForNode(profileId, group.tag, item.tag)) {
                        return@forEach
                    }
                    LatencyRepository.put(
                        NodeLatencyResult(
                            profileId = profileId,
                            groupTag = group.tag,
                            nodeTag = item.tag,
                            method = LatencyTestMethod.PROXY_HTTP_HEAD,
                            samplesMs = listOf(item.urlTestDelay.toLong()),
                            medianMs = item.urlTestDelay.toLong(),
                            minMs = item.urlTestDelay.toLong(),
                            maxMs = item.urlTestDelay.toLong(),
                            firstConnectMs = item.urlTestDelay.toLong(),
                            failedSamples = 0,
                            testedAt = testedAt,
                            networkKey = networkKey,
                            source = LatencyResultSource.LIVE_CORE,
                            status = LatencyResultStatus.SUCCESS,
                        ),
                    )
                }
            }
            LatencyRepository.prune(
                Settings.selectedProfile,
                mergedGroups.associate { group -> group.tag to group.items.map { it.tag }.toSet() },
            )
            val sequence = liveSequence.incrementAndGet()
            _liveGroupUpdates.emit(LiveGroupUpdate(sequence, mergedGroups))
            withContext(Dispatchers.Main) {
                updateState {
                    val initialExpandedGroups = if (expandedGroups.isEmpty() && currentGroups.isEmpty()) {
                        mergedGroups.filter { it.selectable }.map { it.tag }.toSet()
                    } else {
                        expandedGroups
                    }
                    copy(
                        groups = overlayGroups(mergedGroups, currentNetworkKey()),
                        expandedGroups = initialExpandedGroups,
                        isLoading = false,
                    )
                }
            }
        }
    }
}

internal fun List<Group>.visibleForMode(mode: String): List<Group> {
    if (mode.equals("global", ignoreCase = true)) return this
    val hasUserSelectableGroup = any { it.selectable && !it.tag.equals("GLOBAL", ignoreCase = true) }
    return if (hasUserSelectableGroup) {
        filterNot { it.tag.equals("GLOBAL", ignoreCase = true) }
    } else {
        this
    }
}
