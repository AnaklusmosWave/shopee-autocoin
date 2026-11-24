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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorePendingTest {
    private lateinit var store: Store

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("state", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        store = Store(app)
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
    fun twoUnavailableWheelsStartOneHourCooldown() {
        val start = 1_000_000L

        store.recordWheelUnavailable(start)
        assertEquals(1, store.day.wheelUnavailableCount)
        assertEquals(0L, store.day.wheelRetryAfter)

        store.recordWheelUnavailable(start + 1_000L)
        assertEquals(0, store.day.wheelUnavailableCount)
        assertEquals(start + 1_000L + 60 * 60 * 1000L, store.day.wheelRetryAfter)
    }
}
