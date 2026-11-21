package com.example.unifiedreader

import android.content.Context
import android.content.SharedPreferences
import com.example.unifiedreader.core.model.MemoryReadStatusStore
import com.example.unifiedreader.core.model.ReadStatusRecord
import com.example.unifiedreader.core.model.ReadStatusStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The reading statuses on this device ([ReadStatusStore]): one preference per book that has a status of its
 * own (`book id` to "PAUSED|1763712000000|1"), read once at start and written as each changes. Kept out of
 * the database, so no migration; a book with no status of its own isn't in it (it shows what its place says).
 */
@Module
@InstallIn(SingletonComponent::class)
object ReadStatusModule {
    @Provides
    @Singleton
    fun provideReadStatusStore(@ApplicationContext context: Context): ReadStatusStore =
        deviceReadStatusStore(context.getSharedPreferences(READ_STATUS_PREFERENCES, Context.MODE_PRIVATE))
}

/** The store over [preferences]: what they hold now, and every change written back (on a background thread, as `apply` does). */
internal fun deviceReadStatusStore(preferences: SharedPreferences): ReadStatusStore = MemoryReadStatusStore(
    initial = preferences.all.mapNotNull { (itemId, value) -> ReadStatusRecord.decode(value as? String)?.let { itemId to it } }.toMap(),
    onChanged = { changes ->
        preferences.edit().apply { changes.forEach { (itemId, record) -> putString(itemId, record.encode()) } }.apply()
    },
)

private const val READ_STATUS_PREFERENCES = "read_statuses"
