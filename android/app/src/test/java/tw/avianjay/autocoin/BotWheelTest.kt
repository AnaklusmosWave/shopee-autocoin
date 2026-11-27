package tw.avianjay.autocoin

import android.app.Application
import android.graphics.Rect
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
class BotWheelTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun completedWheelLeavesRoomBeforeClaimingLiveCoins() {
        val phone = RoomPhone(startOnList = true)
        val statuses = mutableListOf<String>()
        val store = runBot(phone, statuses = statuses)

        assertEquals(listOf("card", "wheel", "spin", "close"), phone.taps)
        assertEquals(1, store.day.wheelSpins)
        assertEquals(0, store.day.coinClaims)
        assertFalse(store.day.roomDone("主播"))
        assertTrue(statuses.contains("轉盤｜主播"))
        assertTrue(statuses.contains("轉盤｜已完成｜主播"))
        assertTrue(statuses.contains("等待退出直播間"))
    }

    @Test
    fun wheelOnlyVisitDoesNotMarkTheRoomDone() {
        val phone = RoomPhone(startOnList = true)
        val store = runBot(phone) {
            day.coinLimitHit = true
            day.watchDone = true
        }

        assertEquals(listOf("card", "wheel", "spin", "close"), phone.taps)
        assertTrue(store.day.room("主播").wheel)
        assertFalse(store.day.roomDone("主播"))
    }

    @Test
    fun cooldownAlsoAppliesToTheCurrentRoom() {
        val phone = RoomPhone()
        runBot(phone) {
            recordWheelUnavailable()
            recordWheelUnavailable()
        }

        assertEquals(listOf("coin"), phone.taps)
    }

    @Test
    fun successfulClaimSurvivesStoppingBeforeTheBalanceRead() {
        val phone = RoomPhone()
        val statuses = mutableListOf<String>()
        runBot(phone, statuses = statuses) { day.wheelDone = true }

        assertEquals(listOf("coin"), phone.taps)
        val saved = Store(app).day
        assertEquals(1, saved.coinClaims)
        assertEquals(0.35, saved.coinTotal, 0.001)
        assertEquals(1, Store(app).roomClaims("主播"))
        assertTrue(statuses.contains("直播間｜本房已領 1 次｜主播"))
    }

    @Test
    fun wheelIgnoresLimitTextInTheLiveChat() {
        val phone = RoomPhone(chat = "達到每日參與上限")
        val store = runBot(phone)

        assertEquals(listOf("wheel", "spin", "close"), phone.taps)
        assertFalse(store.day.wheelDone)
    }

    @Test
    fun wheelStillHonorsTheLimitToast() {
        val phone = RoomPhone(wheelToast = "達到每日參與上限")
        val store = runBot(phone)

        assertEquals(listOf("wheel", "close", "coin"), phone.taps)
        assertTrue(store.day.wheelDone)
        assertEquals(1, store.day.coinClaims)
    }

    @Test
    fun roomVisitStopsAtThreeClaimsDespiteDifferentHistoryForCardAndRoomNames() {
        val phone = RoomPhone(startOnList = true, cardName = "卡片主播", stopAfterFirstClaim = false)
        val statuses = mutableListOf<String>()
        val store = runBot(phone, statuses = statuses) {
            day.wheelDone = true
            repeat(3) { recordRoomClaim(setOf("卡片主播"), 0.6) }
            recordRoomClaim(setOf("主播"), 0.6)
        }

        assertEquals(listOf("card", "coin", "coin", "coin"), phone.taps)
        assertEquals(3, store.day.coinClaims)
        assertTrue(statuses.contains("等待退出直播間"))
    }

    @Test
    fun riskPromptAfterThirdClaimStopsBeforeLeavingTheRoom() {
        val phone = RoomPhone(stopAfterFirstClaim = false, riskAfterThirdClaim = true)
        val statuses = mutableListOf<String>()
        val store = runBot(phone, statuses = statuses) { day.wheelDone = true }

        assertEquals(3, store.day.coinClaims)
        assertTrue(phone.captures.any { it.endsWith("-risk.txt") })
        assertFalse(statuses.contains("等待退出直播間"))
    }

    private fun runBot(
        phone: RoomPhone,
        statuses: MutableList<String>? = null,
        setup: Store.() -> Unit = {},
    ): Store {
        app.getSharedPreferences("room_coin_records", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val store = Store(app)
        store.day.lastPanel = Long.MAX_VALUE
        store.observePending(0.0)
        store.setup()
        Bot(phone, store, Settings(app)) { status ->
            statuses?.add(status)
            if (status == "沒有合適的直播間，稍後再找") throw InterruptedException()
        }.run()
        return store
    }

    private class RoomPhone(
        startOnList: Boolean = false,
        private val chat: String = "",
        private val wheelToast: String = "",
        private val cardName: String = "主播",
        private val stopAfterFirstClaim: Boolean = true,
        private val riskAfterThirdClaim: Boolean = false,
    ) : Phone {
        override val width = 1080
        override val height = 2400
        val taps = mutableListOf<String>()
        val captures = mutableListOf<String>()
        private var screen = if (startOnList) Screen.EXPLORE else Screen.ROOM
        private var claimReads = 0
        private var coinTaps = 0
        private var toast = ""

        override fun snapshot(): Snap {
            val nodes = when (screen) {
                Screen.EXPLORE -> listOf(
                    node(0, "live_card_cover_0", end = 4, y = 200, height = 400),
                    node(1, "", "抽獎"),
                    node(2, "", "最多 0.6"),
                    node(3, "", cardName, y = 500),
                )
                Screen.ROOM -> {
                    val claimFrame = if (coinTaps == 0) -1 else claimReads++
                    if (stopAfterFirstClaim && claimFrame > 0) throw InterruptedException()
                    val claimed = claimFrame == 0
                    val coinState = if (claimFrame in 1..2) "00:01" else "領取"
                    val showRisk = riskAfterThirdClaim && coinTaps == 3 && claimFrame >= 3
                    listOf(
                        node(0, "tv_anchor_name", "主播"),
                        node(1, "box_view_lucky_draw", end = 3, y = 700),
                        node(2, "tv_state_btn", "參加"),
                        node(3, "box_view_coin_streamer", end = 6),
                        node(4, "tv_state_btn", coinState, y = 900),
                        node(5, "tv_coin_num", "0.35"),
                        node(6, "tv_coin_claimed_content", if (claimed) "成功領取 0.35 Coins" else ""),
                        node(7, "risk_prompt", if (showRisk) "偵測到風險活動" else ""),
                    )
                }
                Screen.WHEEL -> listOf(
                    node(0, "main_play_layout", end = 3),
                    node(1, "draw_title", "幸運轉盤"),
                    node(2, "iv_draw_light", y = 1100),
                    node(3, "public_screen_view", chat),
                    node(4, "ic_close", y = 1300),
                )
                Screen.WHEEL_RESULT -> listOf(
                    node(0, "fl_lucky_result"),
                    node(1, "tv_result", "你獲得 0.01 蝦幣！"),
                    node(2, "tv_more", "今天還有 9 次的抽獎機會。"),
                    node(3, "ic_close", y = 1300),
                )
                else -> throw AssertionError("Unexpected screen: $screen")
            }
            return Snap(nodes, SHOPEE, "")
        }

        override fun tap(p: Pt) {
            val action = when (p.y) {
                400 -> "card"
                710 -> "wheel"
                910 -> "coin"
                1110 -> "spin"
                1310 -> "close"
                else -> throw AssertionError("Unexpected tap: $p")
            }
            taps.add(action)
            when (action) {
                "card", "close" -> screen = Screen.ROOM
                "wheel" -> {
                    screen = Screen.WHEEL
                    toast = wheelToast
                }
                "spin" -> screen = Screen.WHEEL_RESULT
                "coin" -> {
                    coinTaps++
                    if (coinTaps > 3) throw AssertionError("The room visit exceeded three claims")
                    claimReads = 0
                }
            }
        }

        override fun lastToast() = toast
        override fun clearToast() { toast = "" }
        override fun checkActive() {}
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun foreground() = SHOPEE
        override fun screenOn() = true
        override fun locked() = false
        override fun back() = throw AssertionError("Unexpected back")
        override fun launchShopee(initial: Boolean) = throw AssertionError("Unexpected launch")
        override fun save(name: String, content: String) { captures.add(name) }
    }

    companion object {
        private fun node(
            index: Int,
            id: String,
            text: String = "",
            end: Int = index + 1,
            y: Int = 0,
            height: Int = 20,
        ) = N(id, text, "", Rect(0, y, 200, y + height), false, index).apply { this.end = end }
    }
}
