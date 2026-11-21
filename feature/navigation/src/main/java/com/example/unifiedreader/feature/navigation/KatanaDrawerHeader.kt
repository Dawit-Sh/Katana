package com.example.unifiedreader.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.ui.components.AppDrawerItemDefaults
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.appBadgeDot

/**
 * The side menu's top: Katana's [logo] and "Katana 刀", then the Settings gear at the end (Settings
 * left the list for it). The gear sits on the selected fill while Settings is open, and a dot on it
 * says something waits there ([settingsBadge], the version of a waiting update).
 */
@Composable
internal fun KatanaDrawerHeader(
    settingsSelected: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    settingsBadge: String? = null,
    logo: (@Composable () -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = HeaderStart, bottom = HeaderBottom),
        horizontalArrangement = Arrangement.spacedBy(HeaderGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        logo?.invoke()
        Text(
            text = buildAnnotatedString {
                append("Katana ")
                withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = muted)) { append("刀") }
            },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.weight(1f))
        val (selectedFill, onSelectedFill) = AppDrawerItemDefaults.selectedColors()
        AppIconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(SettingsButtonSize)
                .clip(CircleShape)
                .background(if (settingsSelected) selectedFill else Color.Transparent),
        ) {
            Icon(
                imageVector = AppIcons.Settings,
                contentDescription = settingsBadge?.let { version -> "Settings, update $version waiting" } ?: "Settings",
                tint = if (settingsSelected) onSelectedFill else muted,
                modifier = Modifier.appBadgeDot(visible = settingsBadge != null, color = MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private val HeaderStart: Dp = 10.dp
private val HeaderBottom: Dp = 6.dp
private val HeaderGap: Dp = 10.dp
private val SettingsButtonSize: Dp = 40.dp
