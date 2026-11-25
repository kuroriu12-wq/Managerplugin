package app.absentmotion.spike

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.Process
import android.os.SharedMemory

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelable(key, T::class.java) else getParcelable(key) as? T

/** Runs in an isolated process (android:isolatedProcess=true). */
class IsoService : Service() {
    companion object {
        const val PROBE = 1
        const val LOAD = 2
        const val FRAME = 3
        const val SPIN = 4
        const val PING = 5
    }

    private val thread = HandlerThread("iso-worker").apply { start() }
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                val text = try {
                    handle(msg)
                } catch (t: Throwable) {
                    "service error: $t"
                }
                val reply = Message.obtain()
                reply.data = Bundle().apply { putString("text", text) }
                try {
                    msg.replyTo?.send(reply)
                } catch (_: Exception) {
                }
            }
        })
    }

    private fun handle(msg: Message): String = when (msg.what) {
        PROBE -> "pid=${Process.myPid()} uid=${Process.myUid()}\n" +
            Native.probe(msg.data.getString("dir") ?: "/data")
        LOAD -> {
            val pfd = msg.data.parcelable<ParcelFileDescriptor>("fd")
            if (pfd == null) "no fd received" else Native.loadPayload(pfd.detachFd())
        }
        FRAME -> {
            val shm = msg.data.parcelable<SharedMemory>("shm")
            if (shm == null) {
                "no shared memory received"
            } else {
                val buf = shm.mapReadWrite()
                Native.invert(buf, msg.data.getInt("len"))
                SharedMemory.unmap(buf)
                "ok"
            }
        }
        SPIN -> {
            Thread { Native.spin() }.start()
            "spinning on a background thread"
        }
        PING -> "pong pid=${Process.myPid()}"
        else -> "unknown message ${msg.what}"
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // Make sure a spinning native thread dies with the service.
        Process.killProcess(Process.myPid())
    }
}
