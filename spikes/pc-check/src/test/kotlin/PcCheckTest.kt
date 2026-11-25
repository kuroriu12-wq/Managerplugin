import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PcCheckTest {
    init {
        println("PLATFORM os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")} java=${System.getProperty("java.version")}")
    }

    @Test
    fun evaluates() {
        val v = runBlocking { quickJs { evaluate<Int>("1 + 2") } }
        assertEquals(3, v)
    }

    @Test
    fun hostFunctionAndFreeze() {
        val seen = mutableListOf<String>()
        val v = runBlocking {
            quickJs {
                define("ctx") { function("log") { a -> seen.add(a.firstOrNull().toString()); null } }
                evaluate<Any?>("Object.freeze(ctx);")
                evaluate<String>("try { ctx.log = 1 } catch (e) {} ; ctx.log('x'); typeof ctx.log")
            }
        }
        assertEquals("function", v)
        assertEquals(listOf("x"), seen)
    }

    @Test
    fun infiniteLoopStops() {
        val t0 = System.nanoTime()
        try {
            runBlocking { quickJs { evaluationTimeoutMillis = 1000; evaluate<Any?>("while (true) {}") } }
            fail("did not stop")
        } catch (e: Throwable) {
            println("LOOP stopped: ${e.javaClass.name} after ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 4000)
    }

    @Test
    fun memoryLimitHolds() {
        try {
            runBlocking { quickJs { memoryLimit = 64L * 1024 * 1024; evaluate<Any?>("var s = 'x'.repeat(1e9); s.length") } }
            fail("1 GB string allowed")
        } catch (e: Throwable) {
            println("MEMORY refused: ${e.javaClass.name}: ${e.message?.take(100)}")
        }
    }
}
