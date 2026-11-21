package com.example.unifiedreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One destination in the navigation drawer. The selected destination sits on the selected
 * container, as a selected settings row or chip does; unselected rows are muted.
 */
@Composable
fun AppDrawerItem(
    label: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: NavigationDrawerItemColors = AppDrawerItemDefaults.colors(),
) {
    NavigationDrawerItem(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        badge = badge,
        shape = shape,
        colors = colors,
    )
}

/**
 * One destination in the side menu at its compact height (42, growing with large text): the [icon], the
 * [label] and, at the end, a [count] ("6,218", "34"). The selected destination sits on the selected
 * fill, as [AppDrawerItem]; the others are muted. The menu never scrolls, so its rows are this size.
 * [dense] rows keep a little less room at their sides, for two columns of them side by side.
 */
@Composable
fun AppDrawerRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    dense: Boolean = false,
) {
    val (selectedFill, onSelectedFill) = appNavigationSelectedColors()
    val contentColor = if (selected) onSelectedFill else MaterialTheme.colorScheme.onSurfaceVariant
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = AppDrawerItemDefaults.rowHeight)
                .clip(CircleShape)
                .background(if (selected) selectedFill else Color.Transparent)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = if (dense) AppDrawerItemDefaults.denseRowInset else AppDrawerItemDefaults.rowInset),
            horizontalArrangement = Arrangement.spacedBy(
                if (dense) AppDrawerItemDefaults.denseRowIconGap else AppDrawerItemDefaults.rowIconGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(AppDrawerItemDefaults.rowIconSize))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.5.sp, lineHeight = 20.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

object AppDrawerItemDefaults {
    /** A selected destination's fill and its content colour, in the menu and on the rail. */
    @Composable
    fun selectedColors(): Pair<Color, Color> = appNavigationSelectedColors()

    /** [AppDrawerRow]'s height at the default text size. */
    val rowHeight: Dp = 42.dp
    val rowInset: Dp = 16.dp
    val rowIconSize: Dp = 22.dp
    val rowIconGap: Dp = 12.dp
    val denseRowInset: Dp = 12.dp
    val denseRowIconGap: Dp = 10.dp

    @Composable
    fun colors(): NavigationDrawerItemColors {
        val (selectedFill, onSelectedFill) = appNavigationSelectedColors()
        return NavigationDrawerItemDefaults.colors(
            selectedContainerColor = selectedFill,
            selectedIconColor = onSelectedFill,
            selectedTextColor = onSelectedFill,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A short accent pill at the end of a drawer row, such as the version of a waiting update. On
 * the [selected] row it swaps to the row's text colour so it still stands out.
 */
@Composable
fun AppDrawerBadge(
    text: String,
    selected: Boolean = false,
) {
    val (selectedFill, onSelectedFill) = appNavigationSelectedColors()
    AppBadge(
        shape = CircleShape,
        color = if (selected) onSelectedFill else MaterialTheme.colorScheme.primary,
        contentColor = if (selected) selectedFill else MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * A small dot at the top end of an icon, for something waiting behind the button (an update
 * behind the drawer's menu button). Nothing is drawn while [visible] is false.
 */
fun Modifier.appBadgeDot(
    visible: Boolean,
    color: Color,
): Modifier = if (!visible) {
    this
} else {
    drawWithContent {
        drawContent()
        val radius = 4.5.dp.toPx()
        drawCircle(color = color, radius = radius, center = Offset(size.width - radius / 2, radius / 2))
    }
}
