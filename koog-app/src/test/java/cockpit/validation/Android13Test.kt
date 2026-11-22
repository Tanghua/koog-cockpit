package cockpit.validation
import android.content.Context
import cockpit.Verification
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Android13Test {
    @Test fun androidMainDispatcherAvailable() {
        kotlinx.coroutines.Dispatchers.Main.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
    }
    @Test fun nativeAndroidRecovery() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "native-recovery")
        cockpit.koog.KoogAdapter().verifyNativeRecovery(cockpit.Device(File(root, "host")), File(root, "runtime"))
    }
    @Test fun simulatedAndroid13Contract() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val adapter = cockpit.koog.KoogAdapter()
        val results = Verification.run(adapter, File(context.filesDir, "android13"))
        results.forEach { println("Android13 ${it.name}: ${it.passed} ${it.detail}") }
        assertTrue(results.toString(), results.all { it.passed })
    }
}
