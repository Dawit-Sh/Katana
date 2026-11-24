package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppLayout

/**
 * What is done to a picture background (the book's cover or the reader's own photo) before the
 * quote goes on it: [Blur] by default, [None] for the picture as it is. Katana Desk draws the same
 * effects the same way (projects/katana/2026-11-24-card-effects.md); [QuoteCardEffects] has the maths.
 */
enum class QuoteCardEffect(val label: String) {
    None("None"),
    Blur("Blur"),
    Dither("Dither"),
    Halftone("Halftone"),
    Grain("Grain"),
    Mono("Mono"),
    Duotone("Duotone"),
    Pixel("Pixel"),
    ;

    /** Every effect but [None] quiets the picture, so today's lighter scrim and foot shade are enough under the quote. */
    internal val quiets: Boolean get() = this != None
}

/** The effect chips for a picture card, one effect at a time, under their "Effect" label; they wrap on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuoteEffectRow(effect: QuoteCardEffect, onEffect: (QuoteCardEffect) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AppLayout.gridGap)) {
        Text(text = QuoteCopy.Effect, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            QuoteCardEffect.entries.forEach { option ->
                AppChip(selected = effect == option, onClick = { onEffect(option) }, label = { Text(option.label) })
            }
        }
    }
}
