package tw.avianjay.autocoin

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLogConsoleTest {
    @Test
    fun consoleClearsAtThreeAndCatchesUpAfterMissedClear() {
        val yesterday = LocalDate.of(2026, 11, 24)

        assertFalse(shouldClearConsole(yesterday, LocalDateTime.of(2026, 11, 25, 2, 59)))
        assertTrue(shouldClearConsole(yesterday, LocalDateTime.of(2026, 11, 25, 3, 0)))
        assertTrue(shouldClearConsole(yesterday.minusDays(1), LocalDateTime.of(2026, 11, 25, 1, 0)))
        assertFalse(shouldClearConsole(LocalDate.of(2026, 11, 25), LocalDateTime.of(2026, 11, 25, 15, 0)))
    }

    @Test
    fun consoleAddsDividerBetweenDatesAndLimitsReturnedEntries() {
        val entries = listOf(
            ConsoleLogEntry(LocalDate.of(2026, 11, 24), "older"),
            ConsoleLogEntry(LocalDate.of(2026, 11, 25), "newer"),
        )

        assertEquals(
            listOf("older", "--------------------", "newer"),
            renderConsoleLines(entries, 2),
        )
        assertEquals(listOf("newer"), renderConsoleLines(entries, 1))
    }

    @Test
    fun truncatedConsoleEntryDoesNotEraseHistoryOrBreakLaterAppends() {
        val app = RuntimeEnvironment.getApplication()
        val today = LocalDate.now().toString()
        app.getSharedPreferences("console", Context.MODE_PRIVATE).edit()
            .putString("last_clear_date", today).commit()
        val valid = JSONObject().put("date", today).put("line", "previous entry")
        val file = File(app.filesDir, "autocoin-console.log")
        file.writeText(valid.toString() + "\n{\"date\":")

        AppLog.init(app)
        assertEquals(listOf("previous entry"), AppLog.recent())
        AppLog.i("new entry")

        val saved = file.readLines().map { JSONObject(it).getString("line") }
        assertEquals(2, saved.size)
        assertEquals("previous entry", saved.first())
        assertTrue(saved.last().endsWith("new entry"))
    }
}
