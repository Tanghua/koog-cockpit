package cockpit

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Call(val invocationId: String, val action: String, val argument: String = "", val generation: Int = 1)

@Serializable
data class Receipt(val invocationId: String, val action: String, val status: String, val evidence: String)

/** Mock device only. The log implements host recovery semantics, not a framework feature. */
open class Device(val directory: File) {
    var generation = 1
    var enabled = true
    var navigationFails = false
    var loseMediaReceipt = false
    var mediaDelayMs = 0L
    var playing = false
    val effects = CopyOnWriteArrayList<String>()
    val receipts = CopyOnWriteArrayList<Receipt>()
    private val journal = File(directory, "dispatch.jsonl")
    init { directory.mkdirs() }

    private fun records(): List<Receipt> = if (!journal.exists()) emptyList() else journal.readLines()
        .filter { it.isNotBlank() }.map { Json.decodeFromString<Receipt>(it) }

    private fun append(receipt: Receipt): Receipt {
        FileOutputStream(journal, true).use { out ->
            out.write((Json.encodeToString(receipt) + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        receipts += receipt
        return receipt
    }

    // Every dispatch rechecks the latest host config, including calls proposed before a revocation.
    open suspend fun invoke(call: Call): Receipt {
        if (!enabled || call.generation != generation) return append(Receipt(call.invocationId, call.action, "failed", "CAPABILITY_REVOKED"))
        records().lastOrNull { it.invocationId == call.invocationId }?.let {
            return it.copy(status = if (it.status == "dispatching") "unknown" else it.status)
        }
        require(call.action in setOf("nav", "media", "volume")) { "UNSUPPORTED_TOOL" }
        if (call.action == "volume" && call.argument.toIntOrNull() !in 0..15) {
            return append(Receipt(call.invocationId, call.action, "failed", "INVALID_VOLUME"))
        }
        append(Receipt(call.invocationId, call.action, "dispatching", "intent persisted before device call"))
        if (call.action == "nav" && navigationFails) return append(Receipt(call.invocationId, call.action, "failed", "DESTINATION_NOT_FOUND"))
        if (call.action == "media") {
            try { delay(mediaDelayMs) } catch (e: CancellationException) {
                append(Receipt(call.invocationId, call.action, "cancelled", "cancelled before mock device effect")); throw e
            }
            playing = true
        }
        effects += call.action
        if (call.action == "media" && loseMediaReceipt) return append(Receipt(call.invocationId, call.action, "unknown", "ACK_LOST_DO_NOT_RETRY"))
        return append(Receipt(call.invocationId, call.action, "succeeded", when (call.action) {
            "nav" -> "mock destination accepted: ${call.argument}; navigation not started"
            "media" -> "mock player state=playing; no title available"
            else -> "mock volume=${call.argument}"
        }))
    }

    fun restore(): List<Receipt> = records().groupBy { it.invocationId }.values.map { rows ->
        rows.last().let { if (it.status == "dispatching") it.copy(status = "unknown") else it }
    }
}

interface RuntimeAdapter {
    val name: String
    suspend fun run(calls: List<Call>, device: Device, storage: File): String
    suspend fun verifyNativeRecovery(device: Device, storage: File)
}
