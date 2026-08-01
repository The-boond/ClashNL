package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsViewModel
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status

/**
 * Dedicated subscription/profile root page.
 *
 * Profile management used to live inside the first dashboard tab. Keeping it
 * here preserves that fast workflow while the center tab becomes a real,
 * independently configurable dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    serviceStatus: Status = Status.Stopped,
    groupsViewModel: GroupsViewModel? = null,
    onOpenNewProfile: (NewProfileArgs) -> Unit = {},
    viewModel: DashboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_subscriptions)) },
        )
    }

    LazyColumn(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        item {
            ProfilesCard(
                profiles = uiState.profiles,
                selectedProfileId = uiState.selectedProfileId,
                serviceStatus = serviceStatus,
                groupsViewModel = groupsViewModel,
                isLoading = uiState.isLoading,
                showAddProfileSheet = uiState.showAddProfileSheet,
                updatingProfileId = uiState.updatingProfileId,
                updatedProfileId = uiState.updatedProfileId,
                onProfileSelected = viewModel::selectProfile,
                onProfileEdit = viewModel::editProfile,
                onProfileDelete = viewModel::deleteProfile,
                onProfileUpdate = viewModel::updateProfile,
                onShowAddProfileSheet = viewModel::showAddProfileSheet,
                onHideAddProfileSheet = viewModel::hideAddProfileSheet,
                onOpenNewProfile = onOpenNewProfile,
            )
        }
    }
}
