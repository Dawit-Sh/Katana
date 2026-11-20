package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceConnectivityState

/**
 * When the open books' marks come down from Grimmory: once per book each time it is opened, as soon as
 * Grimmory isn't marked offline. A book opened while it is (a Wi-Fi drop whose end the app hasn't noticed)
 * checks Grimmory at once, a single time for that opening; when Grimmory answers, every book still open
 * gets its marks without being opened again. Before 1.1.2 such a book got nothing until it was reopened
 * after the next successful check, up to half an hour later.
 */
internal class OpenBookMarksPulls {
    sealed interface Step {
        /** Nothing to do now. */
        data object Wait : Step

        /** Check whether Grimmory answers again; the answer comes back here as its new state. */
        data object CheckServer : Step

        /** Bring these books' marks down. */
        data class Pull(val itemIds: Set<String>) : Step
    }

    private var pulled = emptySet<String>()
    private var checkedFor = emptySet<String>()

    /**
     * The pull of [itemIds] didn't get through (the Wi-Fi dropped as the book opened): they wait again, so
     * they come down when Grimmory answers, without being opened again.
     */
    fun failed(itemIds: Set<String>) {
        pulled = pulled - itemIds
    }

    fun next(openItemIds: Set<String>, grimmory: SourceConnectivityState): Step {
        // A book closed since starts over: opened again, its marks come down again.
        pulled = pulled intersect openItemIds
        checkedFor = checkedFor intersect openItemIds
        val waiting = openItemIds - pulled
        return when {
            waiting.isEmpty() || grimmory == SourceConnectivityState.Checking -> Step.Wait
            grimmory == SourceConnectivityState.Offline -> {
                val newlyOpened = waiting - checkedFor
                checkedFor = checkedFor + newlyOpened
                if (newlyOpened.isEmpty()) Step.Wait else Step.CheckServer
            }
            else -> {
                pulled = pulled + waiting
                Step.Pull(waiting)
            }
        }
    }
}
