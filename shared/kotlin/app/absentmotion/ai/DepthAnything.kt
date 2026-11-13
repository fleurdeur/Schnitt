package app.absentmotion.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real Depth Anything V2 small inference through ONNX Runtime.  The int8 model
 * is downloaded on demand; inference runs on the CPU/NNAPI backend that ONNX
 * Runtime selects for the phone.  No luminance placeholder is used.
 */
object DepthAnything {
    const val MODEL_URL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"
    const val MODEL_BYTES = 99_060_839L
    const val MODEL_NAME = "depth-anything-v2-small-fp32.onnx"
    private const val SIDE = 518
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null

    fun file(context: Context) = File(context.filesDir, "models/depth/$MODEL_NAME")
    fun installed(context: Context) = file(context).length() == MODEL_BYTES

    suspend fun download(context: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(context).apply { parentFile?.mkdirs() }
        if (target.length() == MODEL_BYTES) return@withContext target
        val part = File(target.path + ".part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Schnitt/5.1.3")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        done += read
                        progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (part.length() != MODEL_BYTES) error("model size mismatch")
            if (!part.renameTo(target)) part.copyTo(target, overwrite = true)
        } finally {
            connection.disconnect()
            if (target.length() != MODEL_BYTES) part.delete()
        }
        target
    }

    fun delete(context: Context) {
        runCatching { session?.close() }
        session = null
        file(context).delete()
    }

    private fun open(context: Context): OrtSession {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            val environment = OrtEnvironment.getEnvironment()
            env = environment
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
                setInterOpNumThreads(1)
            }
            val next = environment.createSession(file(context).path, options)
            session = next
            return next
        }
    }

    /** Returns inverted normalized depth: near subject dark, far background white. */
    suspend fun depthGray(context: Context, bitmap: Bitmap, width: Int, height: Int): ByteArray = withContext(Dispatchers.Default) {
        val session = open(context)
        val environment = env ?: OrtEnvironment.getEnvironment()
        val scaled = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
        try {
            val pixels = IntArray(SIDE * SIDE)
            scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            val data = FloatArray(3 * SIDE * SIDE)
            val plane = SIDE * SIDE
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                data[i] = (r - MEAN[0]) / STD[0]
                data[plane + i] = (g - MEAN[1]) / STD[1]
                data[2 * plane + i] = (b - MEAN[2]) / STD[2]
            }
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { input ->
                val name = session.inputNames.first()
                session.run(mapOf(name to input)).use { result ->
                    val output = result.get(0).value
                    val values = flatten(output)
                    var low = Float.MAX_VALUE
                    var high = -Float.MAX_VALUE
                    for (v in values) { if (v < low) low = v; if (v > high) high = v }
                    val range = max(1e-6f, high - low)
                    val out = ByteArray(width * height)
                    for (y in 0 until height) {
                        val sy = ((y + 0.5f) * SIDE / height).toInt().coerceIn(0, SIDE - 1)
                        for (x in 0 until width) {
                            val sx = ((x + 0.5f) * SIDE / width).toInt().coerceIn(0, SIDE - 1)
                            val v = values[sy * SIDE + sx]
                            val normalized = ((v - low) / range).coerceIn(0f, 1f)
                            out[y * width + x] = ((1f - normalized) * 255f).roundToInt().coerceIn(0, 255).toByte()
                        }
                    }
                    out
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun flatten(value: Any?): FloatArray {
        val list = ArrayList<Float>(SIDE * SIDE)
        fun walk(v: Any?) {
            when (v) {
                is FloatArray -> for (f in v) list.add(f)
                is Array<*> -> for (child in v) walk(child)
                is Float -> list.add(v)
            }
        }
        walk(value)
        if (list.size < SIDE * SIDE) error("unexpected Depth Anything output")
        return FloatArray(SIDE * SIDE) { list[it] }
    }
}
