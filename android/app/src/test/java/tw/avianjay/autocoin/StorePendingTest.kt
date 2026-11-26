package tw.avianjay.autocoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorePendingTest {
    private lateinit var store: Store

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("state", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("room_coin_records", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        store = Store(app)
    }

    @Test
    fun roomClaimRecordUsesRoomAndMaximumAndResetsCountWhenMaximumChanges() {
        store.recordRoomClaim(setOf("主播"), 6.0)
        store.day.coinTotal = 1.25
        store.save()

        assertEquals(1, Store(RuntimeEnvironment.getApplication()).roomClaims("主播"))
        assertTrue(store.observeRoomMax("主播", 5.5))
        assertEquals(0, store.roomClaims("主播"))
        assertEquals(1.25, store.day.coinTotal, 0.001)
        assertFalse(store.observeRoomMax("主播", 5.5))
    }

    @Test
    fun roomClaimRecordDateRollsOverAtThreeAm() {
        assertEquals(
            "2026-11-25",
            roomRecordDate(LocalDateTime.of(2026, 11, 26, 2, 59)),
        )
        assertEquals(
            "2026-11-26",
            roomRecordDate(LocalDateTime.of(2026, 11, 26, 3, 0)),
        )
    }

    @Test
    fun firstPendingObservationIsOnlyBaseline() {
        store.observePending(12.34)

        val day = store.day
        assertTrue(day.pendingBaselineSet)
        assertEquals(12.34, day.pendingBaseline, 0.001)
        assertEquals(0.0, day.pendingGainLive, 0.001)
        assertEquals(0.0, day.pendingGainWatch, 0.001)
        assertEquals(0.0, day.pendingGainWheel, 0.001)
    }

    @Test
    fun positivePendingChangesAreAttributedAndWithdrawalIsNotGain() {
        store.observePending(10.0)
        store.markPendingGainHint("live")
        store.observePending(10.35)
        store.observePending(10.35, "watch")
        store.markPendingGainHint("wheel")
        store.observePending(10.45, "wheel")
        store.observePending(5.45)

        val day = store.day
        assertEquals(0.35, day.pendingGainLive, 0.001)
        assertEquals(0.0, day.pendingGainWatch, 0.001)
        assertEquals(0.10, day.pendingGainWheel, 0.001)
        assertEquals(5.45, day.pending, 0.001)
    }

    @Test
    fun unknownSourceFallsBackToLiveCoins() {
        store.observePending(8.0)
        store.observePending(8.50)

        assertEquals(0.50, store.day.pendingGainLive, 0.001)
    }

    @Test
    fun watchGainIsAttributedSeparately() {
        store.observePending(8.0)
        store.observePending(8.21, "watch")

        val day = store.day
        assertEquals(0.21, day.pendingGainWatch, 0.001)
        assertEquals(0.0, day.pendingGainLive, 0.001)
    }

    @Test
    fun withdrawalDefaultsOff() {
        val app = RuntimeEnvironment.getApplication()
        val settings = Settings(app)

        assertFalse(settings.withdraw)
    }

    @Test
    fun badgeVisibilityDefaultsOnAndCanBeChanged() {
        val app = RuntimeEnvironment.getApplication()
        val settings = Settings(app)

        assertTrue(settings.badgeVisible)
        settings.badgeVisible = false

        assertFalse(Settings(app).badgeVisible)
    }

    @Test
    fun twoUnavailableWheelsStartOneHourCooldown() {
        val start = 1_000_000L

        store.recordWheelUnavailable(start)
        assertEquals(1, store.day.wheelUnavailableCount)
        assertEquals(0L, store.day.wheelRetryAfter)

        store.recordWheelUnavailable(start + 1_000L)
        assertEquals(0, store.day.wheelUnavailableCount)
        assertEquals(start + 1_000L + 60 * 60 * 1000L, store.day.wheelRetryAfter)
    }

    @Test
    fun wheelCooldownBlocksRetryUntilExpiry() {
        val now = 2_000_000L
        store.day.wheelCooldownUntil = now + 10 * 60 * 1000L
        store.save()

        val restored = Store(RuntimeEnvironment.getApplication())
        assertFalse(restored.day.canTryWheel(now))
        assertTrue(restored.day.canTryWheel(now + 10 * 60 * 1000L))
    }

    @Test
    fun summaryShowsOperationTotalsAndPendingBaseline() {
        store.observePending(10.0)
        store.observePending(10.25)
        store.day.coinClaims = 1
        store.day.coinTotal = 0.25
        store.day.wheelSpins = 1
        store.day.wheelTotal = 0.15
        store.day.watchMinutes = 10
        store.day.watchTotal = 0.20

        val summary = store.summary()
        assertTrue(summary.contains("直播間蝦幣 1 次 +0.25"))
        assertTrue(summary.contains("轉盤 1 次 +0.15"))
        assertTrue(summary.contains("觀看任務 10 分鐘 +0.20"))
        assertTrue(summary.contains("提領 0/3 次 -0.00"))
        assertTrue(summary.contains("今日起始 10.00"))
        assertTrue(summary.contains("今日增加 +0.60"))
        assertFalse(summary.contains("待領新增"))
    }
}
