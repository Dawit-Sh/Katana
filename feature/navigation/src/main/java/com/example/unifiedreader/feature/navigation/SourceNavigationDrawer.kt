package com.example.unifiedreader.feature.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.displayName
import com.example.unifiedreader.ui.components.AppDrawerRow
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppWindowHeight
import com.example.unifiedreader.ui.components.appCount
import com.example.unifiedreader.ui.components.appGlass
import com.example.unifiedreader.ui.components.currentAppWindowHeight
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

sealed interface SourceNavigationDestination {
    data class Source(
        val sourceType: SourceType,
    ) : SourceNavigationDestination

    data object Settings : SourceNavigationDestination
    data object Journal : SourceNavigationDestination

    /** The user's lists and reading orders, from every source. */
    data object Lists : SourceNavigationDestination

    /** Every book's highlights, bookmarks and saved words. */
    data object Bookmarks : SourceNavigationDestination

    /** Every saved word, with the sentence it was met in. */
    data object MyWords : SourceNavigationDestination

    /** "Add a server": the choices the first launch offers. */
    data object AddSource : SourceNavigationDestination
}

/**
 * The side menu, top to bottom: Katana's name with the Settings gear, the [readingCard], the sources with
 * their book counts ([sourceCounts]) and "Add a server", Your reading (Lists, Bookmarks, My words with its
 * count, Journal), and the [wordCard] at the bottom. It never scrolls: on a short window the reading card's
 * heat map gets smaller first, then the word card loses its meaning, then the word card goes (see
 * [fitDrawer]); a phone on its side puts Sources and Your reading side by side, in slightly tighter rows.
 */
@Composable
fun SourceNavigationDrawer(
    drawerState: DrawerState,
    sourceOrder: List<SourceType>,
    selectedDestination: SourceNavigationDestination,
    onDestinationSelected: (SourceNavigationDestination) -> Unit,
    /** A dot on the Settings gear, such as the version of a waiting update. */
    settingsBadge: String? = null,
    /** "Add a server" under the sources, while a server is not connected. */
    showAddSource: Boolean = false,
    /** False while the rail shows: then nothing opens the drawer, not even a swipe. */
    gesturesEnabled: Boolean = true,
    /** The reading card, under the name. */
    readingCard: (@Composable () -> Unit)? = null,
    /** The word of the day, at the bottom; null while there is none. */
    wordCard: (@Composable () -> Unit)? = null,
    /** How many words are saved, at the end of the My words row; null leaves the row out. */
    myWordsCount: Int? = null,
    /** How many books each source holds, at the end of its row; a source left out shows none. */
    sourceCounts: Map<SourceType, Int> = emptyMap(),
    /** Katana's mark before its name. */
    logo: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val presentation = UnifiedReaderThemeTokens.presentation
    val drawerShape = RoundedCornerShape(presentation.cardCornerRadius)
    // At any text size: the menu's fit (DrawerFitLayout) shrinks the card or leaves it out only when it can't fit.
    val shownReadingCard = readingCard.takeIf { drawerShowsReadingCard(LocalDensity.current.fontScale) }
    // A phone on its side has no height for the rows one under another: Sources and Your reading go side by side.
    val sideBySide = currentAppWindowHeight() == AppWindowHeight.Compact
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            // A floating glass panel, inset from the screen edges and the system bars. Given the
            // drawer state, the sheet closes on back and follows the predictive back gesture.
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    )
                    .padding(start = DrawerStartMargin, top = DrawerEdgeMargin, bottom = DrawerEdgeMargin)
                    .widthIn(
                        max = drawerPanelMaxWidth(
                            hasReadingCard = shownReadingCard != null,
                            windowWidth = LocalConfiguration.current.screenWidthDp.dp,
                            sideBySide = sideBySide,
                        ),
                    )
                    .appGlass(shape = drawerShape),
                drawerShape = drawerShape,
                drawerContainerColor = Color.Transparent,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                DrawerMenu(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = DrawerInset, end = DrawerInset, top = DrawerTopInset, bottom = DrawerInset),
                    header = {
                        KatanaDrawerHeader(
                            settingsSelected = selectedDestination == SourceNavigationDestination.Settings,
                            onOpenSettings = { onDestinationSelected(SourceNavigationDestination.Settings) },
                            settingsBadge = settingsBadge,
                            logo = logo,
                        )
                    },
                    readingCard = shownReadingCard,
                    wordCard = wordCard,
                ) {
                    DrawerDestinations(
                        sideBySide = sideBySide,
                        sourceOrder = sourceOrder,
                        showAddSource = showAddSource,
                        myWordsCount = myWordsCount,
                        sourceCounts = sourceCounts,
                        selectedDestination = selectedDestination,
                        onDestinationSelected = onDestinationSelected,
                    )
                }
            }
        },
        content = content,
    )
}

/**
 * The menu inside the panel, laid out to its height by [DrawerFitLayout]. Only where even its least
 * (no cards, the rows side by side on a phone held sideways) is taller than the window, at the largest
 * text sizes, can it scroll; at every other size there is nothing to scroll.
 */
@Composable
private fun DrawerMenu(
    header: @Composable () -> Unit,
    readingCard: (@Composable () -> Unit)?,
    wordCard: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    rows: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val room = constraints.maxHeight
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            DrawerFitLayout(
                room = room,
                header = header,
                rows = rows,
                readingCard = readingCard,
                wordCard = wordCard,
            )
        }
    }
}

/** The menu's rows: Sources (each with its book count) and Add a server, then Your reading. */
@Composable
private fun DrawerDestinations(
    sideBySide: Boolean,
    sourceOrder: List<SourceType>,
    showAddSource: Boolean,
    myWordsCount: Int?,
    sourceCounts: Map<SourceType, Int>,
    selectedDestination: SourceNavigationDestination,
    onDestinationSelected: (SourceNavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun Destination(destination: SourceNavigationDestination, label: String, icon: ImageVector, count: Int? = null) {
        DrawerDestination(
            destination = destination,
            label = label,
            icon = icon,
            selectedDestination = selectedDestination,
            onDestinationSelected = onDestinationSelected,
            count = count,
            dense = sideBySide,
        )
    }
    DrawerRows(
        sideBySide = sideBySide,
        modifier = modifier,
        sources = {
            DrawerSectionLabel(text = "Sources")
            sourceOrder.forEach { sourceType ->
                Destination(
                    destination = SourceNavigationDestination.Source(sourceType),
                    label = sourceType.displayName(),
                    icon = sourceType.navigationIcon(),
                    count = sourceCounts[sourceType],
                )
            }
            if (showAddSource) Destination(SourceNavigationDestination.AddSource, "Add a server", AppIcons.Add)
        },
        reading = {
            DrawerSectionLabel(text = "Your reading")
            Destination(SourceNavigationDestination.Lists, "Lists", AppIcons.Checklist)
            Destination(SourceNavigationDestination.Bookmarks, "Bookmarks", AppIcons.Bookmark)
            if (myWordsCount != null) Destination(SourceNavigationDestination.MyWords, MyWordsLabel, AppIcons.Style, myWordsCount)
            Destination(SourceNavigationDestination.Journal, "Journal", AppIcons.History)
        },
    )
}

/** Sources, then Your reading: one under the other, or side by side ([sideBySide]). */
@Composable
private fun DrawerRows(
    sideBySide: Boolean,
    sources: @Composable ColumnScope.() -> Unit,
    reading: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sideBySide) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SideBySideGap)) {
            Column(modifier = Modifier.weight(1f), content = sources)
            Column(modifier = Modifier.weight(1f), content = reading)
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            sources()
            reading()
        }
    }
}

/** "Sources", "Your reading": a small muted label over the rows. */
@Composable
private fun DrawerSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .padding(start = SectionLabelStart, end = SectionLabelStart, top = SectionLabelTop, bottom = SectionLabelBottom)
            .semantics { heading() },
    )
}

@Composable
private fun DrawerDestination(
    destination: SourceNavigationDestination,
    label: String,
    icon: ImageVector,
    selectedDestination: SourceNavigationDestination,
    onDestinationSelected: (SourceNavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    dense: Boolean = false,
) {
    AppDrawerRow(
        label = label,
        icon = icon,
        selected = selectedDestination == destination,
        onClick = { onDestinationSelected(destination) },
        count = count?.let(::appCount),
        dense = dense,
        modifier = modifier,
    )
}

/** The My words row's name, in the drawer and on the rail. */
internal const val MyWordsLabel = "My words"

/**
 * The reading card sits in the side menu at every text size Android offers (to 200%): its lines end in "…"
 * rather than wrap, and the menu's fit shrinks its heat map or leaves it out only when the height runs out.
 * (Before 1.2.4 it left at 150%, though the menu then stood half empty.)
 */
internal fun drawerShowsReadingCard(fontScale: Float): Boolean = fontScale <= DrawerLargestTextScale

private const val DrawerLargestTextScale = 2f

private val DrawerMaxWidth = 320.dp
private val DrawerWithCardMaxWidth = 360.dp
private val DrawerSideBySideMaxWidth = 360.dp

/** The page left showing beside the drawer on a narrow phone, enough to see it is a panel and to tap beside it. */
private val DrawerPagePeek = 24.dp

/** The panel's margins from the window. */
private val DrawerStartMargin = 12.dp
private val DrawerEdgeMargin = 12.dp

/** Inside the panel: 12 at the sides and the foot, 10 over the name. */
private val DrawerInset = 12.dp
private val DrawerTopInset = 10.dp

/** Between Sources and Your reading side by side: the panel is no wider than 360 (Material's drawer). */
private val SideBySideGap = 4.dp

/** The section labels' room: 14 above, 4 under, in line with the rows' text. */
private val SectionLabelStart = 12.dp
private val SectionLabelTop = 14.dp
private val SectionLabelBottom = 4.dp

/**
 * The drawer panel's widest: 360 dp with the reading card or with the rows side by side (Material's
 * drawer is never wider), 320 otherwise, and never closer than 24 dp to the far edge.
 */
internal fun drawerPanelMaxWidth(hasReadingCard: Boolean, windowWidth: Dp, sideBySide: Boolean = false): Dp =
    minOf(
        when {
            sideBySide -> DrawerSideBySideMaxWidth
            hasReadingCard -> DrawerWithCardMaxWidth
            else -> DrawerMaxWidth
        },
        windowWidth - DrawerStartMargin - DrawerPagePeek,
    )

@Composable
fun SourceType.navigationIcon(): ImageVector = when (this) {
    SourceType.KAVITA -> AppIcons.CollectionsBookmark
    SourceType.STUMP -> AppIcons.MenuBook
    SourceType.GRIMMORY -> AppIcons.Cloud
    SourceType.LOCAL -> AppIcons.Folder
}
