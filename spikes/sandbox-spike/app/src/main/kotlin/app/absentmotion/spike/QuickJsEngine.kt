package app.absentmotion.spike

import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

class QuickJsEngine : JsEngine {
    override val name = "QuickJS (quickjs-kt)"

    override fun run(src: String, budgetMs: Long, budgetInstr: Long, cancel: AtomicBoolean, host: Host): Outcome {
        val t0 = System.nanoTime()
        @Volatile var done = false
        return try {
            val value = runBlocking {
                quickJs {
                    evaluationTimeoutMillis = budgetMs
                    val inst = this
                    val watcher = Thread {
                        while (!done) {
                            if (cancel.get()) {
                                inst.interruptEvaluation()
                                break
                            }
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                    watcher.isDaemon = true
                    watcher.start()
                    try {
                        define("ctx") {
                            function("log") { args ->
                                host.logs.add(args.firstOrNull().toString())
                                null
                            }
                            function("ping") {
                                host.calls++
                                null
                            }
                        }
                        evaluate<Any?>("Object.freeze(ctx);")
                        evaluate<Any?>(src, filename = "script")
                    } finally {
                        done = true
                    }
                }
            }
            Outcome(true, value?.toString() ?: "undefined", null, msSince(t0))
        } catch (t: Throwable) {
            val line = try {
                (t.javaClass.methods.firstOrNull { it.name == "getLineNumber" }?.invoke(t) as? Number)?.toInt()
            } catch (_: Throwable) {
                null
            }
            Outcome(false, "", "${t.javaClass.simpleName}: ${t.message}", msSince(t0), -1, line)
        }
    }
}
