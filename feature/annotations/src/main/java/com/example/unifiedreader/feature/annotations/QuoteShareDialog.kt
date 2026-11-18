package com.example.unifiedreader.feature.annotations

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppButtonSpinner
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppDialogCard
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppTonalButton
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.LocalAppToastState
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The grounds a quote card can have; [Cover] takes the open book's cover colours. */
enum class QuoteCardGround(val label: String) {
    Paper("Paper card"),
    Dusk("Dusk card"),
    Teal("Teal card"),
    Cover("Book cover colours"),
}

/**
 * Share a highlight as an image: a preview of the card, its ground (paper, dusk, teal or the
 * book's cover colours) and shape, then Share (Android's share sheet) or Save image (Pictures/Katana).
 */
@Composable
fun QuoteShareDialog(highlight: ReaderHighlight, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var ground by rememberSaveable { mutableStateOf(QuoteCardGround.Paper) }
    var shape by rememberSaveable { mutableStateOf(QuoteCardShape.Square) }
    val coverColors = coverCardColors()
    val card = rememberQuoteCard(highlight, ground.colors(coverColors), shape)
    val fileName = remember(highlight) { QuoteImages.fileName(highlight.book.title, System.currentTimeMillis()) }
    AppDialogCard(
        onDismissRequest = onDismiss,
        title = QuoteCopy.Title,
        modifier = modifier,
        actions = { QuoteShareActions(card = card, fileName = fileName) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppLayout.gridGap)) {
            CardPreview(card = card, shape = shape)
            Text(text = QuoteCopy.Card, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuoteCardGround.entries.forEach { option ->
                    GroundSwatch(
                        option = option,
                        colors = option.colors(coverColors),
                        selected = ground == option,
                        onClick = { ground = option },
                    )
                }
                Spacer(Modifier.weight(1f))
                AppChip(
                    selected = false,
                    onClick = { shape = if (shape == QuoteCardShape.Square) QuoteCardShape.Tall else QuoteCardShape.Square },
                    label = { Text(if (shape == QuoteCardShape.Square) QuoteCopy.Square else QuoteCopy.Tall) },
                )
            }
        }
    }
}

/** The card for [highlight] in [colors] and [shape], drawn off the main thread; null while it is drawn. */
@Composable
private fun rememberQuoteCard(highlight: ReaderHighlight, colors: QuoteCardColors, shape: QuoteCardShape): Bitmap? {
    val type = rememberQuoteCardType()
    val text = remember(highlight) {
        QuoteCardText(quote = highlight.text, title = highlight.book.title, author = highlight.book.author)
    }
    val card by produceState<Bitmap?>(initialValue = null, text, colors, shape, type) {
        value = withContext(Dispatchers.Default) {
            QuoteCardRenderer.render(text = text, colors = colors, type = type, shape = shape)
        }
    }
    return card
}

/** Save image (asking for storage first on Android 9) and Share. */
@Composable
private fun QuoteShareActions(card: Bitmap?, fileName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toasts = LocalAppToastState.current
    var busy by remember { mutableStateOf(false) }
    fun save(bitmap: Bitmap) {
        busy = true
        scope.launch {
            val saved = QuoteImages.save(context, bitmap, fileName)
            busy = false
            toasts?.show(
                if (saved) {
                    AppToastMessage(text = QuoteCopy.Saved, icon = AppToastIcon.Done)
                } else {
                    AppToastMessage(text = QuoteCopy.SaveFailed, icon = AppToastIcon.Error)
                },
            )
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            !granted -> toasts?.show(AppToastMessage(text = QuoteCopy.NeedsPermission, icon = AppToastIcon.Error))
            card != null -> save(card)
        }
    }
    AppTonalButton(
        enabled = card != null && !busy,
        onClick = {
            if (card == null) return@AppTonalButton
            if (QuoteImages.needsPermission(context)) permission.launch(QuoteImages.PERMISSION) else save(card)
        },
    ) {
        if (busy) {
            AppButtonSpinner()
        } else {
            Icon(imageVector = AppIcons.Download, contentDescription = null, modifier = Modifier.size(SmallIcon))
        }
        Spacer(Modifier.width(AppLayout.headingGap))
        Text(QuoteCopy.Save)
    }
    AppButton(
        enabled = card != null,
        onClick = {
            if (card == null) return@AppButton
            scope.launch {
                if (!QuoteImages.share(context, card, fileName, QuoteCopy.Title)) {
                    toasts?.show(AppToastMessage(text = QuoteCopy.ShareFailed, icon = AppToastIcon.Error))
                }
            }
        },
    ) {
        Icon(imageVector = AppIcons.Share, contentDescription = null, modifier = Modifier.size(SmallIcon))
        Spacer(Modifier.width(AppLayout.headingGap))
        Text(QuoteCopy.Share)
    }
}

@Composable
private fun CardPreview(card: Bitmap?, shape: QuoteCardShape) {
    val corners = RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(shape.width.toFloat() / shape.height)
            .clip(corners)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (card == null) {
            AppButtonSpinner()
        } else {
            Image(bitmap = card.asImageBitmap(), contentDescription = QuoteCopy.Preview, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GroundSwatch(option: QuoteCardGround, colors: QuoteCardColors, selected: Boolean, onClick: () -> Unit) {
    val corners = RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius)
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .clip(corners)
            .background(Color(colors.ground))
            .then(if (selected) Modifier.border(SwatchRing, MaterialTheme.colorScheme.primary, corners) else Modifier)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = option.label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        // A dot of the card's accent, so the swatch shows the pair.
        Box(modifier = Modifier.size(SwatchDot).clip(corners).background(Color(colors.accent)))
    }
}

/** The open book's cover colours as a card (inside the reader the theme carries them). */
@Composable
private fun coverCardColors(): QuoteCardColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary) {
        QuoteCardColors(
            ground = scheme.primaryContainer.toArgb(),
            ink = scheme.onPrimaryContainer.toArgb(),
            secondary = scheme.onPrimaryContainer.copy(alpha = SECONDARY_ALPHA).compositeOver(scheme.primaryContainer).toArgb(),
            accent = scheme.primary.toArgb(),
        )
    }
}

/** Literata for the quote; the app's own face, bold and regular, for the captions. */
@Composable
private fun rememberQuoteCardType(): QuoteCardType {
    val context = LocalContext.current
    val resolver = LocalFontFamilyResolver.current
    val family = MaterialTheme.typography.titleMedium.fontFamily
    return remember(context, resolver, family) {
        fun ui(weight: FontWeight, fallback: Typeface): Typeface =
            family?.let { resolver.resolve(it, weight).value as? Typeface } ?: fallback
        QuoteCardType(
            quote = context.quoteTypeface(italic = true),
            mark = context.quoteTypeface(italic = false, bold = true),
            captionBold = ui(FontWeight.Bold, Typeface.DEFAULT_BOLD),
            caption = ui(FontWeight.Normal, Typeface.DEFAULT),
        )
    }
}

internal fun QuoteCardGround.colors(cover: QuoteCardColors): QuoteCardColors = when (this) {
    QuoteCardGround.Paper -> PaperCard
    QuoteCardGround.Dusk -> DuskCard
    QuoteCardGround.Teal -> TealCard
    QuoteCardGround.Cover -> cover
}

private val PaperCard = QuoteCardColors(
    ground = 0xFFF3EFE4.toInt(),
    ink = 0xFF2A2620.toInt(),
    secondary = 0xFF6B655A.toInt(),
    accent = 0xFF3F7A82.toInt(),
)
private val DuskCard = QuoteCardColors(
    ground = 0xFF2B2F36.toInt(),
    ink = 0xFFE8EAED.toInt(),
    secondary = 0xFFA9AFB7.toInt(),
    accent = 0xFF63A7B0.toInt(),
)
private val TealCard = QuoteCardColors(
    ground = 0xFF2E4E54.toInt(),
    ink = 0xFFF4F8F8.toInt(),
    secondary = 0xFFC3D9DC.toInt(),
    accent = 0xFFA7DDE2.toInt(),
)

/** What the share dialog says. */
object QuoteCopy {
    const val Title = "Share as an image"
    const val Card = "Card"
    const val Square = "Square"
    const val Tall = "Tall"
    const val Share = "Share"
    const val Save = "Save image"
    const val Saved = "Saved to Pictures/Katana"
    const val SaveFailed = "Couldn't save the image"
    const val ShareFailed = "Nothing here can share images"
    const val NeedsPermission = "Katana needs storage access to save the image"
    const val Preview = "Quote card preview"
}

private const val SECONDARY_ALPHA = 0.72f
private val SwatchSize = 48.dp
private val SwatchDot = 14.dp
private val SwatchRing = 3.dp
