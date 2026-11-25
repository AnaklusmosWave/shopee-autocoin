package tw.avianjay.autocoin

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
