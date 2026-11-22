package cockpit.koog
import cockpit.Verification
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertTrue
class KoogTest {
    @Test fun nativeCheckpointRecovery() = runBlocking {
        val root = Files.createTempDirectory("koog-native-recovery").toFile()
        KoogAdapter().verifyNativeRecovery(cockpit.Device(java.io.File(root, "host")), java.io.File(root, "native"))
    }
    @Test fun cockpitContract() = runBlocking {
        val results = Verification.run(KoogAdapter(), Files.createTempDirectory("koog-verification").toFile())
        results.forEach { println("${it.name}: ${it.passed} ${it.detail}") }
        assertTrue(results.toString(), results.all { it.passed })
    }
}
