package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.navigation.Screen

/**
 * A compact bottom bar whose top boundary follows the selected destination.
 *
 * The selected icon and label rise into a shallow crest while the other items
 * settle lower. Both movements share the same spring so the boundary and
 * content read as one restrained arc rather than a floating center button.
 */
@Composable
fun CurvedBottomNavigationBar(
    screens: List<Screen>,
    selectedRoute: String?,
    showSettingsBadge: Boolean,
    onScreenSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (screens.isEmpty()) return

    val selectedIndex = screens.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val targetCenterFraction = (selectedIndex + 0.5f) / screens.size
    val animatedCenterFraction by animateFloatAsState(
        targetValue = targetCenterFraction,
        animationSpec =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "BottomBarArcCenter",
    )

    val contentHeight = 62.dp
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)

    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .height(contentHeight + bottomInset),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseY = 13.dp.toPx()
            val peakY = 2.dp.toPx()
            val halfArcWidth = 58.dp.toPx()
            val centerX = size.width * animatedCenterFraction
            val arcStart = (centerX - halfArcWidth).coerceAtLeast(0f)
            val arcEnd = (centerX + halfArcWidth).coerceAtMost(size.width)

            val topBoundary =
                Path().apply {
                    moveTo(0f, baseY)
                    lineTo(arcStart, baseY)
                    cubicTo(
                        centerX - halfArcWidth * 0.58f,
                        baseY,
                        centerX - halfArcWidth * 0.42f,
                        peakY,
                        centerX,
                        peakY,
                    )
                    cubicTo(
                        centerX + halfArcWidth * 0.42f,
                        peakY,
                        centerX + halfArcWidth * 0.58f,
                        baseY,
                        arcEnd,
                        baseY,
                    )
                    lineTo(size.width, baseY)
                }

            val background =
                Path().apply {
                    moveTo(0f, baseY)
                    lineTo(arcStart, baseY)
                    cubicTo(
                        centerX - halfArcWidth * 0.58f,
                        baseY,
                        centerX - halfArcWidth * 0.42f,
                        peakY,
                        centerX,
                        peakY,
                    )
                    cubicTo(
                        centerX + halfArcWidth * 0.42f,
                        peakY,
                        centerX + halfArcWidth * 0.58f,
                        baseY,
                        arcEnd,
                        baseY,
                    )
                    lineTo(size.width, baseY)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }

            drawPath(background, color = surfaceColor)
            drawPath(
                topBoundary,
                color = outlineColor,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(contentHeight)
                .align(Alignment.TopCenter),
        ) {
            screens.forEachIndexed { index, screen ->
                val isSelected = index == selectedIndex
                val verticalOffset by animateDpAsState(
                    targetValue = if (isSelected) 0.dp else 9.dp,
                    animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "BottomBarItemOffset",
                )
                val itemColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                val itemShape = RoundedCornerShape(percent = 50)

                Box(
                    modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier =
                        Modifier
                            .width(76.dp)
                            .height(54.dp)
                            .offset(y = verticalOffset)
                            .shadow(
                                elevation = if (isSelected) 4.dp else 0.dp,
                                shape = itemShape,
                                clip = false,
                            )
                            .clip(itemShape)
                            .background(
                                color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                },
                                shape = itemShape,
                            )
                            .clickable(
                                role = Role.Tab,
                                onClick = { onScreenSelected(screen) },
                            )
                            .semantics { selected = isSelected },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (screen == Screen.Settings && showSettingsBadge) {
                            BadgedBox(
                                badge = {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary)
                                },
                            ) {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = null,
                                    tint = itemColor,
                                    modifier = Modifier.size(23.dp),
                                )
                            }
                        } else {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = null,
                                tint = itemColor,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                        Text(
                            text = stringResource(screen.titleRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = itemColor,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
