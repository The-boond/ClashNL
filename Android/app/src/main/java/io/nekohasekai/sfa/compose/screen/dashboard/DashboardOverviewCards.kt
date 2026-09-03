package io.nekohasekai.sfa.compose.screen.dashboard

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UnderlyingTransport
import io.nekohasekai.sfa.compose.LineChart
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.mihomo.MihomoNetworkMode
import io.nekohasekai.sfa.utils.CoreVersion
import io.nekohasekai.sfa.utils.formatBytes

@Composable
fun DashboardServiceCard(
    serviceStatus: Status,
    selectedProfileName: String?,
    currentProxyName: String?,
    serviceMode: String,
    publicIp: String?,
    publicIpCountryCode: String?,
    publicIpColo: String?,
    publicIpLoading: Boolean,
    publicIpError: String?,
    proxyPublicIpAvailable: Boolean,
    directPublicIp: String?,
    directPublicIpCountryCode: String?,
    directPublicIpColo: String?,
    directPublicIpError: String?,
    underlyingTransport: UnderlyingTransport?,
    underlyingInterfaceName: String?,
    underlyingInterfaceAddresses: List<String>,
    underlyingValidated: Boolean,
    onToggleService: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenNodePicker: () -> Unit,
    onRefreshIp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isStarted = serviceStatus == Status.Started
    val isTransitioning = serviceStatus == Status.Starting || serviceStatus == Status.Stopping
    val hasProfile = !selectedProfileName.isNullOrBlank()
    val accent = if (isStarted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val buttonColor =
        if (isStarted) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors =
        androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dashboard_service_control),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text =
                        if (hasProfile) {
                            stringResource(R.string.dashboard_service_control_hint)
                        } else {
                            stringResource(R.string.dashboard_add_profile_before_start)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = serviceStatusLabel(serviceStatus),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DashboardInfoPill(
                    icon = Icons.Outlined.Cloud,
                    label = stringResource(R.string.dashboard_selected_profile),
                    value = selectedProfileName ?: stringResource(R.string.dashboard_not_available),
                    onClick = onOpenSubscriptions,
                    modifier = Modifier.weight(1f),
                )
                DashboardInfoPill(
                    icon = Icons.Outlined.Route,
                    label = stringResource(R.string.dashboard_proxy_node),
                    value = currentProxyName ?: stringResource(R.string.dashboard_not_available),
                    onClick = onOpenNodePicker,
                    modifier = Modifier.weight(1f),
                )
            }

            if (isStarted) {
                NetworkDiagnosticsPanel(
                    underlyingTransport = underlyingTransport,
                    underlyingInterfaceName = underlyingInterfaceName,
                    underlyingInterfaceAddresses = underlyingInterfaceAddresses,
                    underlyingValidated = underlyingValidated,
                    directPublicIp = directPublicIp,
                    directPublicIpCountryCode = directPublicIpCountryCode,
                    directPublicIpColo = directPublicIpColo,
                    directPublicIpError = directPublicIpError,
                    proxyPublicIp = publicIp,
                    proxyPublicIpCountryCode = publicIpCountryCode,
                    proxyPublicIpColo = publicIpColo,
                    proxyPublicIpError = publicIpError,
                    proxyPublicIpAvailable = proxyPublicIpAvailable,
                    loading = publicIpLoading,
                    onRefresh = onRefreshIp,
                )
            }

            Button(
                onClick = {
                    if (hasProfile) {
                        onToggleService()
                    } else {
                        onOpenSubscriptions()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTransitioning,
                shape = RoundedCornerShape(14.dp),
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (hasProfile) buttonColor else MaterialTheme.colorScheme.primaryContainer,
                    contentColor =
                    if (!hasProfile) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else if (isStarted) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                ),
            ) {
                if (isTransitioning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(19.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dashboard_service_transitioning))
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text =
                        when {
                            !hasProfile -> stringResource(R.string.dashboard_open_subscriptions)
                            isStarted -> stringResource(R.string.dashboard_stop_service)
                            else -> stringResource(R.string.dashboard_start_service)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                text = stringResource(R.string.dashboard_service_mode_value, serviceMode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetworkDiagnosticsPanel(
    underlyingTransport: UnderlyingTransport?,
    underlyingInterfaceName: String?,
    underlyingInterfaceAddresses: List<String>,
    underlyingValidated: Boolean,
    directPublicIp: String?,
    directPublicIpCountryCode: String?,
    directPublicIpColo: String?,
    directPublicIpError: String?,
    proxyPublicIp: String?,
    proxyPublicIpCountryCode: String?,
    proxyPublicIpColo: String?,
    proxyPublicIpError: String?,
    proxyPublicIpAvailable: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dashboard_underlying_network),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val typeAndInterface =
                        listOfNotNull(
                            underlyingTransport?.let { underlyingTransportLabel(it) },
                            underlyingInterfaceName?.takeIf(String::isNotBlank),
                        ).joinToString(" · ")
                    Text(
                        text = typeAndInterface.ifBlank { stringResource(R.string.dashboard_not_available) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                        if (underlyingInterfaceAddresses.isEmpty()) {
                            stringResource(R.string.dashboard_interface_addresses_unavailable)
                        } else {
                            underlyingInterfaceAddresses.joinToString(" · ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (underlyingTransport != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        Text(
                            text = stringResource(R.string.dashboard_network_validation_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (underlyingTransport != null && !underlyingValidated) {
                        Text(
                            text = stringResource(R.string.dashboard_network_not_validated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.dashboard_refresh_ip),
                        )
                    }
                }
            }
            ExitIpRow(
                label = stringResource(R.string.dashboard_direct_public_ip),
                ip = directPublicIp,
                countryCode = directPublicIpCountryCode,
                colo = directPublicIpColo,
                loading = loading,
                available = underlyingTransport != null,
                error = directPublicIpError,
            )
            ExitIpRow(
                label = stringResource(R.string.dashboard_proxy_public_ip),
                ip = proxyPublicIp,
                countryCode = proxyPublicIpCountryCode,
                colo = proxyPublicIpColo,
                loading = loading,
                available = proxyPublicIpAvailable,
                error = proxyPublicIpError,
            )
        }
    }
}

@Composable
private fun ExitIpRow(
    label: String,
    ip: String?,
    countryCode: String?,
    colo: String?,
    loading: Boolean,
    available: Boolean,
    error: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = countryCodeToFlagEmoji(countryCode),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                when {
                    !available -> stringResource(R.string.dashboard_ip_unavailable)
                    loading -> stringResource(R.string.dashboard_ip_pending)
                    ip != null -> ip
                    error != null -> stringResource(R.string.dashboard_ip_query_failed)
                    else -> stringResource(R.string.dashboard_ip_not_checked)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val location = listOfNotNull(countryCode, colo).joinToString(" · ")
            if (location.isNotBlank()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun underlyingTransportLabel(transport: UnderlyingTransport): String = stringResource(
    when (transport) {
        UnderlyingTransport.WiFi -> R.string.dashboard_transport_wifi
        UnderlyingTransport.Cellular -> R.string.dashboard_transport_cellular
        UnderlyingTransport.Ethernet -> R.string.dashboard_transport_ethernet
        UnderlyingTransport.Usb -> R.string.dashboard_transport_usb
        UnderlyingTransport.Bluetooth -> R.string.dashboard_transport_bluetooth
        UnderlyingTransport.Satellite -> R.string.dashboard_transport_satellite
        UnderlyingTransport.Other -> R.string.dashboard_transport_other
    },
)

internal fun countryCodeToFlagEmoji(countryCode: String?): String {
    val normalized = countryCode?.trim()?.uppercase().orEmpty()
    if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) {
        return "🌐"
    }
    return normalized
        .map { character ->
            String(Character.toChars(0x1F1E6 + character.code - 'A'.code))
        }.joinToString("")
}

@Composable
fun SubscriptionSummaryCard(
    profiles: List<Profile>,
    selectedProfileId: Long,
    selectedProfileName: String?,
    onOpenSubscriptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val subscriptionCount = profiles.count { it.typed.type == TypedProfile.Type.Remote }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val selectedRemote = selectedProfile?.takeIf { it.typed.type == TypedProfile.Type.Remote }?.typed
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_subscriptions_card),
        icon = Icons.Outlined.Cloud,
        modifier = modifier,
    ) {
        DashboardMetric(
            label = stringResource(R.string.dashboard_selected_profile),
            value = selectedProfileName ?: stringResource(R.string.dashboard_not_available),
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_subscription_count),
            value = subscriptionCount.toString(),
        )
        if (selectedRemote != null) {
            val upload = selectedRemote.subscriptionUpload.coerceAtLeast(0)
            val download = selectedRemote.subscriptionDownload.coerceAtLeast(0)
            val used = upload.coerceAtMost(Long.MAX_VALUE - download) + download
            val total = selectedRemote.subscriptionTotal
            if (total > 0) {
                Text(
                    text =
                    stringResource(
                        R.string.subscription_usage,
                        formatBytes(used),
                        formatBytes(total),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selectedRemote.lastUpdated.time > 0) {
                DashboardMetric(
                    label = stringResource(R.string.dashboard_last_updated),
                    value = RelativeTimeFormatter.format(context, selectedRemote.lastUpdated),
                )
            }
        }
        DashboardAction(
            label = stringResource(R.string.dashboard_open_subscriptions),
            onClick = onOpenSubscriptions,
        )
    }
}

@Composable
fun CurrentProxyCard(
    profileName: String?,
    proxyGroup: String?,
    proxyName: String?,
    serviceStatus: Status,
    onOpenProxies: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_current_proxy),
        icon = Icons.Outlined.Route,
        accent = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    ) {
        DashboardMetric(
            label = stringResource(R.string.dashboard_selected_profile),
            value = profileName ?: stringResource(R.string.dashboard_not_available),
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_proxy_group),
            value = proxyGroup ?: stringResource(R.string.dashboard_not_available),
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_proxy_node),
            value =
            if (serviceStatus == Status.Started) {
                proxyName ?: stringResource(R.string.dashboard_not_available)
            } else {
                stringResource(R.string.status_default)
            },
        )
        if (serviceStatus == Status.Started) {
            DashboardAction(
                label = stringResource(R.string.dashboard_open_proxies),
                onClick = onOpenProxies,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsCard(
    serviceStatus: Status,
    networkMode: MihomoNetworkMode,
    onModeSelected: (MihomoNetworkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_network_settings),
        icon = Icons.Outlined.SettingsEthernet,
        modifier = modifier,
    ) {
        val modes = listOf(MihomoNetworkMode.SystemProxy, MihomoNetworkMode.VirtualNic)
        val systemProxyAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == networkMode,
                    onClick = { onModeSelected(mode) },
                    enabled =
                    serviceStatus == Status.Stopped &&
                        (mode != MihomoNetworkMode.SystemProxy || systemProxyAvailable),
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                MihomoNetworkMode.SystemProxy -> R.string.network_mode_system_proxy
                                MihomoNetworkMode.VirtualNic -> R.string.network_mode_virtual_nic
                            },
                        ),
                    )
                }
            }
        }
        DashboardMetric(
            label = stringResource(R.string.dashboard_service_status),
            value = serviceStatusLabel(serviceStatus),
        )
        Text(
            text = stringResource(
                if (serviceStatus == Status.Stopped) {
                    when {
                        networkMode == MihomoNetworkMode.SystemProxy && !systemProxyAvailable ->
                            R.string.network_mode_system_proxy_unavailable
                        networkMode == MihomoNetworkMode.SystemProxy ->
                            R.string.network_mode_system_proxy_description
                        else -> R.string.network_mode_virtual_nic_description
                    }
                } else {
                    R.string.network_mode_stop_before_change
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ProxyModePlaceholderCard(
    serviceStatus: Status,
    modifier: Modifier = Modifier,
) {
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_proxy_mode),
        icon = Icons.Outlined.Route,
        accent = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    ) {
        DashboardMetric(
            label = stringResource(R.string.dashboard_service_status),
            value = serviceStatusLabel(serviceStatus),
        )
        Text(
            text = stringResource(R.string.dashboard_start_to_view_mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TrafficStatsCard(
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_traffic_stats),
        icon = Icons.Outlined.DataUsage,
        accent = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrafficStatTile(
                icon = Icons.Outlined.Upload,
                label = stringResource(R.string.upload),
                value = uiState.uplink,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TrafficStatTile(
                icon = Icons.Outlined.Download,
                label = stringResource(R.string.download),
                value = uiState.downlink,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrafficStatTile(
                icon = Icons.Outlined.Link,
                label = stringResource(R.string.dashboard_connections_short),
                value = "${uiState.connectionsIn} / ${uiState.connectionsOut}",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            TrafficStatTile(
                icon = Icons.Outlined.Memory,
                label = stringResource(R.string.memory),
                value = uiState.memory.ifBlank { stringResource(R.string.dashboard_not_available) },
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.upload),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = uiState.uplinkTotal,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LineChart(
                    data = uiState.uplinkHistory,
                    lineColor = MaterialTheme.colorScheme.error,
                    animate = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.download),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = uiState.downlinkTotal,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LineChart(
                    data = uiState.downlinkHistory,
                    lineColor = MaterialTheme.colorScheme.primary,
                    animate = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun WebsiteTestCard(
    onOpenTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_website_test),
        icon = Icons.Outlined.NetworkCheck,
        accent = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.dashboard_website_test_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DashboardAction(
            label = stringResource(R.string.dashboard_open_test),
            onClick = onOpenTest,
        )
    }
}

@Composable
fun IPInfoCard(
    proxyPublicIp: String?,
    proxyLocation: String?,
    proxyColo: String?,
    proxyAvailable: Boolean,
    directPublicIp: String?,
    directLocation: String?,
    directColo: String?,
    directError: String?,
    underlyingTransport: UnderlyingTransport?,
    underlyingInterfaceName: String?,
    underlyingInterfaceAddresses: List<String>,
    underlyingValidated: Boolean,
    loading: Boolean,
    proxyError: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_ip_info),
        icon = Icons.Outlined.Public,
        accent = MaterialTheme.colorScheme.primary,
        modifier = modifier,
        action = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.dashboard_refresh_ip),
                    )
                }
            }
        },
    ) {
        DashboardMetric(
            label = stringResource(R.string.dashboard_underlying_network),
            value =
            listOfNotNull(
                underlyingTransport?.let { underlyingTransportLabel(it) },
                underlyingInterfaceName,
            ).joinToString(" · ").ifBlank { stringResource(R.string.dashboard_not_available) },
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_interface_addresses),
            value = underlyingInterfaceAddresses.joinToString(" · ").ifBlank {
                stringResource(R.string.dashboard_not_available)
            },
        )
        ExitIpRow(
            label = stringResource(R.string.dashboard_direct_public_ip),
            ip = directPublicIp,
            countryCode = directLocation,
            colo = directColo,
            loading = loading,
            available = underlyingTransport != null,
            error = directError,
        )
        ExitIpRow(
            label = stringResource(R.string.dashboard_proxy_public_ip),
            ip = proxyPublicIp,
            countryCode = proxyLocation,
            colo = proxyColo,
            loading = loading,
            available = proxyAvailable,
            error = proxyError,
        )
        if (underlyingTransport != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Text(
                text = stringResource(R.string.dashboard_network_validation_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (underlyingTransport != null && !underlyingValidated) {
            Text(
                text = stringResource(R.string.dashboard_network_not_validated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.dashboard_ip_query_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ClashInfoCard(
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    val coreVersion = remember { CoreVersion.current() }
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_clash_info),
        icon = Icons.Outlined.Info,
        accent = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    ) {
        DashboardMetric(
            label = stringResource(R.string.core_version_title),
            value = coreVersion,
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_proxy_mode),
            value = uiState.selectedClashMode.ifBlank { stringResource(R.string.dashboard_not_available) },
        )
        DashboardMetric(
            label = stringResource(R.string.memory),
            value = uiState.memory.ifBlank { stringResource(R.string.dashboard_not_available) },
        )
        DashboardMetric(
            label = stringResource(R.string.goroutines),
            value = uiState.goroutines.ifBlank { stringResource(R.string.dashboard_not_available) },
        )
    }
}

@Composable
fun SystemInfoCard(modifier: Modifier = Modifier) {
    val device =
        remember {
            listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    val architecture = remember { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_system_info),
        icon = Icons.Outlined.PhoneAndroid,
        accent = MaterialTheme.colorScheme.error,
        modifier = modifier,
    ) {
        DashboardMetric(
            label = stringResource(R.string.dashboard_app_version),
            value = BuildConfig.VERSION_NAME,
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_android_version),
            value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_device),
            value = device.ifBlank { stringResource(R.string.dashboard_not_available) },
        )
        DashboardMetric(
            label = stringResource(R.string.dashboard_architecture),
            value = architecture.ifBlank { stringResource(R.string.dashboard_not_available) },
        )
    }
}

@Composable
internal fun DashboardModuleCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
        androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = accent,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun DashboardInfoPill(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TrafficStatTile(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.08f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1.35f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DashboardAction(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun serviceStatusLabel(status: Status): String = when (status) {
    Status.Started -> stringResource(R.string.status_started)
    Status.Starting -> stringResource(R.string.status_starting)
    Status.Stopping -> stringResource(R.string.status_stopping)
    else -> stringResource(R.string.status_default)
}
