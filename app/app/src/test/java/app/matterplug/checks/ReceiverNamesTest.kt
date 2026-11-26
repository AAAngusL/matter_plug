package app.matterplug.checks

import app.matterplug.cellular.CellularScheduler
import app.matterplug.guard.CheckScheduler
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The alarms name their receivers (they cannot import them: the receivers sit in a higher layer), so a rename must not go unnoticed. */
class ReceiverNamesTest {
    @Test fun checkReceiverExists() = assertNotNull(Class.forName(CheckScheduler.CHECK_RECEIVER, false, javaClass.classLoader))
    @Test fun cellularReceiverExists() = assertNotNull(Class.forName(CellularScheduler.RECEIVER, false, javaClass.classLoader))
}
