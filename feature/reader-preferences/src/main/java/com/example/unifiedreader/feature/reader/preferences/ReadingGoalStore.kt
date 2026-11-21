package com.example.unifiedreader.feature.reader.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.unifiedreader.journal.ReadingGoal
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The daily reading goal in minutes ([ReadingGoal]), which colours the reading days in the side menu
 * and the Journal. Set in Settings › Reading and from the Journal's calendar.
 */
interface ReadingGoalStore {
    val goalFlow: StateFlow<Int>

    /** Saves [minutes] in the background; [goalFlow] follows once it is written. */
    fun setGoal(minutes: Int)
}

class DataStoreReadingGoalStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.readingGoalDataStore,
    /** The app scope; saves run on it so they outlive the screen they were made on. */
    private val scope: CoroutineScope,
    private val ioRetryDelayMillis: Long = DATASTORE_IO_RETRY_DELAY_MILLIS,
) : ReadingGoalStore {

    override val goalFlow: StateFlow<Int> = dataStore.data
        .retryWhen { error, _ ->
            if (error is IOException) {
                emit(emptyPreferences())
                delay(ioRetryDelayMillis)
            }
            error is IOException
        }
        .map { preferences -> ReadingGoal.coerce(preferences[GoalMinutesKey]) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ReadingGoal.DEFAULT_MINUTES,
        )

    override fun setGoal(minutes: Int) {
        scope.launch {
            dataStore.edit { preferences -> preferences[GoalMinutesKey] = ReadingGoal.coerce(minutes) }
        }
    }
}

/** For previews and tests that do not persist the goal. */
class InMemoryReadingGoalStore(
    minutes: Int = ReadingGoal.DEFAULT_MINUTES,
) : ReadingGoalStore {
    private val goalState = MutableStateFlow(ReadingGoal.coerce(minutes))
    override val goalFlow: StateFlow<Int> = goalState.asStateFlow()

    override fun setGoal(minutes: Int) {
        goalState.value = ReadingGoal.coerce(minutes)
    }
}

private val Context.readingGoalDataStore: DataStore<Preferences> by preferencesDataStore(
    name = READING_GOAL_DATASTORE_NAME,
)

private val GoalMinutesKey = intPreferencesKey("daily_goal_minutes")

internal const val READING_GOAL_DATASTORE_NAME = "reading_goal_preferences"
