package app.absentmotion.spike

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)

        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(16, 8, 16, 8)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        fun refresh() {
            runOnUiThread {
                text.text = SpikeLog.text()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        SpikeLog.onLine = { refresh() }

        fun bg(name: String, block: () -> Unit) {
            Thread {
                try {
                    block()
                } catch (t: Throwable) {
                    SpikeLog.line("CRASH in $name: $t")
                }
                SpikeLog.line("-- $name finished")
            }.start()
        }

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            this.text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

        val engines: List<Pair<String, () -> JsEngine>> = listOf(
            "Rhino" to { RhinoEngine() },
            "QuickJS" to { QuickJsEngine() },
        )

        root.addView(button("1. Run JS tests (Rhino and QuickJS)") {
            bg("js") {
                SpikeLog.line("=== JS tests")
                for ((label, make) in engines) {
                    SpikeLog.line("== engine: $label")
                    try {
                        JsTests.runAll(make) { SpikeLog.line("  $it") }
                    } catch (t: Throwable) {
                        SpikeLog.line("  ENGINE COULD NOT RUN: $t")
                    }
                }
            }
        })
        root.addView(button("2. Run native tests (N0 to N4)") {
            bg("native") {
                SpikeLog.line("=== native tests")
                NativeTests.runAll(this) { SpikeLog.line(it) }
            }
        })
        root.addView(button("3. Run stress tests (may crash the app)") {
            bg("stress") {
                SpikeLog.line("=== stress tests (if the app closes, reopen it: the log is kept)")
                for ((label, make) in engines) {
                    SpikeLog.line("== engine: $label")
                    try {
                        JsTests.runStress(make) { SpikeLog.line("  $it") }
                    } catch (t: Throwable) {
                        SpikeLog.line("  ENGINE COULD NOT RUN: $t")
                    }
                }
            }
        })
        root.addView(button("Copy log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("spike log", SpikeLog.text()))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("Clear log") {
            SpikeLog.clear()
            refresh()
        })
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        SpikeLog.line("--- opened. device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        refresh()
    }
}
