package com.example.unifiedreader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppButtonSpinner
import com.example.unifiedreader.ui.components.AppCircularProgress
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppLinearProgress
import com.example.unifiedreader.ui.components.AppSheetLayout
import com.example.unifiedreader.ui.components.AppTextButton
import com.example.unifiedreader.ui.components.AppTextField
import com.example.unifiedreader.ui.components.AppTonalButton
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

/**
 * Katana Desk, from Settings › Sources and the Local library's ⋮ (his OK on the round's page, item 4): before
 * pairing, the PC found on this Wi-Fi with a check, its name and address, the hint, six code boxes and Pair (a
 * typed address only when nothing is found, or asked for); after, which PC, how its sync stands, Sync now and
 * Forget.
 */
@Composable
internal fun KatanaDeskSheet(
    look: KatanaDeskSheetLook,
    pairing: KatanaDeskPairing,
    onSyncNow: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheetLayout(onDismissRequest = onDismiss, skipPartiallyExpanded = true) { _ ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppLayout.inset).padding(bottom = AppLayout.inset),
            verticalArrangement = Arrangement.spacedBy(SHEET_GAP),
        ) {
            Text(text = "Katana Desk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val desk = look.paired
            if (desk == null) {
                DisposableEffect(pairing) {
                    pairing.start()
                    onDispose(pairing::stop)
                }
                PairContent(look.form, pairing)
            } else {
                PairedContent(look, desk, onSyncNow = onSyncNow, onForget = onForget)
            }
        }
    }
}

/** What the Katana Desk sheet shows: the PC paired (or the pairing screen), and how its sync stands. */
internal data class KatanaDeskSheetLook(
    val paired: KatanaDeskPaired?,
    val form: KatanaDeskPairForm,
    val books: Int,
    val syncing: Boolean,
    val problem: String?,
    val syncedAt: Long,
    val brought: KatanaDeskBringing?,
    val getting: KatanaDeskGetting?,
)

@Composable
private fun PairContent(form: KatanaDeskPairForm, pairing: KatanaDeskPairing) {
    Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
        if (form.found.isNotEmpty() || form.looking) FoundDesks(form, onPick = pairing::pick)
        if (form.nothingFound) {
            Text(
                text = "No Katana Desk on this Wi-Fi. Open Katana Desk on your PC and turn on Sync on this Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTonalButton(onClick = pairing::lookAgain, modifier = Modifier.fillMaxWidth()) { Text("Look again") }
        }
        if (form.found.isNotEmpty() || form.showsAddressField) CodeForm(form, pairing)
    }
}

/** The address (when typed), the hint or what went wrong, the code boxes, Pair, and the way to type an address. */
@Composable
private fun CodeForm(form: KatanaDeskPairForm, pairing: KatanaDeskPairing) {
    Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
        if (form.showsAddressField) {
            AppTextField(
                value = form.address,
                onValueChange = pairing::onAddress,
                label = { Text("Address") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val problem = form.problem?.message
        if (problem != null) {
            WarningBox(problem)
        } else {
            Text(
                text = "Type the code Katana Desk shows (Settings › Sync on this Wi-Fi › Pair a device).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CodeBoxes(code = form.code, enabled = !form.pairing, onCode = pairing::onCode)
        AppButton(onClick = pairing::pair, enabled = form.canPair, modifier = Modifier.fillMaxWidth()) {
            if (form.pairing) AppButtonSpinner() else Text("Pair")
        }
        if (form.found.isNotEmpty() && !form.showsAddressField) {
            AppTextButton(onClick = pairing::typeAddress, modifier = Modifier.fillMaxWidth()) { Text("Not your PC? Type its address") }
        }
    }
}

/** The PCs found: one with a check; two or more listed by name, the picked one checked. */
@Composable
private fun FoundDesks(form: KatanaDeskPairForm, onPick: (KatanaDeskFound) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
        if (form.found.isEmpty() && form.looking) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppCircularProgress(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(text = "Looking for Katana Desk on this Wi-Fi…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val several = form.found.size > 1
        if (several) Text(text = "Pick your PC:", style = MaterialTheme.typography.bodyMedium)
        form.found.forEach { desk ->
            DeskCard(desk = desk, checked = desk.address == form.address && !form.typed, onClick = { onPick(desk) }.takeIf { several })
        }
    }
}

/** A PC: a check (or an empty ring when another is picked), its name, and "<address> · on this Wi-Fi". */
@Composable
private fun DeskCard(desk: KatanaDeskFound, checked: Boolean, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(UnifiedReaderThemeTokens.presentation.controlCornerRadius)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(UnifiedReaderThemeTokens.colors.surfaceRaised)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = AppLayout.menuInset, vertical = AppLayout.headingGap),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) accent.copy(alpha = CHECK_TINT) else Color.Transparent)
                .border(if (checked) 0.dp else 2.dp, accent.copy(alpha = RING_TINT), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(AppIcons.Check, contentDescription = "Picked", tint = accent, modifier = Modifier.size(16.dp))
        }
        Column {
            Text(text = desk.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${desk.address.substringBeforeLast(':')} · on this Wi-Fi",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Six boxes for the code; the next box to fill is outlined in the accent. The keyboard comes up by itself. */
@Composable
private fun CodeBoxes(code: String, enabled: Boolean, onCode: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    // and again once a refused code has emptied the boxes
    LaunchedEffect(enabled) { if (enabled) focus.requestFocusSafely() }
    val shape = RoundedCornerShape(UnifiedReaderThemeTokens.presentation.controlCornerRadius)
    BasicTextField(
        value = code,
        onValueChange = onCode,
        enabled = enabled,
        singleLine = true,
        // a plain number field, so no password manager offers to save the code
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Code on the PC" },
        decorationBox = { _ ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                repeat(KATANA_DESK_CODE_DIGITS) { index ->
                    val next = index == code.length
                    Box(
                        modifier = Modifier
                            .size(width = 42.dp, height = 52.dp)
                            .clip(shape)
                            .background(UnifiedReaderThemeTokens.colors.surfaceRaised)
                            .border(
                                width = if (next) 2.dp else 1.dp,
                                color = if (next) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = shape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = code.getOrNull(index)?.toString().orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    )
}

// The field may not be placed yet on the first frame; then the person taps it.
private fun FocusRequester.requestFocusSafely() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        Unit
    }
}

/** What went wrong with pairing: an amber box, with the next step in its words. */
@Composable
private fun WarningBox(text: String) {
    val amber = Color(AMBER)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UnifiedReaderThemeTokens.presentation.controlCornerRadius))
            .background(lerp(UnifiedReaderThemeTokens.colors.surfaceRaised, amber, WARNING_TINT))
            .padding(horizontal = AppLayout.menuInset, vertical = AppLayout.headingGap),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.ErrorOutline, contentDescription = null, tint = amber, modifier = Modifier.size(20.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PairedContent(look: KatanaDeskSheetLook, desk: KatanaDeskPaired, onSyncNow: () -> Unit, onForget: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
        DeskCard(desk = KatanaDeskFound(name = desk.name, address = desk.address, deskId = desk.deskId), checked = true)
        look.getting?.let { getting ->
            Text(text = gettingText(getting), style = MaterialTheme.typography.bodyMedium)
            val fraction = getting.fraction
            if (fraction != null) AppLinearProgress(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(text = booksOnDeskText(look.books), style = MaterialTheme.typography.bodyMedium)
        look.brought?.let(::broughtText)?.let { line -> Text(text = line, style = MaterialTheme.typography.bodyMedium) }
        val failed = look.problem != null && !look.syncing
        Text(
            text = pairedStatusText(look.syncing, look.problem, look.syncedAt),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppButton(onClick = onSyncNow, enabled = !look.syncing, modifier = Modifier.fillMaxWidth()) {
            if (look.syncing) AppButtonSpinner() else Text("Sync now")
        }
        AppTextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) { Text("Forget this PC") }
    }
}

private const val AMBER = 0xFFE5A04FL
private const val WARNING_TINT = 0.2f
private const val CHECK_TINT = 0.22f
private const val RING_TINT = 0.6f
private val SHEET_GAP = 12.dp
