package com.example.unifiedreader

/**
 * Where Back goes from a top-level screen, once the screen itself has nothing left to close (a
 * settings page, an open list, a book's marks, a sheet or the menu go first): Lists, Bookmarks, My words,
 * Journal and Settings step back to the library they were opened over, the same server's, since
 * picking them in the menu keeps the library's source. The library has nowhere to go: Back there
 * leaves the app.
 */
internal fun topLevelBackTarget(topLevel: TopLevelDestination): TopLevelDestination? = when (topLevel) {
    TopLevelDestination.Library -> null
    TopLevelDestination.Lists,
    TopLevelDestination.Bookmarks,
    TopLevelDestination.MyWords,
    TopLevelDestination.Journal,
    TopLevelDestination.Settings,
    -> TopLevelDestination.Library
}
