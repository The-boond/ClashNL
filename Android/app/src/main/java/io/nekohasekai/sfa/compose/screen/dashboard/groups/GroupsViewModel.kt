package io.nekohasekai.sfa.compose.screen.dashboard.groups

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.model.toList
import io.nekohasekai.sfa.config.ProfileNodeSelection
import io.nekohasekai.sfa.constant.Status
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
import io.nekohasekai.sfa.latency.NetworkIdentityProvider
import io.nekohasekai.sfa.latency.NodeLatencyResult
import io.nekohasekai.sfa.latency.OfflineLatencyProbe
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.ProfileConfigStore
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
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
    private val liveSequence = AtomicLong(0)
    private val _liveGroupUpdates = MutableSharedFlow<LiveGroupUpdate>(replay = 1, extraBufferCapacity = 1)
    val liveGroupUpdates = _liveGroupUpdates
    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped

    private val profileCallback: () -> Unit = {
        if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value != Status.Started) {
            viewModelScope.launch { refreshOfflineGroups() }
        }
    }

    init {
        if (sharedCommandClient != null) {
            commandClient = sharedCommandClient
            isUsingSharedClient = true
            commandClient.addHandler(this)
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
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    if (isUsingSharedClient) {
                        commandClient.addHandler(this@GroupsViewModel)
                    } else {
                        updateState { copy(isLoading = true) }
                        commandClient.connect()
                    }
                } else {
                    if (isUsingSharedClient) {
                        commandClient.removeHandler(this@GroupsViewModel)
                    } else {
                        commandClient.disconnect()
                    }
                    if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value != Status.Started) {
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

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    override fun createInitialState() = GroupsUiState()

    override fun onCleared() {
        latencyJobs.values.toList().forEach { it.cancel() }
        latencyJobs.clear()
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
        viewModelScope.launch {
            _serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    fun refreshOfflineGroups() {
        if (RemoteControlManager.remoteServer.value != null || _serviceStatus.value == Status.Started) return
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = Settings.selectedProfile
            val groups = if (profileId == -1L) {
                emptyList()
            } else {
                loadOfflineGroups(profileId)
            }
            withContext(Dispatchers.Main) {
                updateState {
                    copy(
                        groups = overlayGroups(groups, currentNetworkKey()),
                        isLoading = false,
                    )
                }
            }
        }
    }

    private suspend fun loadOfflineGroups(profileId: Long): List<Group> {
        val profile = ProfileManager.get(profileId)
        val profileFile = profile?.typed?.path?.let(::File)
        if (profileFile == null || !profileFile.isFile) {
            LatencyRepository.clearProfile(profileId)
            return emptyList()
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

    fun toggleGroupExpand(groupTag: String) {
        val newExpanded = !uiState.value.expandedGroups.contains(groupTag)
        updateState {
            val newExpandedGroups = if (newExpanded) expandedGroups + groupTag else expandedGroups - groupTag
            copy(expandedGroups = newExpandedGroups)
        }
        if (_serviceStatus.value != Status.Started && RemoteControlManager.remoteServer.value == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { CommandTarget.standaloneClient().setGroupExpand(groupTag, newExpanded) }
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localStopped =
                    _serviceStatus.value != Status.Started && RemoteControlManager.remoteServer.value == null
                if (!localStopped) {
                    val client = CommandTarget.standaloneClient()
                    client.selectOutbound(groupTag, itemTag)
                    runCatching { client.closeConnections() }
                }
                persistLocalSelection(groupTag, itemTag)
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            groups = groups.map { group ->
                                if (group.tag == groupTag) group.copy(selected = itemTag) else group
                            },
                        )
                    }
                    sendEvent(GroupsEvent.GroupSelected(groupTag, itemTag))
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    private suspend fun persistLocalSelection(groupTag: String, itemTag: String) {
        if (RemoteControlManager.remoteServer.value != null) return
        val profileId = Settings.selectedProfile
        if (profileId == -1L) return
        val profile = ProfileManager.get(profileId) ?: return
        val profileFile = File(profile.typed.path)
        if (!profileFile.isFile) return
        runCatching {
            val updated = ProfileNodeSelection.select(profileFile.readText(), groupTag, itemTag)
            ProfileConfigStore.writeIfChanged(profileFile, updated)
        }
    }

    fun urlTest(groupTag: String) {
        val running = latencyJobs[groupTag]
        if (running != null) {
            running.cancel()
            return
        }

        val profileId = Settings.selectedProfile
        if (profileId == -1L) return
        val group = uiState.value.groups.firstOrNull { it.tag == groupTag } ?: return
        val source = if (_serviceStatus.value == Status.Started || RemoteControlManager.remoteServer.value != null) {
            io.nekohasekai.sfa.latency.LatencyResultSource.LIVE_CORE
        } else {
            io.nekohasekai.sfa.latency.LatencyResultSource.OFFLINE_PROBE
        }
        val networkKey = currentNetworkKey()
        LatencyRepository.markNetworkChanged(networkKey)
        val targets = group.items.map { item ->
            LatencyTarget(profileId, group.tag, item.tag, networkKey, source)
        }
        if (targets.isEmpty()) return

        updateState { copy(testingGroups = testingGroups + groupTag) }
        val probeFactory = when (source) {
            io.nekohasekai.sfa.latency.LatencyResultSource.LIVE_CORE -> LatencyProbeFactory {
                LiveCoreLatencyProbe(
                    updates = liveGroupUpdates,
                    currentSequence = liveSequence::get,
                )
            }
            io.nekohasekai.sfa.latency.LatencyResultSource.OFFLINE_PROBE -> LatencyProbeFactory {
                val profile = ProfileManager.get(profileId) ?: error("profile not found")
                val content = File(profile.typed.path).readText()
                OfflineLatencyProbe(content).also { it.start() }
            }
        }
        val job = latencyCoordinator.start(viewModelScope, targets, probeFactory) { result ->
            viewModelScope.launch(Dispatchers.Main) {
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
            latencyJobs.remove(groupTag, job)
            viewModelScope.launch(Dispatchers.Main) {
                updateState {
                    copy(
                        testingGroups = testingGroups - groupTag,
                        testingNodes = testingNodes.filterNot { it.groupTag == groupTag }.toSet(),
                    )
                }
            }
        }
    }

    private fun cancelLatencyTests() {
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
            if (_serviceStatus.value == Status.Started || RemoteControlManager.remoteServer.value != null) {
                updateState { copy(groups = emptyList(), isLoading = false, testingGroups = emptySet(), testingNodes = emptySet()) }
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
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
                    val key = LatencyKey(profileId, group.tag, item.tag, networkKey)
                    val existing = LatencyRepository.get(key)
                    if (existing == null || existing.testedAt != testedAt || existing.medianMs != item.urlTestDelay.toLong()) {
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
