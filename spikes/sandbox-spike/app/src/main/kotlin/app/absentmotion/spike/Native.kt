package app.absentmotion.spike

import java.nio.ByteBuffer

object Native {
    init {
        System.loadLibrary("spike")
    }

    external fun probe(appFilesDir: String): String
    external fun loadPayload(fd: Int): String
    external fun loadPath(path: String): String
    external fun spin()
    external fun invert(buf: ByteBuffer, len: Int)
}
