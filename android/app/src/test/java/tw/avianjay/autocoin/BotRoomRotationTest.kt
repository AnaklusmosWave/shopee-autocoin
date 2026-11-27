package tw.avianjay.autocoin

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoomRotationTest {
    @Test
    fun highestMaximumWinsWhenRoomsHaveNoPreviousClaims() {
        val lower = card("lower", 4.8)
        val higher = card("higher", 6.0)

        val selected = bestCoinRoom(
            listOf(lower, higher),
            claimsFor = { 0 },
            minCoins = 0.5,
            initialWaitMinutes = 6.0,
            switchSeconds = 25,
        )

        assertSame(higher, selected)
    }

    @Test
    fun lowerMaximumWinsWhenItsNextClaimIsMoreEfficient() {
        val repeatedlyClaimed = card("repeat", 6.0)
        val fresh = card("fresh", 4.8)

        val selected = bestCoinRoom(
            listOf(repeatedlyClaimed, fresh),
            claimsFor = { key -> if (key == "repeat") 3 else 0 },
            minCoins = 0.5,
            initialWaitMinutes = 6.0,
            switchSeconds = 25,
        )

        assertSame(fresh, selected)
        assertTrue(
            coinRoomEfficiency(4.8, 0) > coinRoomEfficiency(6.0, 3),
        )
    }

    private fun card(key: String, maxCoins: Double) = Card(
        rid = key,
        center = Pt(0, 0),
        height = 400,
        viewers = "",
        maxCoins = maxCoins,
        lottery = false,
        streamer = key,
        title = "",
    )
}
