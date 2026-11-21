package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.journal.ReadingGoal
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppMenu
import com.example.unifiedreader.ui.components.AppMenuChoiceItem
import com.example.unifiedreader.ui.components.AppMenuHeading

/** The daily goal the Journal's calendars colour their days by, and how to change it (null: not from here). */
internal data class JournalReadingGoal(
    val minutes: Int = ReadingGoal.DEFAULT_MINUTES,
    val onChange: ((Int) -> Unit)? = null,
)

internal val LocalJournalReadingGoal = compositionLocalOf { JournalReadingGoal() }

/** A calendar day's step against [goalMinutes]; a day with reading but no minutes counted is the first. */
internal fun journalReadingDayStep(day: JournalActivityDay, goalMinutes: Int): Int =
    ReadingGoal.step(minutes = day.heatValue, goalMinutes = goalMinutes, read = day.heatValue > 0)

/** "Goal 30 min a day ▾" under the calendars' key: a tap lists the goals to pick from. */
@Composable
internal fun JournalGoalPicker(
    goal: JournalReadingGoal,
    textStyle: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val onChange = goal.onChange
    var choosing by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .then(
                    if (onChange != null) {
                        Modifier.clickable(onClickLabel = GoalCopy.Change, role = Role.Button) { choosing = true }
                    } else {
                        Modifier
                    },
                ),
            horizontalArrangement = Arrangement.spacedBy(GoalArrowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = GoalCopy.goal(goal.minutes), style = textStyle, color = color)
            if (onChange != null) {
                Icon(imageVector = AppIcons.KeyboardArrowDown, contentDescription = null, tint = color, modifier = Modifier.size(GoalArrow))
            }
        }
        if (onChange != null) {
            AppMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                AppMenuHeading(text = GoalCopy.Heading)
                ReadingGoal.Choices.forEach { minutes ->
                    AppMenuChoiceItem(
                        text = ReadingGoal.label(minutes),
                        selected = minutes == goal.minutes,
                        onClick = {
                            onChange(minutes)
                            choosing = false
                        },
                    )
                }
            }
        }
    }
}

internal object GoalCopy {
    const val Heading = "Daily reading goal"
    const val Change = "Change the daily goal"

    fun goal(minutes: Int): String = "Goal ${ReadingGoal.label(minutes)}"
}

private val GoalArrow = 16.dp
private val GoalArrowGap = 2.dp
