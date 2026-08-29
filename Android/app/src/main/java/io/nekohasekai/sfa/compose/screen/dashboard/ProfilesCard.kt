package io.nekohasekai.sfa.compose.screen.dashboard

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.qr.QRCodeDialog
import io.nekohasekai.sfa.compose.component.qr.QRSDialog
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.screen.configuration.ProfileImportHandler
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsViewModel
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.compose.util.QRCodeGenerator
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.ktx.errorDialogBuilder
import io.nekohasekai.sfa.ktx.shareProfile
import io.nekohasekai.sfa.utils.MihomoProfileExport
import io.nekohasekai.sfa.utils.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesCard(
    profiles: List<Profile>,
    selectedProfileId: Long,
    serviceStatus: Status,
    groupsViewModel: GroupsViewModel? = null,
    isLoading: Boolean,
    showAddProfileSheet: Boolean,
    updatingProfileId: Long? = null,
    updatedProfileId: Long? = null,
    onProfileSelected: (Long) -> Unit,
    onProfileEdit: (Profile) -> Unit,
    onProfileDelete: (Profile) -> Unit,
    onProfileUpdate: (Profile) -> Unit,
    onShowAddProfileSheet: () -> Unit,
    onHideAddProfileSheet: () -> Unit,
    onOpenNewProfile: (NewProfileArgs) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val importHandler = remember { ProfileImportHandler(context) }

    var showQRCodeDialog by remember { mutableStateOf(false) }
    var qrCodeProfile by remember { mutableStateOf<Profile?>(null) }

    var showQRSDialog by remember { mutableStateOf(false) }
    var qrsProfile by remember { mutableStateOf<Profile?>(null) }
    var qrsProfileData by remember { mutableStateOf<ByteArray?>(null) }

    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportName by remember { mutableStateOf<String?>(null) }
    var pendingQrsData by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    var showQRScanSheet by remember { mutableStateOf(false) }
    var fileExportProfile by remember { mutableStateOf<Profile?>(null) }

    val importFromFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                coroutineScope.launch {
                    when (val parseResult = importHandler.parseUri(uri)) {
                        is ProfileImportHandler.UriParseResult.Success -> {
                            withContext(Dispatchers.Main) {
                                pendingImportName = parseResult.name
                                pendingImportUri = uri
                                showImportConfirmDialog = true
                            }
                        }
                        is ProfileImportHandler.UriParseResult.Error -> {
                            withContext(Dispatchers.Main) {
                                context.errorDialogBuilder(Exception(parseResult.message)).show()
                            }
                        }
                    }
                }
            }
        }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MihomoProfileExport.CONTENT_TYPE),
    ) { uri ->
        val exportProfile = fileExportProfile
        fileExportProfile = null
        if (uri != null) {
            if (exportProfile != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val profileData = MihomoProfileExport.read(exportProfile)
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(profileData)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.success_profile_saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "${context.getString(R.string.failed_save_profile)}: ${e.message}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    var expandedProfileId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.title_configuration),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onShowAddProfileSheet) {
                Text(stringResource(R.string.add_profile))
            }
        }

        if (profiles.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.no_profiles),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            profiles.forEach { profile ->
                key(profile.id) {
                    val isSelected = profile.id == selectedProfileId
                    val isExpanded = profile.id == expandedProfileId && isSelected
                    SubscriptionProfileCard(
                        profile = profile,
                        isSelected = isSelected,
                        isExpanded = isExpanded,
                        isLoading = isLoading && isSelected,
                        isUpdating = profile.id == updatingProfileId,
                        showUpdateSuccess = profile.id == updatedProfileId,
                        serviceStatus = serviceStatus,
                        groupsViewModel = groupsViewModel,
                        onClick = {
                            if (isExpanded) {
                                expandedProfileId = null
                            } else {
                                expandedProfileId = profile.id
                                if (!isSelected) {
                                    onProfileSelected(profile.id)
                                }
                            }
                        },
                        onEdit = { onProfileEdit(profile) },
                        onUpdate = { onProfileUpdate(profile) },
                        onDelete = { onProfileDelete(profile) },
                        onShareFile = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    context.shareProfile(profile)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        },
                        onSaveFile = {
                            fileExportProfile = profile
                            saveFileLauncher.launch(MihomoProfileExport.fileName(profile.name))
                        },
                        onShareURL = {
                            qrCodeProfile = profile
                            showQRCodeDialog = true
                        },
                        onShareQRS = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val profileData = MihomoProfileExport.read(profile)
                                    withContext(Dispatchers.Main) {
                                        qrsProfile = profile
                                        qrsProfileData = profileData
                                        showQRSDialog = true
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(e).show()
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAddProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = onHideAddProfileSheet,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_profile),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        importFromFileLauncher.launch("*/*")
                    },
                    headlineContent = {
                        Text(stringResource(R.string.profile_add_import_file))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.import_from_file_description))
                    },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        showQRScanSheet = true
                    },
                    headlineContent = {
                        Text(stringResource(R.string.profile_add_scan_qr_code))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.scan_qr_code_description))
                    },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        onOpenNewProfile(NewProfileArgs(startWithRemote = true))
                    },
                    headlineContent = {
                        Text(stringResource(R.string.profile_add_subscription_url))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.profile_add_subscription_url_description))
                    },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onHideAddProfileSheet()
                        onOpenNewProfile(NewProfileArgs())
                    },
                    headlineContent = {
                        Text(stringResource(R.string.profile_add_create_manually))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.create_new_profile_description))
                    },
                )
            }
        }
    }

    if (showQRCodeDialog && qrCodeProfile != null) {
        val profile = qrCodeProfile!!
        val link = remember(profile) {
            MihomoProfileExport.remoteImportLink(
                profile.name,
                profile.typed.remoteURL,
            )
        }
        val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
        val qrBitmap = QRCodeGenerator.rememberPrimaryBitmap(link, backgroundColor = surfaceColor)

        QRCodeDialog(
            bitmap = qrBitmap,
            onDismiss = {
                showQRCodeDialog = false
                qrCodeProfile = null
            },
        )
    }

    if (showQRSDialog && qrsProfile != null && qrsProfileData != null) {
        QRSDialog(
            profileData = qrsProfileData!!,
            profileName = qrsProfile!!.name,
            onDismiss = {
                showQRSDialog = false
                qrsProfile = null
                qrsProfileData = null
            },
        )
    }

    if (showImportConfirmDialog && pendingImportName != null) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirmDialog = false
                pendingImportName = null
                pendingQrsData = null
                pendingImportUri = null
            },
            title = { Text(stringResource(R.string.import_profile_confirm_title)) },
            text = { Text(stringResource(R.string.import_profile_confirm_message, pendingImportName!!)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        val qrsData = pendingQrsData
                        val importUri = pendingImportUri
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                        coroutineScope.launch {
                            if (qrsData != null) {
                                when (val result = importHandler.importFromQRSData(qrsData)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onProfileEdit(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            } else if (importUri != null) {
                                when (val result = importHandler.importFromUri(importUri)) {
                                    is ProfileImportHandler.ImportResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            onProfileEdit(result.profile)
                                        }
                                    }
                                    is ProfileImportHandler.ImportResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            context.errorDialogBuilder(Exception(result.message)).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showQRScanSheet) {
        QRScanSheet(
            onDismiss = { showQRScanSheet = false },
            onScanResult = { result ->
                showQRScanSheet = false
                when (result) {
                    is QRScanResult.QRSData -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRSData(result.data)) {
                                is ProfileImportHandler.QRSParseResult.Success -> {
                                    withContext(Dispatchers.Main) {
                                        pendingImportName = parseResult.name
                                        pendingQrsData = result.data
                                        showImportConfirmDialog = true
                                    }
                                }
                                is ProfileImportHandler.QRSParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                    is QRScanResult.Text -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRCode(result.value)) {
                                is ProfileImportHandler.QRCodeParseResult.RemoteProfile -> {
                                    withContext(Dispatchers.Main) {
                                        onOpenNewProfile(
                                            NewProfileArgs(
                                                importName = parseResult.name,
                                                importUrl = parseResult.url,
                                            ),
                                        )
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.LocalProfile -> {
                                    when (val importResult = importHandler.importFromQRCode(result.value)) {
                                        is ProfileImportHandler.ImportResult.Success -> {
                                            withContext(Dispatchers.Main) {
                                                onProfileEdit(importResult.profile)
                                            }
                                        }
                                        is ProfileImportHandler.ImportResult.Error -> {
                                            withContext(Dispatchers.Main) {
                                                context.errorDialogBuilder(Exception(importResult.message)).show()
                                            }
                                        }
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        context.errorDialogBuilder(Exception(parseResult.message)).show()
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionProfileCard(
    profile: Profile,
    isSelected: Boolean,
    isExpanded: Boolean,
    isLoading: Boolean,
    isUpdating: Boolean,
    showUpdateSuccess: Boolean,
    serviceStatus: Status,
    groupsViewModel: GroupsViewModel?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onShareFile: () -> Unit,
    onSaveFile: () -> Unit,
    onShareURL: () -> Unit,
    onShareQRS: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation =
        CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                        )
                        if (profile.typed.type == TypedProfile.Type.Remote) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (showUpdateSuccess) {
                                                R.string.success
                                            } else {
                                                R.string.update_profile
                                            },
                                        ),
                                    )
                                },
                                enabled = !isUpdating && !showUpdateSuccess,
                                onClick = {
                                    showMenu = false
                                    onUpdate()
                                },
                            )
                        }
                        if (profile.typed.core == ProfileCore.Mihomo) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_as_file)) },
                                onClick = {
                                    showMenu = false
                                    onSaveFile()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_as_file)) },
                                onClick = {
                                    showMenu = false
                                    onShareFile()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_as_qrs)) },
                                onClick = {
                                    showMenu = false
                                    onShareQRS()
                                },
                            )
                            if (profile.typed.type == TypedProfile.Type.Remote) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profile_share_url)) },
                                    onClick = {
                                        showMenu = false
                                        onShareURL()
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_delete)) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            ProfileInfoRow(profile = profile)

            if (isSelected && !isExpanded) {
                Text(
                    text = stringResource(R.string.profile_tap_to_choose_node),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                SubscriptionNodesPanel(
                    serviceStatus = serviceStatus,
                    profile = profile,
                    viewModel = groupsViewModel,
                )
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(profile: Profile?) {
    if (profile == null) return

    val context = LocalContext.current

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (profile.typed.type == TypedProfile.Type.Remote) {
                    stringResource(R.string.profile_type_remote)
                } else {
                    stringResource(R.string.profile_type_local)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (profile.typed.type == TypedProfile.Type.Remote) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = RelativeTimeFormatter.format(context, profile.typed.lastUpdated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (profile.typed.type == TypedProfile.Type.Remote) {
            val upload = profile.typed.subscriptionUpload
            val download = profile.typed.subscriptionDownload
            val total = profile.typed.subscriptionTotal
            val hasUsage = total >= 0 && (upload >= 0 || download >= 0)
            val hasExpiration = profile.typed.subscriptionExpireAt > 0

            if (hasUsage) {
                val safeDownload = download.coerceAtLeast(0)
                val used =
                    upload.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - safeDownload) +
                        safeDownload
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
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() },
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                    )
                }
            }

            if (hasExpiration) {
                val expiration = Date(profile.typed.subscriptionExpireAt)
                Text(
                    text =
                    if (expiration.time <= System.currentTimeMillis()) {
                        stringResource(R.string.subscription_expired)
                    } else {
                        stringResource(
                            R.string.subscription_expires,
                            RelativeTimeFormatter.format(context, expiration),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
