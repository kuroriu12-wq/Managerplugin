package app.absentmotion.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Blocking client for IsoService. Use from a background thread only. */
class IsoClient(private val ctx: Context, private val instance: String) {
    private val connected = CountDownLatch(1)
    val died = CountDownLatch(1)

    @Volatile
    var service: Messenger? = null
    private val replies = LinkedBlockingQueue<String>()
    private val replyThread = HandlerThread("iso-reply").apply { start() }
    private val replyMessenger = Messenger(Handler(replyThread.looper) { m ->
        replies.put(m.data.getString("text") ?: "")
        true
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            try {
                binder.linkToDeath({ died.countDown() }, 0)
            } catch (_: Exception) {
            }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {}
    }

    fun connect(timeoutMs: Long): Boolean {
        val intent = Intent(ctx, IsoService::class.java)
        // One isolated process per instance name. A plain bindService would share one process between all clients.
        val ok = if (Build.VERSION.SDK_INT >= 29) {
            ctx.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instance, ctx.mainExecutor, conn)
        } else {
            ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
        return ok && connected.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun call(what: Int, data: Bundle = Bundle(), timeoutMs: Long): String? {
        val m = Message.obtain(null, what)
        m.data = data
        m.replyTo = replyMessenger
        service?.send(m) ?: return null
        return replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun close() {
        try {
            ctx.unbindService(conn)
        } catch (_: Exception) {
        }
    }
}
