package cockpit

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable

@Serializable
data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/** One shared adversarial suite exercises actual framework loops with deterministic model proposals. */
object Verification {
    suspend fun runWithRecovery(adapter: RuntimeAdapter, root: File): List<CheckResult> {
        val results = run(adapter, root).toMutableList()
        val dir = File(root, "native-${System.nanoTime()}")
        try {
            withTimeout(30_000) { adapter.verifyNativeRecovery(Device(File(dir, "host")), File(dir, "runtime")) }
            results += CheckResult("native_recovery", true, "PASS")
        } catch (e: Exception) {
            results += CheckResult("native_recovery", false, "${e.javaClass.simpleName}: ${e.message}")
        }
        return results
    }
    suspend fun run(adapter: RuntimeAdapter, root: File): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        suspend fun case(name: String, block: suspend (Device, File) -> Unit) {
            val dir = File(root, "$name-${System.nanoTime()}")
            val device = Device(File(dir, "host"))
            try { withTimeout(20_000) { block(device, File(dir, "runtime")) }; results += CheckResult(name, true, "PASS") }
            catch (e: Exception) { results += CheckResult(name, false, "${e.javaClass.simpleName}: ${e.message}") }
        }
        case("commute") { d, s ->
            adapter.run(listOf(Call("n1", "nav", "home"), Call("m1", "media")), d, s)
            check(d.effects == listOf("nav", "media")) { "expected both mock effects: ${d.effects}" }
            check(d.restore().all { it.status == "succeeded" })
        }
        case("partial_failure") { d, s ->
            d.navigationFails = true
            adapter.run(listOf(Call("n1", "nav", "home"), Call("m1", "media")), d, s)
            check(d.effects == listOf("media"))
            check(d.restore().map { it.status } == listOf("failed", "succeeded"))
        }
        case("revoked_stale_plan") { d, s ->
            d.generation = 2
            adapter.run(listOf(Call("m1", "media", generation = 1)), d, s)
            check(d.effects.isEmpty())
            check(d.restore().single().evidence == "CAPABILITY_REVOKED")
        }
        case("duplicate_invocation") { d, s ->
            adapter.run(listOf(Call("m1", "media"), Call("m1", "media")), d, s)
            check(d.effects == listOf("media"))
        }
        case("lost_ack_restart") { d, s ->
            d.loseMediaReceipt = true
            adapter.run(listOf(Call("m1", "media")), d, s)
            check(d.playing && d.restore().single().status == "unknown")
            val restarted = Device(d.directory)
            adapter.run(listOf(Call("m1", "media")), restarted, File(s.parentFile, "fresh-runtime"))
            check(restarted.effects.isEmpty()) { "unknown operation must not be replayed" }
            check(restarted.restore().single().status == "unknown")
        }
        case("cancel_before_effect") { d, s ->
            d.mediaDelayMs = 10_000
            coroutineScope {
                val job = launch { adapter.run(listOf(Call("m1", "media")), d, s) }
                withTimeout(8_000) { while (d.receipts.none { it.status == "dispatching" }) delay(10) }
                job.cancelAndJoin()
            }
            check(d.effects.isEmpty())
            check(d.restore().single().status == "cancelled")
        }
        case("invalid_arguments") { d, s ->
            adapter.run(listOf(Call("v1", "volume", "99")), d, s)
            check(d.effects.isEmpty())
            check(d.restore().single().evidence == "INVALID_VOLUME")
        }
        return results
    }
}
