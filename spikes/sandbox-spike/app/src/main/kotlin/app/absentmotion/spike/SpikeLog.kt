package app.absentmotion.spike

import android.content.Context
import java.io.File

/** Log that is also appended to a file, so a crash does not lose the lines written before it. */
object SpikeLog {
    private val sb = StringBuilder()
    private var file: File? = null

    @Volatile
    var onLine: ((String) -> Unit)? = null

    @Synchronized
    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "spike-log.txt")
        file = f
        try {
            if (f.exists()) sb.append(f.readText())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun line(s: String) {
        sb.append(s).append('\n')
        try {
            file?.appendText(s + "\n")
        } catch (_: Exception) {
        }
        onLine?.invoke(s)
    }

    @Synchronized
    fun text(): String = sb.toString()

    @Synchronized
    fun clear() {
        sb.setLength(0)
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
    }
}
