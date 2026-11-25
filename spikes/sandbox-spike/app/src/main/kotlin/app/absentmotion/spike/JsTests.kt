package app.absentmotion.spike

import java.util.concurrent.atomic.AtomicBoolean

/** Task 1 of the scripts plan: sandbox and budget checks, run the same way on every engine. */
object JsTests {
    private class Case(
        val name: String,
        val src: String,
        val budgetMs: Long = 2000,
        val budgetInstr: Long = 50_000_000,
        val cancelAfterMs: Long? = null,
        val info: Boolean = false,
        val check: (Outcome, Host) -> Pair<Boolean, String>,
    )

    private fun detail(o: Outcome) = if (o.ok) o.value else (o.error ?: "")

    private fun stops(maxMs: Long): (Outcome, Host) -> Pair<Boolean, String> = { o, _ ->
        (!o.ok && o.ms < maxMs) to "${o.error} after ${o.ms}ms (instr=${o.instr})"
    }

    private val FEATURES = """
        var f = {};
        function t(n, c) { try { f[n] = !!c(); } catch (e) { f[n] = false; } }
        t('arrow', function () { return eval('(() => 1)()') === 1; });
        t('let/const', function () { return eval('let a = 1; const b = 2; a + b') === 3; });
        t('template', function () { return eval('`a${'$'}{1}`') === 'a1'; });
        t('spread', function () { return eval('Math.max(...[1, 2])') === 2; });
        t('destructuring', function () { return eval('var {a} = {a: 1}; a') === 1; });
        t('class', function () { return eval('class A {}; typeof A') === 'function'; });
        t('for-of', function () { return eval('var s = 0; for (var x of [1, 2]) s += x; s') === 3; });
        t('default-param', function () { return eval('(function (a = 2) { return a; })()') === 2; });
        t('Map/Set', function () { return typeof Map === 'function' && typeof Set === 'function'; });
        t('Symbol', function () { return typeof Symbol === 'function'; });
        t('Promise', function () { return typeof Promise === 'function'; });
        t('Math.hypot', function () { return Math.hypot(3, 4) === 5; });
        t('Array.includes', function () { return [1].includes(1); });
        t('Object.freeze', function () { return typeof Object.freeze === 'function'; });
        t('String.repeat', function () { return 'a'.repeat(2) === 'aa'; });
        t('Float64Array', function () { return typeof Float64Array === 'function'; });
        t('JSON', function () { return JSON.parse('[1]')[0] === 1; });
        JSON.stringify(f)
    """.trimIndent()

    private val BENCH = """
        function bench() {
            var N = 30, steps = 1200, dt = 1 / 120, g = 3 * 1080;
            var x = [], y = [], vx = [], vy = [], r = [];
            for (var i = 0; i < N; i++) {
                x.push(100 + (i * 37) % 900); y.push(100 + (i * 53) % 800);
                vx.push((i % 7) - 3); vy.push(0); r.push(30 + (i % 5) * 4);
            }
            var hits = 0;
            for (var s = 0; s < steps; s++) {
                for (var i = 0; i < N; i++) {
                    vy[i] += g * dt; x[i] += vx[i] * dt; y[i] += vy[i] * dt;
                    if (y[i] + r[i] > 1080) { y[i] = 1080 - r[i]; vy[i] *= -0.5; }
                    if (x[i] < r[i]) { x[i] = r[i]; vx[i] *= -0.5; }
                    if (x[i] + r[i] > 1920) { x[i] = 1920 - r[i]; vx[i] *= -0.5; }
                }
                for (var i = 0; i < N; i++) {
                    for (var j = i + 1; j < N; j++) {
                        var dx = x[j] - x[i], dy = y[j] - y[i], d2 = dx * dx + dy * dy, rr = r[i] + r[j];
                        if (d2 < rr * rr && d2 > 0) {
                            var d = Math.sqrt(d2), nx = dx / d, ny = dy / d, p = (rr - d) / 2;
                            x[i] -= nx * p; y[i] -= ny * p; x[j] += nx * p; y[j] += ny * p;
                            var rv = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny;
                            if (rv < 0) {
                                vx[i] += rv * nx * 0.75; vy[i] += rv * ny * 0.75;
                                vx[j] -= rv * nx * 0.75; vy[j] -= rv * ny * 0.75; hits++;
                            }
                        }
                    }
                }
            }
            return hits;
        }
        bench()
    """.trimIndent()

    private fun cases(): List<Case> = listOf(
        Case("hostCallsWork", "ctx.log('hello'); ctx.ping(); ctx.ping(); 1") { o, h ->
            (o.ok && h.calls == 2 && h.logs == listOf("hello")) to "calls=${h.calls} logs=${h.logs} ${detail(o)}"
        },
        Case(
            "noJavaOrAndroidReachable",
            "[typeof Packages, typeof java, typeof importClass, typeof require, typeof process, " +
                "typeof setTimeout, typeof fetch, typeof Java, typeof android].join(',')",
        ) { o, _ ->
            val v = o.value.split(",")
            (o.ok && v.size == 9 && v.all { it == "undefined" }) to detail(o)
        },
        Case(
            "constructorEscapeFails",
            "var out = [];\n" +
                "try { out.push(Function('return typeof java')()); } catch (e) { out.push('err'); }\n" +
                "try { out.push((function () {}).constructor('return typeof Packages')()); } catch (e) { out.push('err'); }\n" +
                "try { out.push((0, eval)('typeof java')); } catch (e) { out.push('err'); }\n" +
                "out.join(',')",
        ) { o, _ ->
            (o.ok && !o.value.contains("object") && !o.value.contains("function")) to detail(o)
        },
        Case(
            "hostObjectCannotBeReplaced",
            "try { ctx.log = function () {}; } catch (e) {}\n" +
                "try { Object.defineProperty(ctx, 'log', {value: 1}); } catch (e) {}\n" +
                "try { delete ctx.log; } catch (e) {}\n" +
                "ctx.log('after'); ctx.ping();\n" +
                "typeof ctx.log",
        ) { o, h ->
            (o.ok && o.value == "function" && h.calls == 1 && h.logs == listOf("after")) to
                "calls=${h.calls} logs=${h.logs} ${detail(o)}"
        },
        Case("infiniteLoopStops", "while (true) {}", budgetMs = 1000, check = stops(3000)),
        Case(
            "loopThatCatchesStillStops",
            "while (true) { try { for (;;) {} } catch (e) {} }",
            budgetMs = 1000,
            check = stops(3000),
        ),
        Case("cancelStops", "while (true) {}", budgetMs = 20_000, cancelAfterMs = 150, check = stops(1500)),
        Case("deepRecursionFailsCleanly", "function f() { return 1 + f(); } f()", budgetMs = 5000, check = stops(6000)),
        Case("errorCarriesLine", "var a = 1;\nvar b = 2;\nthrow new Error('boom');") { o, _ ->
            (!o.ok && o.line == 3) to "line=${o.line} ${o.error}"
        },
        Case("syntaxErrorIsReadable", "var = ;") { o, _ -> (!o.ok && !o.error.isNullOrBlank()) to (o.error ?: "") },
        Case("languageFeatures", FEATURES, info = true) { o, _ -> true to detail(o) },
        Case(
            "benchmark30bodies1200steps",
            BENCH,
            budgetMs = 60_000,
            budgetInstr = 5_000_000_000L,
            info = true,
        ) { o, _ -> true to "result=${detail(o)} ${o.ms}ms instr=${o.instr}" },
    )

    private fun runCase(make: () -> JsEngine, c: Case, log: (String) -> Unit) {
        val engine = make()
        val host = Host()
        val cancel = AtomicBoolean(false)
        var out: Outcome? = null
        val th = Thread {
            out = try {
                engine.run(c.src, c.budgetMs, c.budgetInstr, cancel, host)
            } catch (t: Throwable) {
                Outcome(false, "", "${t.javaClass.simpleName}: ${t.message}", 0)
            }
        }
        th.isDaemon = true
        th.start()
        c.cancelAfterMs?.let { d ->
            val t = Thread {
                try {
                    Thread.sleep(d)
                } catch (_: InterruptedException) {
                }
                cancel.set(true)
            }
            t.isDaemon = true
            t.start()
        }
        val wait = c.budgetMs + 8000
        th.join(wait)
        val o = out
        if (th.isAlive || o == null) {
            log("HANG ${c.name}: not stopped ${wait}ms after start (force-stop the app after this run)")
            return
        }
        val (pass, text) = c.check(o, host)
        log("${if (c.info) "INFO" else if (pass) "PASS" else "FAIL"} ${c.name}: $text")
    }

    fun runAll(make: () -> JsEngine, log: (String) -> Unit) {
        for (c in cases()) runCase(make, c, log)
        // Fresh state per run: a global set by one run must not exist in the next.
        val cancel = AtomicBoolean(false)
        make().run("leak = 1; 1", 2000, 50_000_000, cancel, Host())
        val second = make().run("typeof leak", 2000, 50_000_000, cancel, Host())
        log("${if (second.ok && second.value == "undefined") "PASS" else "FAIL"} globalsDoNotLeakBetweenRuns: ${detail(second)}")
    }

    /** May be killed by the system or freeze the phone for a while: its own button. */
    fun runStress(make: () -> JsEngine, log: (String) -> Unit) {
        val stress = listOf(
            Case("hugeString", "var s = 'x'.repeat(1e9); s.length", budgetMs = 8000, info = true) { o, _ ->
                true to (if (o.ok) "NOT REFUSED: ${o.value}" else "refused: ${o.error}") + " ${o.ms}ms"
            },
            Case(
                "memoryFlood",
                "var a = []; while (true) { a.push('y'.repeat(100000)); }",
                budgetMs = 8000,
                info = true,
            ) { o, _ -> true to (if (o.ok) "returned?" else o.error ?: "") + " ${o.ms}ms" },
        )
        for (c in stress) runCase(make, c, log)
    }
}
