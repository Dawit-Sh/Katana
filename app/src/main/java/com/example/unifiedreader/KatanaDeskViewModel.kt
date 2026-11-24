package com.example.unifiedreader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pairing with Katana Desk, and how its sync stands. */
@HiltViewModel
class KatanaDeskViewModel @Inject constructor(
    private val sync: KatanaDeskSync,
    private val finder: KatanaDeskFinder,
    connection: KatanaDeskConnection,
) : ViewModel() {
    val paired = connection.paired
    val links = connection.links
    val lastSyncedAt = connection.lastSyncedAt
    val syncing = sync.syncing
    val problem = sync.problem
    val lastResult = sync.lastResult

    private val formState = MutableStateFlow(KatanaDeskPairForm())

    /** The pairing screen: desks found, the address, the code, what went wrong. */
    val form: StateFlow<KatanaDeskPairForm> = formState.asStateFlow()

    private var looking: Job? = null

    /** Looks for Katana Desk on this Wi-Fi while the pairing screen shows. */
    fun startLooking() {
        if (looking?.isActive == true) return
        formState.update { it.withFound(it.found, stillLooking = true) }
        looking = viewModelScope.launch {
            finder.look { desks, stillLooking -> formState.update { it.withFound(desks, stillLooking) } }
        }
    }

    fun stopLooking() {
        looking?.cancel()
        looking = null
    }

    fun pick(desk: KatanaDeskFound) = formState.update { it.picked(desk) }

    fun typeAddress() = formState.update { it.openAddress() }

    fun onAddress(text: String) = formState.update { it.addressTyped(text) }

    /** The code as typed; the sixth digit pairs at once when the desk's address is known. */
    fun onCode(text: String) {
        formState.update { it.codeTyped(text) }
        if (formState.value.canPair) pair()
    }

    fun pair() {
        val now = formState.value
        if (!now.canPair) return
        formState.update { it.started() }
        viewModelScope.launch {
            try {
                sync.pair(now.address, now.code)
                formState.update { it.ended() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: KatanaDeskRefused) {
                formState.update { it.refused(refused) }
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                formState.update { it.refused(noAnswerRefusal(now.address).apply { initCause(failure) }) }
            } finally {
                // never left on "Pairing…"
                formState.update { if (it.pairing) it.ended() else it }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch { sync.syncNow() }
    }

    fun forget() {
        sync.forget()
        formState.value = KatanaDeskPairForm()
    }

    override fun onCleared() = stopLooking()
}
