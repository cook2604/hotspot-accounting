package com.hotspot.accounting.ui.glass

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One entry in [GlassBottomBar]. */
data class GlassTab(
    val label: String,
    val icon: ImageVector,
)

/**
 * A floating glass tab bar, in the manner of iOS 26's translucent bottom chrome.
 *
 * Two deliberate departures from a conventional Material navigation bar:
 *  - it floats clear of the screen edges with its own rounded shape, so the backdrop shows around it
 *    and the material reads as a pane rather than a solid dock;
 *  - the selected item gets a small glass pill behind it, which is how the platform signals selection
 *    without breaking the translucency of the bar itself.
 */
@Composable
fun GlassBottomBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPane(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(26.dp),
        material = GlassMaterial.CHROME,
        contentPadding = 8.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                else Color.Transparent
                            )
                            .padding(1.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * A large translucent title with optional trailing actions, matching the "large title" idiom of the
 * platform this is modelled on: the title sits in the content flow rather than in a solid bar, so the
 * backdrop stays visible behind everything.
 */
@Composable
fun GlassLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}

/**
 * A compact glass chip, used for range selectors and inline status labels.
 *
 * Kept separate from Material's `FilterChip` because the selected state has to be expressed as a
 * brighter tint on the same translucent material, rather than as a solid fill which would punch a
 * hole in the glass.
 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPane(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        material = if (selected) GlassMaterial.THICK else GlassMaterial.ULTRA_THIN,
        accent = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentPadding = 0.dp,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A thin glass divider, used inside panes where a solid rule would look heavy. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.22f),
                        Color.Transparent,
                    )
                )
            )
    )
}

/** Small helper for a labelled value inside a glass pane. */
@Composable
fun GlassStat(
    label: String,
    value: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Horizontal breathing room used between glass panes. */
val GlassGap = 12.dp

/** Standard width for a small inline icon inside a glass pane. */
val GlassIconSize = 18.dp

/** Spacer helper so call sites do not need to import layout each time. */
@Composable
fun GlassSpacer(height: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.height(height))
}

/** Row helper with a small gap, used inside panes. */
@Composable
fun GlassRowGap(width: androidx.compose.ui.unit.Dp = 8.dp) {
    Spacer(Modifier.width(width))
}
