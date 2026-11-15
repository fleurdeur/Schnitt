package app.absentmotion.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineClockTest {
    @Test fun playbackClockRollsOverAtSixtySeconds() {
        assertEquals("00:00:59", TimelineMath.playbackTimecode(59, 1))
        assertEquals("00:01:00", TimelineMath.playbackTimecode(60, 1))
        assertEquals("00:01:00", TimelineMath.playbackTimecode(120, 2))
        assertEquals("00:01:00", TimelineMath.playbackTimecode(7200, 120))
        assertEquals("01:00:00", TimelineMath.playbackTimecode(3600, 1))
    }

    @Test fun playbackClockNeverShowsSixtyInTheSecondsField() {
        assertEquals("00:00:59", TimelineMath.playbackTimecode(3599, 60))
        assertEquals("00:01:00", TimelineMath.playbackTimecode(3600, 60))
        assertEquals("00:59:59", TimelineMath.playbackTimecode(359999, 100))
        assertEquals("01:00:00", TimelineMath.playbackTimecode(360000, 100))
    }
}
