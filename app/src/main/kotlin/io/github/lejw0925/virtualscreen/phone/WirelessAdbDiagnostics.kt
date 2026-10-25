package io.github.lejw0925.virtualscreen.phone

/** Diagnostic metadata only. Never render Throwable.toString/message, arguments, or stack locals. */
internal object WirelessAdbDiagnostics {
    fun pairingError(failure: Throwable): String? {
        if (failure !is java.io.IOException || failure.stackTrace.none {
                it.className == "io.github.muntashirakon.adb.PairingConnectionCtx"
            }) return null
        // These are the exact literals in the pinned libadb-android 3.1.1 AAR.
        return when (failure.message) {
            "Exchanging message wasn't successful." -> "PAIRING_SPAKE2_EXCHANGE_FAILED"
            "Could not exchange peer info." -> "PAIRING_PEER_INFO_FAILED"
            "Unable to create PairingAuthCtx." -> "PAIRING_SPAKE2_INIT_FAILED"
            "Connection is not ready yet." -> "PAIRING_STATE_NOT_READY"
            "Connection closed with errors." -> "PAIRING_CONNECTION_CLOSED"
            else -> null
        }
    }

    fun describe(phase: String, failure: Throwable): String {
        val causes = generateSequence(failure) { it.cause }.take(5).toList()
        val stage = if (phase == "PAIRING_PROTOCOL") pairingStage(causes) else phase
        return buildString {
            append("phase=").append(stage)
            causes.forEachIndexed { index, cause ->
                append("\ncause[").append(index).append("]=").append(cause.javaClass.name)
                cause.stackTrace.take(16).forEach { frame ->
                    append("\n at ").append(frame.className).append('.').append(frame.methodName)
                        .append(':').append(frame.lineNumber)
                }
            }
        }
    }

    private fun pairingStage(causes: List<Throwable>): String {
        val frames = causes.flatMap { it.stackTrace.toList() }
            .filter { it.className == "io.github.muntashirakon.adb.PairingConnectionCtx" }
        // Fixed library literals are used only as selectors; no exception text is ever emitted.
        return when {
            frames.any { it.methodName == "exportKeyingMaterial" } -> "PAIRING_TLS_EXPORT"
            frames.any { it.methodName == "doExchangePeerInfo" } ||
                causes.any { it.message == "Could not exchange peer info." } -> "PAIRING_PEER_INFO"
            frames.any { it.methodName == "doExchangeMsgs" } ||
                causes.any { it.message == "Exchanging message wasn't successful." } -> "PAIRING_SPAKE2_EXCHANGE"
            causes.any { it.message == "Unable to create PairingAuthCtx." } -> "PAIRING_SPAKE2_INIT"
            frames.any { it.methodName == "setupTlsConnection" } -> "PAIRING_TCP_OR_TLS"
            else -> "PAIRING_PROTOCOL"
        }
    }
}
