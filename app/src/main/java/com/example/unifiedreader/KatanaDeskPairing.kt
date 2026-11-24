package com.example.unifiedreader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The pairing screen of the Katana Desk sheet: looking for the PC on this Wi-Fi, the one picked or the address
 * typed, the code, and what went wrong. Runs in [scope] (the sheet's view model).
 */
class KatanaDeskPairing(
    private val finder: KatanaDeskFinder,
    private val sync: KatanaDeskSync,
    private val scope: CoroutineScope,
) {
    private val formState = MutableStateFlow(KatanaDeskPairForm())

    /** Desks found, the address, the code, what went wrong. */
    val form: StateFlow<KatanaDeskPairForm> = formState.asStateFlow()

    private var looking: Job? = null

    /** Looks for Katana Desk on this Wi-Fi while the pairing screen shows. */
    fun start() {
        if (looking?.isActive == true) return
        formState.update { it.withFound(it.found, stillLooking = true) }
        looking = scope.launch {
            finder.look { desks, stillLooking -> formState.update { it.withFound(desks, stillLooking) } }
        }
    }

    fun stop() {
        looking?.cancel()
        looking = null
    }

    /** Looks again from the start (nothing was found). */
    fun lookAgain() {
        stop()
        formState.update { it.copy(found = emptyList()) }
        start()
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
        scope.launch {
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

    /** Back to an empty screen (the PC was forgotten). */
    fun reset() {
        formState.value = KatanaDeskPairForm()
    }
}
