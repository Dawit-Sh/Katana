package com.example.unifiedreader.feature.annotations

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.LocalAppToastState
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

/** Loads the open book's cover for a quote card's background; null when the book has none. */
fun interface QuoteCoverLoader {
    suspend fun load(): Bitmap?
}

/** The open book's cover, for the share card; the app sets it around the reader. Null outside a book. */
val LocalQuoteCover = staticCompositionLocalOf<QuoteCoverLoader?> { null }

/**
 * The pictures a quote card can sit on: the open book's [cover] (null while it loads, or when the
 * book has none) and the [photo] the reader picked (null until one is picked).
 */
@Stable
class QuoteCardPictures internal constructor(
    val cover: Bitmap?,
    val photo: Bitmap?,
    private val pick: () -> Unit,
) {
    /** Opens the photo picker; a picked photo becomes the card's ground. */
    fun pickPhoto() = pick()

    /** The picture [ground] shows, or null for a colour ground (or a picture not loaded yet). */
    fun image(ground: QuoteCardGround): Bitmap? = when (ground) {
        QuoteCardGround.CoverPicture -> cover
        QuoteCardGround.Photo -> photo
        else -> null
    }

    fun backdrop(ground: QuoteCardGround, blurred: Boolean): QuoteCardBackdrop? =
        image(ground)?.let { QuoteCardBackdrop(image = it, blurred = blurred) }
}

/**
 * The cover from [LocalQuoteCover] and a photo from the photo picker (Android's own, or the files
 * app where it has none). [onPhotoPicked] runs as a photo is chosen; a photo that can't be read
 * says so and calls [onPhotoLost].
 */
@Composable
internal fun rememberQuoteCardPictures(onPhotoPicked: () -> Unit, onPhotoLost: () -> Unit): QuoteCardPictures {
    val context = LocalContext.current
    val toasts = LocalAppToastState.current
    val loader = LocalQuoteCover.current
    val cover by produceState<Bitmap?>(initialValue = null, loader) { value = loader?.load() }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picked by rememberUpdatedState(onPhotoPicked)
    val lost by rememberUpdatedState(onPhotoLost)
    val photo by produceState<Bitmap?>(initialValue = null, photoUri) {
        val uri = photoUri
        val loaded = uri?.let { QuoteImages.loadPhoto(context, it) }
        if (uri != null && loaded == null) {
            photoUri = null
            toasts?.show(AppToastMessage(text = QuoteCopy.PhotoFailed, icon = AppToastIcon.Error))
            lost()
        }
        value = loaded
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            photoUri = uri
            picked()
        }
    }
    return remember(cover, photo, launcher) {
        QuoteCardPictures(cover = cover, photo = photo) {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

/** A swatch showing a picture ground: the picture itself, or an image sign until a photo is picked. */
@Composable
internal fun PictureSwatch(
    option: QuoteCardGround,
    image: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corners = RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius)
    val picture = remember(image) { image?.asImageBitmap() }
    Box(
        modifier = modifier
            .size(QuoteSwatchSize)
            .clip(corners)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (selected) Modifier.border(QuoteSwatchRing, MaterialTheme.colorScheme.primary, corners) else Modifier)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = option.label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(bitmap = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(imageVector = AppIcons.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Six in a row on a phone-wide dialog (315 dp inside).
internal val QuoteSwatchSize = 44.dp
internal val QuoteSwatchRing = 3.dp
