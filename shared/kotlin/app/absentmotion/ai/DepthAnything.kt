package app.absentmotion.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real Depth Anything V2 small inference through ONNX Runtime.  The fp32 model
 * is downloaded on demand and accelerated with NNAPI (fp16 where available),
 * then XNNPACK, with a plain CPU session as the final fallback.
 */
object DepthAnything {
    const val MODEL_URL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"
    const val MODEL_BYTES = 99_060_839L
    const val MODEL_NAME = "depth-anything-v2-small-fp32.onnx"
    private const val SIDE = 518
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    private enum class Backend { Nnapi, Xnnpack, Cpu }

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var backend: Backend? = null

    fun file(context: Context) = File(context.filesDir, "models/depth/$MODEL_NAME")
    fun installed(context: Context) = file(context).length() == MODEL_BYTES

    /** "NNAPI", "XNNPACK" or "CPU"; used by diagnostics and the depth sheet. */
    fun backendName(context: Context): String {
        open(context)
        return when (backend) {
            Backend.Nnapi -> "NNAPI"
            Backend.Xnnpack -> "XNNPACK"
            else -> "CPU"
        }
    }

    suspend fun download(context: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(context).apply { parentFile?.mkdirs() }
        if (target.length() == MODEL_BYTES) return@withContext target
        val part = File(target.path + ".part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Schnitt/5.3.0")
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
        backend = null
        file(context).delete()
    }

    private fun open(context: Context): OrtSession {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            val environment = OrtEnvironment.getEnvironment()
            env = environment
            val path = file(context).path
            val cores = max(1, Runtime.getRuntime().availableProcessors())
            val attempts = listOf(Backend.Nnapi, Backend.Xnnpack, Backend.Cpu)
            var last: Throwable? = null
            for (choice in attempts) {
                val options = OrtSession.SessionOptions()
                try {
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    when (choice) {
                        Backend.Nnapi -> {
                            options.setIntraOpNumThreads(1)
                            options.setInterOpNumThreads(1)
                            options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                        }
                        Backend.Xnnpack -> {
                            options.setIntraOpNumThreads(cores)
                            options.setInterOpNumThreads(1)
                            options.addXnnpack(mapOf("intra_op_num_threads" to "$cores", "execution_mode" to "1"))
                        }
                        Backend.Cpu -> {
                            options.setIntraOpNumThreads(cores)
                            options.setInterOpNumThreads(1)
                        }
                    }
                    val next = environment.createSession(path, options)
                    if (choice != Backend.Cpu && !probe(environment, next)) {
                        runCatching { next.close() }
                        runCatching { options.close() }
                        last = IllegalStateException("${choice.name} could not run the depth model")
                        continue
                    }
                    session = next
                    backend = choice
                    return next
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError) throw t
                    last = t
                    runCatching { options.close() }
                }
            }
            throw last ?: IllegalStateException("The depth model could not be opened")
        }
    }

    private fun probe(environment: OrtEnvironment, active: OrtSession): Boolean = runCatching {
        val data = FloatArray(3 * SIDE * SIDE)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { input ->
            active.run(mapOf(active.inputNames.first() to input)).use { result -> result.get(0).value != null }
        }
    }.getOrDefault(false)

    /** Returns inverted normalized depth: near subject dark, far background white. */
    suspend fun depthGray(context: Context, bitmap: Bitmap, width: Int, height: Int): ByteArray = withContext(Dispatchers.Default) {
        val active = open(context)
        val environment = env ?: OrtEnvironment.getEnvironment()
        val scaled = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
        try {
            val pixels = IntArray(SIDE * SIDE)
            scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            val plane = SIDE * SIDE
            val data = FloatArray(3 * plane)
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
                val name = active.inputNames.first()
                active.run(mapOf(name to input)).use { result ->
                    val values = flatten(result.get(0).value)
                    var low = Float.MAX_VALUE
                    var high = -Float.MAX_VALUE
                    for (v in values) { if (v < low) low = v; if (v > high) high = v }
                    val range = max(1e-6f, high - low)
                    val out = ByteArray(width * height)
                    val xs0 = IntArray(width)
                    val xs1 = IntArray(width)
                    val xw = FloatArray(width)
                    for (x in 0 until width) {
                        val f = ((x + 0.5f) * SIDE / width - 0.5f).coerceIn(0f, (SIDE - 1).toFloat())
                        val x0 = f.toInt().coerceIn(0, SIDE - 1)
                        xs0[x] = x0
                        xs1[x] = (x0 + 1).coerceAtMost(SIDE - 1)
                        xw[x] = f - x0
                    }
                    for (y in 0 until height) {
                        val fy = ((y + 0.5f) * SIDE / height - 0.5f).coerceIn(0f, (SIDE - 1).toFloat())
                        val y0 = fy.toInt().coerceIn(0, SIDE - 1)
                        val y1 = (y0 + 1).coerceAtMost(SIDE - 1)
                        val wy = fy - y0
                        val row0 = y0 * SIDE
                        val row1 = y1 * SIDE
                        val outRow = y * width
                        for (x in 0 until width) {
                            val x0 = xs0[x]
                            val x1 = xs1[x]
                            val wx = xw[x]
                            val top = values[row0 + x0] + (values[row0 + x1] - values[row0 + x0]) * wx
                            val bottom = values[row1 + x0] + (values[row1 + x1] - values[row1 + x0]) * wx
                            val v = top + (bottom - top) * wy
                            val normalized = ((v - low) / range).coerceIn(0f, 1f)
                            out[outRow + x] = ((1f - normalized) * 255f).roundToInt().coerceIn(0, 255).toByte()
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
        val out = FloatArray(SIDE * SIDE)
        var at = 0
        fun walk(v: Any?) {
            when (v) {
                is FloatArray -> {
                    val n = minOf(v.size, out.size - at)
                    if (n > 0) {
                        v.copyInto(out, at, 0, n)
                        at += n
                    }
                }
                is Array<*> -> for (child in v) walk(child)
                is Float -> if (at < out.size) out[at++] = v
            }
        }
        walk(value)
        if (at < out.size) error("unexpected Depth Anything output")
        return out
    }
}
