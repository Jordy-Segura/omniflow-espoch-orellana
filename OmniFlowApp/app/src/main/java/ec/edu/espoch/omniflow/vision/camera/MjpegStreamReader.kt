package ec.edu.espoch.omniflow.vision.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Lee video de OTRO telefono usado como camara IP temporal (o de una camara IP real con MJPEG/HTTP).
 *
 * Apps recomendadas en el telefono-camara:
 *  - "IP Webcam" (Android):  http://IP:8080/video     (stream MJPEG)  o  http://IP:8080/shot.jpg (foto)
 *  - "DroidCam":             http://IP:4747/video
 *  - Camaras IP con MJPEG:   la URL MJPEG del fabricante (para RTSP se usara el backend con OpenCV).
 *
 * Si la URL termina en .jpg/.jpeg se hace polling de fotos; si no, se parsea el stream multipart
 * buscando los marcadores JPEG SOI (FFD8) y EOI (FFD9).
 */
class MjpegStreamReader(
    private val url: String,
    private val maxSide: Int = 640,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val isSnapshotMode: Boolean
        get() = url.substringBefore('?').lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }

    /** Bucle de lectura. Llama [onFrame] por cada imagen decodificada hasta que se cancele la corrutina. */
    suspend fun stream(onFrame: (Bitmap) -> Unit) {
        if (isSnapshotMode) pollSnapshots(onFrame) else readStream(onFrame)
    }

    private suspend fun pollSnapshots(onFrame: (Bitmap) -> Unit) {
        while (currentCoroutineContext().isActive) {
            val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} en $url")
                resp.body.bytes()
            }
            decode(bytes, bytes.size)?.let(onFrame)
            delay(120)
        }
    }

    private suspend fun readStream(onFrame: (Bitmap) -> Unit) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} en $url")
            val input: InputStream = resp.body.byteStream().buffered(64 * 1024)
            val jpeg = ByteArrayOutputStream(256 * 1024)
            var inJpeg = false
            var prev = -1
            while (true) {
                currentCoroutineContext().ensureActive()
                val b = input.read()
                if (b == -1) throw IOException("El stream se cerro")
                if (!inJpeg) {
                    if (prev == 0xFF && b == 0xD8) {
                        inJpeg = true
                        jpeg.reset()
                        jpeg.write(0xFF)
                        jpeg.write(0xD8)
                    }
                } else {
                    jpeg.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        inJpeg = false
                        val data = jpeg.toByteArray()
                        decode(data, data.size)?.let(onFrame)
                    } else if (jpeg.size() > MAX_FRAME_BYTES) {
                        inJpeg = false // frame corrupto
                    }
                }
                prev = b
            }
        }
    }

    private fun decode(bytes: ByteArray, len: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, len, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, len, opts)
    }

    companion object {
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}
