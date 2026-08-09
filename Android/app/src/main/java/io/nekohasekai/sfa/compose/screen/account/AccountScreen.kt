package io.nekohasekai.sfa.compose.screen.account

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.account.model.AccountDetails
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    navController: NavController,
    viewModel: AccountViewModel = viewModel(),
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.account_title)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        when {
            !state.isSignedIn -> LoginContent(
                isLoading = state.isLoading,
                errorMessage = state.errorMessage,
                onLogin = viewModel::login,
            )

            state.details != null -> AccountDetailsContent(
                details = state.details!!,
                isLoading = state.isLoading,
                errorMessage = state.errorMessage,
                profileSynced = state.profileSyncResult != null,
                onManagePlans = { navController.navigate("settings/account/plans") },
                onRefresh = viewModel::refresh,
                onLogout = viewModel::logout,
            )

            else -> SignedInLoadingContent(
                email = state.session?.email.orEmpty(),
                isLoading = state.isLoading,
                errorMessage = state.errorMessage,
                onRetry = viewModel::refresh,
                onLogout = viewModel::logout,
            )
        }
    }
}

@Composable
private fun LoginContent(
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val canLogin = email.isNotBlank() && password.length >= 8 && !isLoading

    Text(
        text = stringResource(R.string.account_login_heading),
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.account_login_description),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.account_email)) },
        singleLine = true,
        enabled = !isLoading,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.account_password)) },
        singleLine = true,
        enabled = !isLoading,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (canLogin) onLogin(email, password) },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    errorMessage?.let {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = { onLogin(email, password) },
        enabled = canLogin,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(R.string.account_login_action))
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.account_security_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccountDetailsContent(
    details: AccountDetails,
    isLoading: Boolean,
    errorMessage: String?,
    profileSynced: Boolean,
    onManagePlans: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val used = details.usedBytes
    val total = details.transferLimitBytes
    val usageProgress = if (total > 0L) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val expired = details.isExpired(System.currentTimeMillis() / 1000L)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = details.plan?.name ?: stringResource(R.string.account_no_plan),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = details.email,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(
                    R.string.account_usage_value,
                    Formatter.formatFileSize(context, used),
                    Formatter.formatFileSize(context, total),
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { usageProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            if (expired) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.account_expired),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AccountInfoItem(
                icon = Icons.Outlined.Event,
                label = stringResource(R.string.account_expiry),
                value = formatEpochSeconds(details.expiresAtEpochSeconds),
            )
            AccountInfoItem(
                icon = Icons.Outlined.Devices,
                label = stringResource(R.string.account_device_limit),
                value = details.plan?.deviceLimit?.toString() ?: stringResource(R.string.account_unlimited),
            )
            AccountInfoItem(
                icon = Icons.Outlined.Speed,
                label = stringResource(R.string.account_speed_limit),
                value = details.plan?.speedLimitMbps
                    ?.let { stringResource(R.string.account_speed_value, it) }
                    ?: stringResource(R.string.account_unlimited),
            )
            AccountInfoItem(
                icon = Icons.Outlined.CloudSync,
                label = stringResource(R.string.account_subscription_sync),
                value = if (profileSynced) {
                    stringResource(R.string.account_subscription_synced)
                } else {
                    stringResource(R.string.account_subscription_pending)
                },
            )
        }
    }

    errorMessage?.let {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = onManagePlans,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.account_manage_plans_action))
    }
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onRefresh,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.account_refresh_action))
        }
    }
    TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.account_logout_action))
    }
    Text(
        text = stringResource(R.string.account_logout_profile_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SignedInLoadingContent(
    email: String,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
) {
    Text(text = email, style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(16.dp))
    if (isLoading) {
        CircularProgressIndicator()
    }
    errorMessage?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.account_retry_action))
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    TextButton(onClick = onLogout) {
        Text(stringResource(R.string.account_logout_action))
    }
}

@Composable
private fun AccountInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun formatEpochSeconds(value: Long): String {
    if (value <= 0L) return stringResource(R.string.account_no_expiry)
    val millis = if (value > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else value * 1000L
    return remember(value) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }
}
