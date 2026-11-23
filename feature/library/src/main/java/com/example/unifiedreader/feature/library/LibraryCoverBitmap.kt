package com.example.unifiedreader.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale

/**
 * [imageUrl]'s cover as a software bitmap of about 960 × 1440 pixels, for drawing onto a picture
 * (the share card's background), through Coil so a cover already on disk is not fetched again.
 * Null when the cover can't be loaded.
 */
suspend fun loadLibraryCoverBitmap(context: Context, imageUrl: String): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .size(CoverWidth, CoverHeight)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .build()
    return ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
}

private const val CoverWidth = 960
private const val CoverHeight = 1440
