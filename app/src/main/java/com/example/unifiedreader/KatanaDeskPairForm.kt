package com.example.unifiedreader

/**
 * The pairing screen's state, apart from how it looks: the desks found on this Wi-Fi, the address (filled in from
 * what was found; typed only when nothing was), the code, and what went wrong. Pairing starts by itself once the
 * sixth digit is typed and an address is known, so the only thing to type is the code.
 */
data class KatanaDeskPairForm(
    val found: List<KatanaDeskFound> = emptyList(),
    /** The first look over the network is still going */
    val looking: Boolean = true,
    val address: String = "",
    /** The address was typed by hand (then what's found doesn't replace it) */
    val typed: Boolean = false,
    /** The address field is open: asked for, or nothing was found */
    val typing: Boolean = false,
    val code: String = "",
    val pairing: Boolean = false,
    val problem: KatanaDeskRefused? = null,
) {
    val codeComplete: Boolean get() = code.length == KATANA_DESK_CODE_DIGITS
    val canPair: Boolean get() = address.isNotBlank() && codeComplete && !pairing

    /** Nothing found once the look is over: the address is typed instead. */
    val nothingFound: Boolean get() = !looking && found.isEmpty()
    val showsAddressField: Boolean get() = typing || nothingFound

    /** The desk the address belongs to, when it was found. */
    val desk: KatanaDeskFound? get() = found.firstOrNull { it.address == address }

    /** What the network look found so far: the first desk's address is taken unless one was typed or picked. */
    fun withFound(desks: List<KatanaDeskFound>, stillLooking: Boolean): KatanaDeskPairForm {
        val keep = typed || desks.any { it.address == address }
        return copy(found = desks, looking = stillLooking, address = if (keep) address else desks.firstOrNull()?.address ?: address)
    }

    fun picked(desk: KatanaDeskFound) = copy(address = desk.address, typed = false, typing = false, problem = null)

    fun addressTyped(text: String) = copy(address = text.trim(), typed = text.isNotBlank(), typing = true, problem = null)

    fun openAddress() = copy(typing = true)

    /** Digits only, six at most; a new code clears the last refusal. */
    fun codeTyped(text: String) = copy(code = text.filter(Char::isDigit).take(KATANA_DESK_CODE_DIGITS), problem = null)

    fun started() = copy(pairing = true, problem = null)

    /** Pairing was refused: say why, and empty the code when it's no use any more (ran out, wrong, none showing). */
    fun refused(refusal: KatanaDeskRefused) = copy(
        pairing = false,
        problem = refusal,
        code = if (refusal.refusal.needsNewCode) "" else code,
        // no desk at that address: let the address be checked or typed
        typing = typing || refusal.refusal == KatanaDeskPairRefusal.NoAnswer,
    )

    fun ended() = copy(pairing = false)
}

/** The pairing code's length on Katana Desk's screen. */
const val KATANA_DESK_CODE_DIGITS = 6
