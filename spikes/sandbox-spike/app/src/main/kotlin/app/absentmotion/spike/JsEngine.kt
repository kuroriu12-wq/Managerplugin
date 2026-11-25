package app.absentmotion.spike

import java.util.concurrent.atomic.AtomicBoolean

data class Outcome(
    val ok: Boolean,
    val value: String,
    val error: String?,
    val ms: Long,
    val instr: Long = -1,
    val line: Int? = null,
)

/** What the script can call back into: ctx.log(text) and ctx.ping(). */
class Host {
    val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    var calls = 0
}

interface JsEngine {
    val name: String

    /** Fresh sandbox per call. Never throws. */
    fun run(src: String, budgetMs: Long, budgetInstr: Long, cancel: AtomicBoolean, host: Host): Outcome
}

fun msSince(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000
