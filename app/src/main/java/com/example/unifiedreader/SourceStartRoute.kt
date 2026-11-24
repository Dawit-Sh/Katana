package com.example.unifiedreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.settings.RemoteSourceSettingsActions
import com.example.unifiedreader.feature.settings.SourceSettingsEntry
import com.example.unifiedreader.feature.settings.SourceStartFlow
import com.example.unifiedreader.feature.settings.SourceStartMode
import com.example.unifiedreader.feature.settings.direction.ReadingDirectionLibraryUiState
import com.example.unifiedreader.feature.settings.mode.ReaderModeLibraryUiState

/**
 * What opened the start flow: the first launch, "Add a server", or Connect on an empty library,
 * which opens straight on that [server]'s page.
 */
data class SourceStartRequest(
    val mode: SourceStartMode,
    val server: SourceType? = null,
)

/**
 * The start flow's request, kept across configuration changes. A launch with no source set up (no server signed in
 * and no book from this device: a fresh install, or one whose data was cleared) opens on "Where are your books?",
 * once per launch, as soon as the sources are known; how or when the app was installed doesn't matter.
 */
@Composable
internal fun rememberSourceStartRequest(uiState: AppUiState): MutableState<SourceStartRequest?> {
    val request = rememberSaveable(stateSaver = SourceStartRequestSaver) { mutableStateOf<SourceStartRequest?>(null) }
    var decided by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // A book folder picked (or the whole device searched) is a source set up, even before its books have loaded.
    val hasBookPlaces = remember(context) { LocalBookPlaces(context.applicationContext).hasPlaces }
    val noSources = noSourcesSetUp(
        serverSessions = uiState.serverSessions,
        localBooksKnown = SourceType.LOCAL in uiState.hydratedSources,
        hasLocalBooks = hasBookPlaces || uiState.items.any { item -> item.sourceType == SourceType.LOCAL },
    )
    LaunchedEffect(noSources) {
        if (decided || noSources == null) return@LaunchedEffect
        decided = true
        if (noSources && request.value == null) request.value = SourceStartRequest(SourceStartMode.FirstLaunch)
    }
    return request
}

/**
 * Whether no source is set up: true with no server signed in and no book from this device, false as soon as one is,
 * null while that isn't known yet (a server's saved sign-in or the books from this device still loading).
 */
internal fun noSourcesSetUp(
    serverSessions: Map<SourceType, Boolean?>,
    localBooksKnown: Boolean,
    hasLocalBooks: Boolean,
): Boolean? = when {
    hasLocalBooks || serverSessions.values.any { signedIn -> signedIn == true } -> false
    !localBooksKnown || serverSessions.values.any { signedIn -> signedIn == null } -> null
    else -> true
}

/** The start flow over the whole app, while [request] is set. */
@Composable
internal fun SourceStartHost(
    request: SourceStartRequest,
    uiState: AppUiState,
    viewModel: AppViewModel,
    onImportLocalFileClick: () -> Unit,
    onFinished: () -> Unit,
) {
    val finish = onFinished
    val localBooks = LocalLocalBookActions.current
    SourceStartFlow(
        mode = request.mode,
        initialServer = request.server,
        // In the source order, as the rail lists them.
        servers = rememberSourceSettingsEntries(uiState = uiState, viewModel = viewModel)
            .sortedBy { entry -> uiState.sourceOrder.indexOf(entry.sourceType) },
        onImportFile = {
            finish()
            viewModel.openLibrarySource(SourceType.LOCAL)
            onImportLocalFileClick()
        },
        onAddFolder = localBooks?.let { actions ->
            {
                finish()
                viewModel.openLibrarySource(SourceType.LOCAL)
                actions.onAddFolder()
            }
        },
        onFindOnPhone = localBooks?.let { actions ->
            {
                finish()
                viewModel.openLibrarySource(SourceType.LOCAL)
                actions.onFindOnPhone()
            }
        },
        onKatanaDesk = localBooks?.let { actions ->
            {
                finish()
                viewModel.openLibrarySource(SourceType.LOCAL)
                actions.onKatanaDesk()
            }
        },
        onServerConnected = { server ->
            // The first server connected opens when the app starts.
            if (uiState.navigationConnectedServers.none { it != server }) {
                viewModel.reorderSources(listOf(server) + uiState.sourceOrder.filter { it != server })
            }
        },
        onOpenLibrary = { server ->
            finish()
            viewModel.openLibrarySource(server)
        },
        onClose = {
            finish()
            if (request.mode == SourceStartMode.FirstLaunch) viewModel.openLibrarySource(SourceType.LOCAL)
        },
    )
}

/** Each server as Settings and the start flow see it: its connection, its actions, its last sync. */
@Composable
internal fun rememberSourceSettingsEntries(
    uiState: AppUiState,
    viewModel: AppViewModel,
    kavitaReadingDirectionLibraries: List<ReadingDirectionLibraryUiState> = emptyList(),
    stumpReadingDirectionLibraries: List<ReadingDirectionLibraryUiState> = emptyList(),
    kavitaReaderModeLibraries: List<ReaderModeLibraryUiState> = emptyList(),
    stumpReaderModeLibraries: List<ReaderModeLibraryUiState> = emptyList(),
    // The Sign out on each server page in Settings; the start flow has none.
    onSignOut: ((SourceType) -> Unit)? = null,
): List<SourceSettingsEntry> {
    val syncTimes = rememberSourceSyncTimes()
    val kavitaActions = remember(viewModel) {
        RemoteSourceSettingsActions(
            onDisplayNameChanged = viewModel::onKavitaDisplayNameChanged,
            onBaseUrlChanged = viewModel::onKavitaBaseUrlChanged,
            onBackupBaseUrlChanged = viewModel::onKavitaBackupBaseUrlChanged,
            onUsernameChanged = viewModel::onKavitaUsernameChanged,
            onPasswordChanged = viewModel::onKavitaPasswordChanged,
            onConnect = viewModel::connectKavitaServer,
            onSync = viewModel::refreshKavitaLibrary,
            onFastSync = viewModel::fastSyncKavitaLibrary,
            onPreloadCovers = { viewModel.preloadCovers(SourceType.KAVITA) },
            onRetryFailedCovers = { viewModel.retryFailedCovers(SourceType.KAVITA) },
            onCancelCoverPreload = { viewModel.cancelCoverPreload(SourceType.KAVITA) },
            onClearCoverCache = { viewModel.clearCachedCovers(SourceType.KAVITA) },
        )
    }
    val appContext = LocalContext.current.applicationContext
    val grimmoryActions = remember(viewModel, appContext) {
        val finder = GrimmoryWifiFinder(appContext)
        RemoteSourceSettingsActions(
            findOnWifi = finder::find,
            onDisplayNameChanged = viewModel::onGrimmoryDisplayNameChanged,
            onBaseUrlChanged = viewModel::onGrimmoryBaseUrlChanged,
            onBackupBaseUrlChanged = viewModel::onGrimmoryBackupBaseUrlChanged,
            onUsernameChanged = viewModel::onGrimmoryUsernameChanged,
            onPasswordChanged = viewModel::onGrimmoryPasswordChanged,
            onConnect = viewModel::connectGrimmoryServer,
            onSync = viewModel::refreshGrimmoryLibrary,
            onPreloadCovers = { viewModel.preloadCovers(SourceType.GRIMMORY) },
            onRetryFailedCovers = { viewModel.retryFailedCovers(SourceType.GRIMMORY) },
            onCancelCoverPreload = { viewModel.cancelCoverPreload(SourceType.GRIMMORY) },
            onClearCoverCache = { viewModel.clearCachedCovers(SourceType.GRIMMORY) },
        )
    }
    val stumpActions = remember(viewModel) {
        RemoteSourceSettingsActions(
            onDisplayNameChanged = viewModel::onStumpDisplayNameChanged,
            onBaseUrlChanged = viewModel::onStumpBaseUrlChanged,
            onBackupBaseUrlChanged = viewModel::onStumpBackupBaseUrlChanged,
            onUsernameChanged = viewModel::onStumpUsernameChanged,
            onPasswordChanged = viewModel::onStumpPasswordChanged,
            onConnect = viewModel::connectStumpServer,
            onSync = viewModel::refreshStumpLibrary,
            onPreloadCovers = { viewModel.preloadCovers(SourceType.STUMP) },
            onRetryFailedCovers = { viewModel.retryFailedCovers(SourceType.STUMP) },
            onCancelCoverPreload = { viewModel.cancelCoverPreload(SourceType.STUMP) },
            onClearCoverCache = { viewModel.clearCachedCovers(SourceType.STUMP) },
        )
    }
    fun connectivityOf(source: SourceType) = uiState.sourceConnectivityBySource[source] ?: SourceConnectivityState.Unknown
    return listOf(
        SourceSettingsEntry(
            sourceType = SourceType.KAVITA,
            lastSyncedAtEpochMillis = syncTimes[SourceType.KAVITA],
            connection = uiState.kavitaConnection,
            connectivity = connectivityOf(SourceType.KAVITA),
            readingDirectionLibraries = kavitaReadingDirectionLibraries,
            readerModeLibraries = kavitaReaderModeLibraries,
            actions = kavitaActions.withSignOut(onSignOut, SourceType.KAVITA),
        ),
        SourceSettingsEntry(
            sourceType = SourceType.GRIMMORY,
            lastSyncedAtEpochMillis = syncTimes[SourceType.GRIMMORY],
            connection = uiState.grimmoryConnection,
            connectivity = connectivityOf(SourceType.GRIMMORY),
            actions = grimmoryActions.withSignOut(onSignOut, SourceType.GRIMMORY),
        ),
        SourceSettingsEntry(
            sourceType = SourceType.STUMP,
            lastSyncedAtEpochMillis = syncTimes[SourceType.STUMP],
            connection = uiState.stumpConnection,
            connectivity = connectivityOf(SourceType.STUMP),
            readingDirectionLibraries = stumpReadingDirectionLibraries,
            readerModeLibraries = stumpReaderModeLibraries,
            actions = stumpActions.withSignOut(onSignOut, SourceType.STUMP),
        ),
    )
}

private fun RemoteSourceSettingsActions.withSignOut(
    onSignOut: ((SourceType) -> Unit)?,
    source: SourceType,
): RemoteSourceSettingsActions = if (onSignOut == null) this else copy(onSignOut = { onSignOut(source) })

private val SourceStartRequestSaver: Saver<SourceStartRequest?, String> = Saver(
    save = { request -> request?.let { "${it.mode.name}:${it.server?.name.orEmpty()}" } ?: "" },
    restore = { saved ->
        saved.takeIf(String::isNotEmpty)?.let {
            val (mode, server) = it.split(':', limit = 2)
            SourceStartRequest(
                mode = SourceStartMode.valueOf(mode),
                server = server.takeIf(String::isNotEmpty)?.let(SourceType::valueOf),
            )
        }
    },
)
