package app.absentmotion.spike

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit

/** Spikes N0 to N4 from manager-plugin-native-sandbox.md. */
object NativeTests {
    fun runAll(context: Context, log: (String) -> Unit) {
        val app = context.applicationContext

        log("== N0 control: normal app process (not isolated)")
        val libDir = app.applicationInfo.nativeLibraryDir
        val src = File(libDir, "libpayload.so")
        log("  libpayload.so in nativeLibraryDir: ${src.exists()} ($libDir)")
        val dir = File(app.filesDir, "plugins").apply { mkdirs() }
        val rw = File(dir, "payload_rw.so")
        val ro = File(dir, "payload_ro.so")
        val dl = File(dir, "payload_dl.so")
        for (f in listOf(rw, ro, dl)) {
            try {
                f.delete()
                src.copyTo(f, overwrite = true)
            } catch (e: Exception) {
                log("  copy ${f.name} failed: $e")
            }
        }
        ro.setReadOnly()
        dl.setReadOnly()
        log("  probe in app process (for comparison):")
        log(Native.probe(app.filesDir.absolutePath).trimEnd())
        log(try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "id"))
            "    exec /system/bin/sh: ALLOWED, output: " + p.inputStream.bufferedReader().readText().trim()
        } catch (t: Throwable) {
            "    exec /system/bin/sh: blocked ($t)"
        })
        log("  load .so from a writable file in app storage:")
        log(Native.loadPath(rw.absolutePath).trimEnd())
        log("  load .so from a read-only file in app storage:")
        log(Native.loadPath(ro.absolutePath).trimEnd())

        log("== N1 isolated process: what can it reach?")
        val c1 = IsoClient(app, "main")
        if (!c1.connect(8000)) {
            log("  FAIL: could not bind the isolated service")
            return
        }
        val probeData = Bundle().apply { putString("dir", app.filesDir.absolutePath) }
        log((c1.call(IsoService.PROBE, probeData, 8000) ?: "  no reply (timeout)").trimEnd())
        log((c1.call(IsoService.EXEC, Bundle(), 8000) ?: "  no reply (timeout)").trimEnd())

        log("== N2 isolated process: load a .so that is not in the APK (passed as a file descriptor)")
        try {
            ParcelFileDescriptor.open(dl, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val b = Bundle().apply { putParcelable("fd", pfd) }
                log((c1.call(IsoService.LOAD, b, 8000) ?: "  no reply (timeout)").trimEnd())
            }
        } catch (t: Throwable) {
            log("  FAIL: $t")
        }

        log("== N2b isolated process: copy the .so into a memfd inside the process, then load it")
        try {
            ParcelFileDescriptor.open(dl, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val b = Bundle().apply { putParcelable("fd", pfd) }
                log((c1.call(IsoService.LOAD_MEMFD, b, 8000) ?: "  no reply (timeout)").trimEnd())
            }
        } catch (t: Throwable) {
            log("  FAIL: $t")
        }

        log("== N3 frame transfer: 1080p RGBA through shared memory, 60 round trips")
        try {
            val size = 1920 * 1080 * 4
            val shm = SharedMemory.create("frame", size)
            val map = shm.mapReadWrite()
            val fill = ByteArray(size).also { it.fill(0x11) }
            val frames = 60
            var verified = 0
            var replied = 0
            val t0 = SystemClock.elapsedRealtimeNanos()
            for (i in 0 until frames) {
                map.position(0)
                map.put(fill)
                val b = Bundle().apply {
                    putParcelable("shm", shm)
                    putInt("len", size)
                }
                val r = c1.call(IsoService.FRAME, b, 5000) ?: break
                if (r == "ok") replied++
                if (map.get(0) == 0xEE.toByte() && map.get(size - 1) == 0xEE.toByte()) verified++
            }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            log("  replies=$replied/$frames verified=$verified/$frames total=${"%.0f".format(ms)}ms per frame=${"%.1f".format(ms / frames)}ms")
            SharedMemory.unmap(map)
            shm.close()
        } catch (t: Throwable) {
            log("  FAIL: $t")
        }

        log("== N4 watchdog: kill a plugin that spins forever")
        val c2 = IsoClient(app, "spinner")
        if (!c2.connect(8000)) {
            log("  FAIL: could not bind a second isolated service")
        } else {
            log("  " + (c2.call(IsoService.SPIN, Bundle(), 3000) ?: "no reply (timeout)"))
            Thread.sleep(1000)
            val t = SystemClock.elapsedRealtime()
            c2.close()
            val dead = c2.died.await(5, TimeUnit.SECONDS)
            val took = SystemClock.elapsedRealtime() - t
            log("  after unbind: process death ${if (dead) "seen after ${took}ms" else "NOT seen within 5000ms"}")
            val c3 = IsoClient(app, "fresh")
            val alive = c3.connect(8000) && c3.call(IsoService.PING, Bundle(), 5000) != null
            log("  a new isolated process answers afterwards: $alive")
            log("  main process: " + c1.call(IsoService.PING, Bundle(), 5000) + " / fresh process: " + c3.call(IsoService.PING, Bundle(), 5000))
            c3.close()
        }
        c1.close()
    }
}
