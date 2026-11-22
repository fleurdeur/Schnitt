package app.absentmotion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadFrameRemoverTest {
    @Test fun identicalFramesAreDead() {
        val frame = IntArray(64) { 0xFF204060.toInt() }
        assertTrue(DeadFrameRemover.sameFrame(frame, frame.copyOf(), 8, 8))
    }

    @Test fun aVisibleChangeKeepsTheFrameAlive() {
        val a = IntArray(64) { 0xFF204060.toInt() }
        val b = a.copyOf().also { it[31] = 0xFFFF0000.toInt() }
        assertFalse(DeadFrameRemover.sameFrame(a, b, 8, 8))
    }

    @Test fun tinyEncoderNoiseDoesNotCountAsMotion() {
        val a = IntArray(64) { 0xFF204060.toInt() }
        val b = a.copyOf().also { it[31] = 0xFF214161.toInt() }
        assertTrue(DeadFrameRemover.sameFrame(a, b, 8, 8))
    }
}
