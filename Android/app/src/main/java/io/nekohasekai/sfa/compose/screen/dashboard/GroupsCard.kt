package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compat.LazyColumnCompat
import io.nekohasekai.sfa.compat.rememberOverscrollEffectCompat
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsViewModel
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.rememberSheetDismissFromContentOnlyIfGestureStartedAtTopModifier
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.latency.LatencyResultStatus
import io.nekohasekai.sfa.latency.NodeLatencyResult
import io.nekohasekai.sfa.utils.CommandClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsCard(
    serviceStatus: Status,
    commandClient: CommandClient? = null,
    viewModel: GroupsViewModel? = null,
    showTopBar: Boolean = false,
    showStartFab: Boolean = false,
    showStatusBar: Boolean = false,
    listHeaderContent: (@Composable () -> Unit)? = null,
    asSheet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val actualViewModel: GroupsViewModel = viewModel ?: viewModel(
        factory =
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return GroupsViewModel(commandClient) as T
            }
        },
    )
    val uiState by actualViewModel.uiState.collectAsState()

    if (showTopBar) {
        val allCollapsed = uiState.expandedGroups.isEmpty()
        OverrideTopBar {
            TopAppBar(
                title = { Text(stringResource(R.string.title_groups)) },
                actions = {
                    if (uiState.groups.isNotEmpty()) {
                        IconButton(onClick = { actualViewModel.toggleAllGroups() }) {
                            Icon(
                                imageVector =
                                if (allCollapsed) {
                                    Icons.Default.UnfoldMore
                                } else {
                                    Icons.Default.UnfoldLess
                                },
                                contentDescription =
                                if (allCollapsed) {
                                    stringResource(R.string.expand_all)
                                } else {
                                    stringResource(R.string.collapse_all)
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    // Stable callbacks to prevent recomposition - use remember with viewModel as key
    val onToggleExpanded =
        remember(actualViewModel) {
            { groupTag: String -> actualViewModel.toggleGroupExpand(groupTag) }
        }
    val onItemSelected =
        remember(actualViewModel) {
            { groupTag: String, itemTag: String -> actualViewModel.selectGroupItem(groupTag, itemTag) }
        }
    val onUrlTest =
        remember(actualViewModel) {
            { groupTag: String -> actualViewModel.urlTest(groupTag) }
        }

    // Only update service status when it actually changes
    LaunchedEffect(serviceStatus) {
        actualViewModel.updateServiceStatus(serviceStatus)
    }

    GroupsCardContent(
        uiState = uiState,
        serviceStatus = serviceStatus,
        bottomPadding =
        when {
            showStartFab -> 88.dp
            showStatusBar -> 74.dp
            else -> 16.dp
        },
        onToggleExpanded = onToggleExpanded,
        onItemSelected = onItemSelected,
        onUrlTest = onUrlTest,
        listHeaderContent = listHeaderContent,
        asSheet = asSheet,
        modifier = modifier,
    )
}

/**
 * Compact node chooser embedded in the selected subscription card.
 *
 * It intentionally exposes only the primary selectable group so the everyday
 * flow stays profile -> node, while the legacy full groups route remains
 * available for diagnostics and deep links.
 */
@Composable
fun SubscriptionNodesPanel(
    serviceStatus: Status,
    profile: Profile? = null,
    commandClient: CommandClient? = null,
    viewModel: GroupsViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val actualViewModel: GroupsViewModel = viewModel ?: viewModel(
        factory =
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return GroupsViewModel(commandClient) as T
            }
        },
    )
    val uiState by actualViewModel.uiState.collectAsState()

    LaunchedEffect(serviceStatus, profile?.id) {
        actualViewModel.updateServiceStatus(serviceStatus)
        profile?.id?.let(actualViewModel::refreshSelectedProfile)
    }

    val availableGroups = uiState.groups
    val primaryGroup = availableGroups.firstOrNull { it.selectable } ?: availableGroups.firstOrNull()
    val isTesting = primaryGroup?.let { uiState.testingGroups.contains(it.tag) } == true
    val isLoading = uiState.isLoading

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.title_groups),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                    stringResource(
                        R.string.current_node,
                        primaryGroup?.selected?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.auto),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (primaryGroup?.selectable == true) {
                TextButton(
                    onClick = { actualViewModel.urlTest(primaryGroup.tag) },
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        stringResource(
                            if (isTesting) {
                                R.string.latency_test_cancel
                            } else {
                                R.string.latency_test_all
                            },
                        ),
                    )
                }
            }
        }

        when {
            isLoading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            primaryGroup == null -> {
                Text(
                    text = stringResource(R.string.no_nodes_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            else -> {
                ProxyItemsList(
                    items = primaryGroup.items,
                    selectedTag = primaryGroup.selected,
                    isSelectable = primaryGroup.selectable,
                    onItemSelected = { itemTag -> actualViewModel.selectGroupItem(primaryGroup.tag, itemTag) },
                )
            }
        }
    }
}

@Composable
private fun GroupsCardContent(
    uiState: io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsUiState,
    serviceStatus: Status,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onToggleExpanded: (String) -> Unit,
    onItemSelected: (String, String) -> Unit,
    onUrlTest: (String) -> Unit,
    listHeaderContent: (@Composable () -> Unit)? = null,
    asSheet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scrollModifier =
        if (asSheet) {
            rememberSheetDismissFromContentOnlyIfGestureStartedAtTopModifier {
                lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
            }
        } else {
            Modifier.nestedScroll(rememberBounceBlockingNestedScrollConnection(lazyListState))
        }
    val overscrollEffect = if (asSheet) null else rememberOverscrollEffectCompat()

    LazyColumnCompat(
        modifier =
        modifier
            .fillMaxSize()
            .then(scrollModifier),
        state = lazyListState,
        contentPadding =
        PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        overscrollEffect = overscrollEffect,
    ) {
        if (listHeaderContent != null) {
            item(key = "groups_list_header") {
                listHeaderContent()
            }
        }

        when {
            uiState.isLoading -> {
                item(key = "groups_loading") {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            uiState.groups.isEmpty() -> {
                item(key = "groups_empty") {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text =
                            if (serviceStatus == Status.Stopped) {
                                stringResource(R.string.nodes_start_vpn_hint)
                            } else {
                                stringResource(R.string.no_nodes_available)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                items(
                    items = uiState.groups,
                    key = { it.tag },
                    contentType = { "GroupCard" },
                ) { group ->
                    ProxyGroupItem(
                        group = group,
                        isExpanded = uiState.expandedGroups.contains(group.tag),
                        isTesting = uiState.testingGroups.contains(group.tag),
                        onToggleExpanded = { onToggleExpanded(group.tag) },
                        onItemSelected = { itemTag -> onItemSelected(group.tag, itemTag) },
                        onUrlTest = { onUrlTest(group.tag) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyGroupItem(
    group: Group,
    isExpanded: Boolean,
    isTesting: Boolean,
    onToggleExpanded: () -> Unit,
    onItemSelected: (String) -> Unit,
    onUrlTest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onToggleExpanded,
                    color = Color.Transparent,
                    modifier = Modifier.weight(1f),
                ) {
                    ListItem(
                        headlineContent = {
                            Column {
                                Text(
                                    text = group.tag,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )

                                if (group.selected.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.current_node, group.selected),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            val rotationAngle by animateFloatAsState(
                                targetValue = if (isExpanded) 180f else 0f,
                                animationSpec = tween(300),
                                label = "ExpandIcon",
                            )

                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                modifier =
                                Modifier
                                    .size(24.dp)
                                    .graphicsLayer { rotationZ = rotationAngle },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors =
                        ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )
                }

                if (group.selectable) {
                    TextButton(
                        onClick = onUrlTest,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text =
                            stringResource(
                                if (isTesting) {
                                    R.string.latency_test_cancel
                                } else {
                                    R.string.latency_test_all
                                },
                            ),
                        )
                    }
                }
            }

            // Expandable content
            AnimatedVisibility(
                visible = isExpanded && group.items.isNotEmpty(),
                enter =
                expandVertically(animationSpec = tween(300)) +
                    fadeIn(
                        animationSpec =
                        tween(
                            300,
                        ),
                    ),
                exit =
                shrinkVertically(animationSpec = tween(300)) +
                    fadeOut(
                        animationSpec =
                        tween(
                            300,
                        ),
                    ),
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 1.dp,
                    )

                    // Proxy Items
                    ProxyItemsList(
                        items = group.items,
                        selectedTag = group.selected,
                        isSelectable = group.selectable,
                        onItemSelected = onItemSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyItemsList(items: List<GroupItem>, selectedTag: String, isSelectable: Boolean, onItemSelected: (String) -> Unit) {
    val itemsPerRow = 1
    val chunkedItems =
        remember(items) {
            items.chunked(itemsPerRow)
        }

    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        chunkedItems.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { item ->
                    key(item.tag) {
                        Box(
                            modifier = Modifier.weight(1f),
                        ) {
                            ProxyChip(
                                item = item,
                                isSelected = item.tag == selectedTag,
                                isSelectable = isSelectable,
                                onClick = { onItemSelected(item.tag) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                repeat(itemsPerRow - rowItems.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyChip(item: GroupItem, isSelected: Boolean, isSelectable: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Use simpler, faster animations
    val animatedElevation by animateFloatAsState(
        targetValue = if (isSelected) 6.dp.value else 1.dp.value,
        animationSpec = tween(150),
        label = "Elevation",
    )

    val surfaceModifier = modifier
    val surfaceShape = RoundedCornerShape(8.dp)
    val surfaceColor =
        when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    val surfaceBorder =
        androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color =
            when {
                isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            },
        )

    val content: @Composable () -> Unit = {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.tag,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color =
                if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ProxyLatencyBadge(
                    latency = item.latency,
                    isSelected = isSelected,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }

    if (isSelectable) {
        Surface(
            onClick = onClick,
            modifier = surfaceModifier,
            shape = surfaceShape,
            color = surfaceColor,
            tonalElevation = animatedElevation.dp,
            border = surfaceBorder,
            content = content,
        )
    } else {
        Surface(
            modifier = surfaceModifier,
            shape = surfaceShape,
            color = surfaceColor,
            tonalElevation = animatedElevation.dp,
            border = surfaceBorder,
            content = content,
        )
    }
}

@Composable
private fun ProxyLatencyBadge(
    latency: NodeLatencyResult?,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    // Direct color calculation without animation for better performance
    val colorScheme = MaterialTheme.colorScheme
    val delay = latency?.medianMs?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
    val latencyColor =
        remember(delay, isSelected) {
            when {
                delay < 100 -> {
                    // Excellent - green/tertiary
                    if (isSelected) {
                        colorScheme.tertiary
                    } else {
                        colorScheme.tertiary.copy(alpha = 0.9f)
                    }
                }

                delay < 300 -> {
                    // Good - primary
                    if (isSelected) {
                        colorScheme.primary
                    } else {
                        colorScheme.primary.copy(alpha = 0.9f)
                    }
                }

                delay < 500 -> {
                    // Fair - secondary/warning
                    if (isSelected) {
                        colorScheme.secondary
                    } else {
                        colorScheme.secondary.copy(alpha = 0.9f)
                    }
                }

                else -> {
                    // Poor - error
                    if (isSelected) {
                        colorScheme.error
                    } else {
                        colorScheme.error.copy(alpha = 0.9f)
                    }
                }
            }
        }

    val label = when (latency?.status) {
        LatencyResultStatus.SUCCESS, LatencyResultStatus.CACHED ->
            stringResource(R.string.latency_label) + ": ${latency.medianMs}ms"
        LatencyResultStatus.TESTING -> stringResource(R.string.latency_status_testing)
        LatencyResultStatus.TIMEOUT -> stringResource(R.string.latency_status_timeout)
        LatencyResultStatus.FAILED -> stringResource(R.string.latency_status_failed)
        LatencyResultStatus.CANCELLED -> stringResource(R.string.latency_status_cancelled)
        LatencyResultStatus.EXPIRED -> {
            val previous = latency.medianMs?.let { " ${it}ms" }.orEmpty()
            stringResource(R.string.latency_status_expired) + previous
        }
        else -> stringResource(R.string.latency_status_untested)
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = latencyColor,
        modifier = modifier,
    )
}

@Composable
private fun rememberBounceBlockingNestedScrollConnection(lazyListState: LazyListState): NestedScrollConnection = remember(lazyListState) {
    object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Only block upward scroll (y < 0) at bottom to prevent sheet expansion
            // Allow downward scroll (y > 0) at top to let sheet collapse
            return if (available.y < 0) available else Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // Only block upward fling (y < 0) to prevent sheet expansion
            // Allow downward fling (y > 0) to let sheet collapse
            return if (available.y < 0) available else Velocity.Zero
        }
    }
}
