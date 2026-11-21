package com.example.unifiedreader.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The reading statuses kept on this device, by book (item id). A book with none shows what its place says. */
interface ReadStatusStore {
    val records: StateFlow<Map<String, ReadStatusRecord>>

    fun get(itemId: String): ReadStatusRecord? = records.value[itemId]

    fun put(itemId: String, record: ReadStatusRecord) = putAll(mapOf(itemId to record))

    fun putAll(changes: Map<String, ReadStatusRecord>)

    /**
     * Replaces [itemId]'s record with [replacement] only while it is still [expected] (null: while it has none);
     * false when it changed meanwhile.
     */
    fun replaceIfCurrent(itemId: String, expected: ReadStatusRecord?, replacement: ReadStatusRecord): Boolean
}

/**
 * A [ReadStatusStore] in memory, starting from [initial]; [onChanged] gets every change, in order, to keep it
 * (the app's device store writes them to its preferences). It must be quick: it runs under the store's lock.
 */
class MemoryReadStatusStore(
    initial: Map<String, ReadStatusRecord> = emptyMap(),
    private val onChanged: (Map<String, ReadStatusRecord>) -> Unit = {},
) : ReadStatusStore {
    private val state = MutableStateFlow(initial)
    private val lock = Any()

    override val records: StateFlow<Map<String, ReadStatusRecord>> = state.asStateFlow()

    override fun putAll(changes: Map<String, ReadStatusRecord>) = synchronized(lock) {
        val current = state.value
        val changed = changes.filter { (itemId, record) -> current[itemId] != record }
        if (changed.isNotEmpty()) {
            state.value = current + changed
            onChanged(changed)
        }
    }

    override fun replaceIfCurrent(itemId: String, expected: ReadStatusRecord?, replacement: ReadStatusRecord): Boolean =
        synchronized(lock) {
            val current = state.value
            val matches = current[itemId] == expected
            if (matches && expected != replacement) {
                state.value = current + (itemId to replacement)
                onChanged(mapOf(itemId to replacement))
            }
            matches
        }
}
