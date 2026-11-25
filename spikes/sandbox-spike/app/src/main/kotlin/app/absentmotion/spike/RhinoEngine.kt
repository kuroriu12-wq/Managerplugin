package app.absentmotion.spike

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextAction
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.atomic.AtomicBoolean

class RhinoEngine : JsEngine {
    override val name = "Rhino 1.7.15"

    /** An Error (not an Exception) so a script's try/catch has the least chance of swallowing it. */
    private class Stop(msg: String) : Error(msg)

    private class Factory(
        val deadlineNanos: Long,
        val maxInstr: Long,
        val cancel: AtomicBoolean,
    ) : ContextFactory() {
        @Volatile
        var count = 0L

        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1 // interpreted: the only mode that works on Android
            cx.instructionObserverThreshold = 10_000
            cx.languageVersion = Context.VERSION_ES6
            cx.maximumInterpreterStackDepth = 2_000
            cx.setClassShutter(ClassShutter { false })
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            count += instructionCount
            if (cancel.get()) throw Stop("cancelled")
            if (count > maxInstr) throw Stop("instruction budget exceeded")
            if (System.nanoTime() > deadlineNanos) throw Stop("time budget exceeded")
        }
    }

    private fun fn(body: (Array<Any?>) -> Any?): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            body(args)
    }

    override fun run(src: String, budgetMs: Long, budgetInstr: Long, cancel: AtomicBoolean, host: Host): Outcome {
        val t0 = System.nanoTime()
        val factory = Factory(t0 + budgetMs * 1_000_000, budgetInstr, cancel)
        return try {
            val value = factory.call(ContextAction<String> { cx ->
                val scope = cx.initSafeStandardObjects()
                val ctxObj = cx.newObject(scope) as ScriptableObject
                val locked = ScriptableObject.READONLY or ScriptableObject.PERMANENT
                ScriptableObject.defineProperty(ctxObj, "log", fn { args ->
                    host.logs.add(Context.toString(args.getOrNull(0)))
                    Undefined.instance
                }, locked)
                ScriptableObject.defineProperty(ctxObj, "ping", fn {
                    host.calls++
                    Undefined.instance
                }, locked)
                ctxObj.sealObject()
                ScriptableObject.putProperty(scope, "ctx", ctxObj)
                Context.toString(cx.evaluateString(scope, src, "script", 1, null))
            })
            Outcome(true, value, null, msSince(t0), factory.count)
        } catch (e: Stop) {
            Outcome(false, "", "Stop: ${e.message}", msSince(t0), factory.count)
        } catch (e: RhinoException) {
            Outcome(false, "", "${e.javaClass.simpleName}: ${e.details()}", msSince(t0), factory.count, e.lineNumber())
        } catch (e: Throwable) {
            Outcome(false, "", "${e.javaClass.simpleName}: ${e.message}", msSince(t0), factory.count)
        }
    }
}
