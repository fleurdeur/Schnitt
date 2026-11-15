package app.absentmotion.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineClockTest {
    @Test fun playbackClockRollsOverAtSixtySeconds() {
        assertEquals("00:00:59.000", TimelineMath.playbackTimecode(59, 1))
        assertEquals("00:01:00.000", TimelineMath.playbackTimecode(60, 1))
        assertEquals("00:01:00.000", TimelineMath.playbackTimecode(120, 2))
        assertEquals("00:01:00.000", TimelineMath.playbackTimecode(7200, 120))
        assertEquals("01:00:00.000", TimelineMath.playbackTimecode(3600, 1))
    }

    @Test fun playbackClockShowsMillisecondsWithoutPassingFiftyNineSeconds() {
        assertEquals("00:00:00.500", TimelineMath.playbackTimecode(15, 30))
        assertEquals("00:00:59.983", TimelineMath.playbackTimecode(3599, 60))
        assertEquals("00:01:00.000", TimelineMath.playbackTimecode(3600, 60))
        assertEquals("00:00:59.999", TimelineMath.playbackTimecode(59999, 1000))
        assertEquals("00:01:00.000", TimelineMath.playbackTimecode(60000, 1000))
    }
}
