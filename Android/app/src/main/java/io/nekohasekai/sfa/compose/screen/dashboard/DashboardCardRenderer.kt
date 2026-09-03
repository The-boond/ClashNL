package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nekohasekai.sfa.compose.navigation.Screen
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.mihomo.MihomoNetworkMode

@Composable
fun DashboardCardRenderer(
    cardGroup: CardGroup,
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
    serviceStatus: Status = Status.Stopped,
    onClashModeSelected: (String) -> Unit,
    onNetworkModeSelected: (MihomoNetworkMode) -> Unit,
    onNavigate: (String) -> Unit,
    onRefreshIp: () -> Unit,
) {
    when (cardGroup) {
        CardGroup.Subscriptions ->
            SubscriptionSummaryCard(
                profiles = uiState.profiles,
                selectedProfileId = uiState.selectedProfileId,
                selectedProfileName = uiState.selectedProfileName,
                onOpenSubscriptions = { onNavigate(Screen.Subscriptions.route) },
                modifier = modifier,
            )

        CardGroup.CurrentProxy ->
            CurrentProxyCard(
                profileName = uiState.selectedProfileName,
                proxyGroup = uiState.currentProxyGroup,
                proxyName = uiState.currentProxyName,
                serviceStatus = serviceStatus,
                onOpenProxies = { onNavigate(Screen.Subscriptions.route) },
                modifier = modifier,
            )

        CardGroup.NetworkSettings ->
            NetworkSettingsCard(
                serviceStatus = serviceStatus,
                networkMode = uiState.networkMode,
                onModeSelected = onNetworkModeSelected,
                modifier = modifier,
            )

        CardGroup.ProxyMode ->
            if (uiState.clashModeVisible && uiState.clashModes.isNotEmpty()) {
                ClashModeCard(
                    modes = uiState.clashModes,
                    selectedMode = uiState.selectedClashMode,
                    onModeSelected = onClashModeSelected,
                    enabled = serviceStatus == Status.Stopped || serviceStatus == Status.Started,
                    modifier = modifier,
                )
            } else {
                ProxyModePlaceholderCard(
                    serviceStatus = serviceStatus,
                    modifier = modifier,
                )
            }

        CardGroup.TrafficStats ->
            TrafficStatsCard(
                uiState = uiState,
                modifier = modifier,
            )

        CardGroup.IPInfo ->
            IPInfoCard(
                proxyPublicIp = uiState.publicIp,
                proxyLocation = uiState.publicIpLocation,
                proxyColo = uiState.publicIpColo,
                proxyAvailable = uiState.proxyPublicIpAvailable,
                directPublicIp = uiState.directPublicIp,
                directLocation = uiState.directPublicIpLocation,
                directColo = uiState.directPublicIpColo,
                directError = uiState.directPublicIpError,
                underlyingTransport = uiState.underlyingTransport,
                underlyingInterfaceName = uiState.underlyingInterfaceName,
                underlyingInterfaceAddresses = uiState.underlyingInterfaceAddresses,
                underlyingValidated = uiState.underlyingValidated,
                loading = uiState.publicIpLoading,
                proxyError = uiState.publicIpError,
                onRefresh = onRefreshIp,
                modifier = modifier,
            )

        CardGroup.ClashInfo ->
            ClashInfoCard(
                uiState = uiState,
                modifier = modifier,
            )

        CardGroup.SystemInfo ->
            SystemInfoCard(modifier = modifier)
    }
}
