package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClashModeCard(modes: List<String>, selectedMode: String, onModeSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val ruleLabel = stringResource(R.string.proxy_mode_rule)
    val directLabel = stringResource(R.string.proxy_mode_direct)
    val globalLabel = stringResource(R.string.proxy_mode_global)
    val modeLabels =
        remember(modes, ruleLabel, directLabel, globalLabel) {
            modes.associateWith { mode ->
                localizeClashMode(
                    mode = mode,
                    ruleLabel = ruleLabel,
                    directLabel = directLabel,
                    globalLabel = globalLabel,
                )
            }
        }
    DashboardModuleCard(
        title = stringResource(R.string.dashboard_proxy_mode),
        icon = Icons.Outlined.Tune,
        accent = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val textMeasurer = rememberTextMeasurer()
            val textStyle = MaterialTheme.typography.labelLarge
            val density = LocalDensity.current

            val totalTextWidth = remember(modeLabels, textStyle, density) {
                modes.sumOf { mode ->
                    textMeasurer.measure(modeLabels[mode].orEmpty(), textStyle).size.width
                }
            }
            val buttonPadding = with(density) { (24.dp * modes.size).roundToPx() }
            val estimatedWidth = totalTextWidth + buttonPadding
            val availableWidth = with(density) { maxWidth.roundToPx() }

            val useDropdown = estimatedWidth > availableWidth

            if (useDropdown) {
                ModeDropdown(
                    modes = modes,
                    modeLabels = modeLabels,
                    selectedMode = selectedMode,
                    onModeSelected = onModeSelected,
                )
            } else {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = modes.size,
                            ),
                            onClick = { onModeSelected(mode) },
                            selected = mode == selectedMode,
                        ) {
                            Text(modeLabels[mode] ?: mode)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeDropdown(
    modes: List<String>,
    modeLabels: Map<String, String>,
    selectedMode: String,
    onModeSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = if (isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceDim
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modeLabels[selectedMode] ?: selectedMode,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(modeLabels[mode] ?: mode) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        if (mode == selectedMode) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

internal fun localizeClashMode(
    mode: String,
    ruleLabel: String,
    directLabel: String,
    globalLabel: String,
): String = when (mode.trim().lowercase()) {
    "rule" -> ruleLabel
    "direct" -> directLabel
    "global" -> globalLabel
    else -> mode
}
