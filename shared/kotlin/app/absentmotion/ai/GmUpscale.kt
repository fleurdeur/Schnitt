package app.absentmotion.ai

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The GM Anime / GM Soul 4x models are 128x128 -> 512x512 ESRGAN-style
 * generators. They are tiled here exactly like the reference tool: each tile
 * is fed as planar RGB floats and the 4x result is stitched back into one
 * bitmap. Edge tiles replicate the last source pixel instead of leaving a
 * transparent border, which removes the dark seam the old tiled path showed.
 */
internal class GmUpscale private constructor(
    private val interpreter: Interpreter,
    private val scale: Int,
) : Closeable {
    private val input = ByteBuffer.allocateDirect(IN * IN * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(OUT * OUT * 3 * 4).order(ByteOrder.nativeOrder())
    private val tile = IntArray(IN * IN)
    private val pixels = IntArray(OUT * OUT)

    fun upscale(source: IntArray, width: Int, height: Int): IntArray {
        val outWidth = width * scale
        val outHeight = height * scale
        val result = IntArray(outWidth * outHeight)
        var y = 0
        while (y < height) {
            val tileHeight = min(IN, height - y)
            var x = 0
            while (x < width) {
                val tileWidth = min(IN, width - x)
                for (ty in 0 until IN) {
                    val sy = min(height - 1, y + ty)
                    val row = sy * width
                    for (tx in 0 until IN) {
                        val sx = min(width - 1, x + tx)
                        tile[ty * IN + tx] = source[row + sx]
                    }
                }
                runTile()
                val copyWidth = tileWidth * scale
                val copyHeight = tileHeight * scale
                val destX = x * scale
                val destY = y * scale
                for (ty in 0 until copyHeight) {
                    val src = ty * OUT
                    val dst = (destY + ty) * outWidth + destX
                    System.arraycopy(pixels, src, result, dst, copyWidth)
                }
                x += IN
            }
            y += IN
        }
        return result
    }

    private fun runTile() {
        input.rewind()
        for (pixel in tile) {
            input.putFloat(((pixel shr 16) and 0xFF) / 255f)
            input.putFloat(((pixel shr 8) and 0xFF) / 255f)
            input.putFloat((pixel and 0xFF) / 255f)
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        for (i in pixels.indices) {
            val r = clamp((output.float * 255f).roundToInt())
            val g = clamp((output.float * 255f).roundToInt())
            val b = clamp((output.float * 255f).roundToInt())
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        private const val IN = 128
        private const val OUT = 512

        fun open(context: Context, mode: EnhanceMode): GmUpscale? {
            if (mode != EnhanceMode.GmAnime && mode != EnhanceMode.GmSoul) return null
            val asset = if (mode == EnhanceMode.GmAnime) "goldmotion/gm_anime_x4.tflite" else "goldmotion/gm_soul_x4.tflite"
            val model = runCatching { map(context, asset) }.getOrNull() ?: return null
            val options = Interpreter.Options().setNumThreads(max(2, min(6, Runtime.getRuntime().availableProcessors())))
            val interpreter = runCatching { Interpreter(model, options) }.getOrNull() ?: return null
            val inShape = interpreter.getInputTensor(0).shape()
            val outShape = interpreter.getOutputTensor(0).shape()
            val ok = inShape.contentEquals(intArrayOf(1, IN, IN, 3)) && outShape.contentEquals(intArrayOf(1, OUT, OUT, 3))
            if (!ok) {
                interpreter.close()
                return null
            }
            return GmUpscale(interpreter, 4)
        }

        private fun map(context: Context, asset: String): MappedByteBuffer {
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }

        private fun clamp(v: Int) = v.coerceIn(0, 255)
    }
}
