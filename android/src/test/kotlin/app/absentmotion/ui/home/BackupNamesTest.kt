package app.absentmotion.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupNamesTest {
    @Test fun onlyTheAppsOwnWeeklyBackupsMayBeReplaced() {
        assertTrue(isWeeklyBackupName("Schnitt-weekly-2026-10-22-1030.absbackup"))
        assertTrue(isWeeklyBackupName("Schnitt-weekly-2026-10-22-1030 (1).absbackup"))
        assertFalse(isWeeklyBackupName("Schnitt-weekly-2026-10-22-1030.absbackup copy.zip"))
        assertFalse(isWeeklyBackupName("Schnitt-backup-2026-10-22-1030.absbackup"))
        assertFalse(isWeeklyBackupName("Schnitt 20261022_101010.mp4"))
        assertFalse(isWeeklyBackupName("holiday.mp4"))
        assertFalse(isWeeklyBackupName(""))
        assertFalse(isWeeklyBackupName(null))
    }
}
