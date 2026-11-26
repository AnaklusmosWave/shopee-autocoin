package tw.avianjay.autocoin

import android.content.Context
import kotlin.math.roundToInt
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal fun roomRecordDate(now: LocalDateTime): String =
    (if (now.toLocalTime() < LocalTime.of(3, 0)) now.toLocalDate().minusDays(1) else now.toLocalDate()).toString()

internal data class RoomCoinRecord(var maxCoins: Double?, var claims: Int)

/** 使用者可以調的設定。 */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 是否顯示螢幕上的懸浮狀態球。 */
    var badgeVisible: Boolean
        get() = prefs.getBoolean("badgeVisible", true)
        set(v) = prefs.edit().putBoolean("badgeVisible", v).apply()

    /** 自動提領（待提領 → 我的蝦幣）。 */
    var withdraw: Boolean
        get() = prefs.getBoolean("withdraw", false)
        set(v) = prefs.edit().putBoolean("withdraw", v).apply()

    val minRoomCoins = 0.5          // 卡片上「最多X」低於這個值就不考慮
    val explorePages = 3            // 「看更多」最多往下捲幾次
    val maxRoomMinutes = 75.0       // 保險：單一直播間最多待多久
    val noCoinGraceMs = 60_000L     // 「直播間蝦幣」不見多久就換台（領完、回到直播間時會暫時消失）
    val watchRoomMinutes = 10.5     // 只為觀看任務待的話，每間待多久（每場只算 10 分鐘）
    val panelEveryMinutes = 30.0    // 多久開一次面板，把觀看任務的蝦幣領進待提領
    val wheelCooldownMs = 10 * 60 * 1000L // 每次完成轉盤後，多久再嘗試下一次
    val roomClaimsPerVisit = 3      // 單次進房最多領取次數
    val roomInitialWaitMinutes = 6.0 // 第一次領取前的固定等待時間
    val roomSwitchSeconds = 25      // 切換直播間的估計耗時
    val withdrawMin = 5.0           // 待提領至少這麼多才提領；收尾那次只要 ≥1
    val withdrawDailyLimit = 3      // 蝦皮規定每天最多提領 3 次
}

class RoomStat {
    var coins = 0.0
    var done = false
    var wheel = false      // 試過轉盤
    var wheelGot = false   // 轉盤結果已經記帳
    var watched = false
}

/** 當天的統計（對應電腦版 state.py 的 Day）。 */
class Day(val date: String) {
    var coinClaims = 0
    var coinTotal = 0.0
    var coinLimitHit = false
    var wheelSpins = 0
    var wheelTotal = 0.0
    var wheelDone = false
    var wheelUnavailableCount = 0
    var wheelRetryAfter = 0L
    var wheelCooldownUntil = 0L
    var watchTotal = 0.0
    var watchMinutes = 0
    var watchDone = false
    var withdrawals = 0
    var withdrawn = 0.0
    var pending = 0.0
    var pendingBaselineSet = false
    var pendingBaseline = 0.0
    var pendingLastObserved = 0.0
    var pendingGainLive = 0.0
    var pendingGainWheel = 0.0
    var pendingGainWatch = 0.0
    var pendingGainHint: String = ""
    var lastPanel = 0L
    var finalPanel = false
    val rooms = HashMap<String, RoomStat>()

    fun room(key: String) = rooms.getOrPut(key) { RoomStat() }
    fun roomDone(key: String) = key.isNotEmpty() && rooms[key]?.done == true
    fun flag(key: String, pick: (RoomStat) -> Boolean) = rooms[key]?.let(pick) ?: false
    fun canTryWheel(now: Long) =
        !wheelDone && now >= wheelRetryAfter && now >= wheelCooldownUntil

    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("coinClaims", coinClaims); put("coinTotal", coinTotal); put("coinLimitHit", coinLimitHit)
        put("wheelSpins", wheelSpins); put("wheelTotal", wheelTotal); put("wheelDone", wheelDone)
        put("wheelUnavailableCount", wheelUnavailableCount); put("wheelRetryAfter", wheelRetryAfter)
        put("wheelCooldownUntil", wheelCooldownUntil)
        put("watchTotal", watchTotal); put("watchMinutes", watchMinutes); put("watchDone", watchDone)
        put("withdrawals", withdrawals); put("withdrawn", withdrawn); put("pending", pending)
        put("pendingBaselineSet", pendingBaselineSet); put("pendingBaseline", pendingBaseline)
        put("pendingLastObserved", pendingLastObserved)
        put("pendingGainLive", pendingGainLive); put("pendingGainWheel", pendingGainWheel)
        put("pendingGainWatch", pendingGainWatch); put("pendingGainHint", pendingGainHint)
        put("lastPanel", lastPanel); put("finalPanel", finalPanel)
        put("rooms", JSONObject().apply {
            for ((k, r) in rooms) put(k, JSONObject().apply {
                put("coins", r.coins); put("done", r.done)
                put("wheel", r.wheel); put("wheelGot", r.wheelGot); put("watched", r.watched)
            })
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = Day(o.getString("date")).apply {
            coinClaims = o.optInt("coinClaims"); coinTotal = o.optDouble("coinTotal", 0.0)
            coinLimitHit = o.optBoolean("coinLimitHit")
            wheelSpins = o.optInt("wheelSpins"); wheelTotal = o.optDouble("wheelTotal", 0.0)
            wheelDone = o.optBoolean("wheelDone")
            wheelUnavailableCount = o.optInt("wheelUnavailableCount")
            wheelRetryAfter = o.optLong("wheelRetryAfter")
            wheelCooldownUntil = o.optLong("wheelCooldownUntil")
            watchTotal = o.optDouble("watchTotal", 0.0); watchMinutes = o.optInt("watchMinutes")
            watchDone = o.optBoolean("watchDone")
            withdrawals = o.optInt("withdrawals"); withdrawn = o.optDouble("withdrawn", 0.0)
            pending = o.optDouble("pending", 0.0)
            pendingBaselineSet = o.optBoolean("pendingBaselineSet")
            pendingBaseline = o.optDouble("pendingBaseline", 0.0)
            pendingLastObserved = o.optDouble("pendingLastObserved", pending)
            pendingGainLive = o.optDouble("pendingGainLive", 0.0)
            pendingGainWheel = o.optDouble("pendingGainWheel", 0.0)
            pendingGainWatch = o.optDouble("pendingGainWatch", 0.0)
            pendingGainHint = o.optString("pendingGainHint")
            lastPanel = o.optLong("lastPanel"); finalPanel = o.optBoolean("finalPanel")
            val rs = o.optJSONObject("rooms") ?: JSONObject()
            for (k in rs.keys()) {
                val r = rs.getJSONObject(k)
                rooms[k] = RoomStat().apply {
                    coins = r.optDouble("coins", 0.0); done = r.optBoolean("done")
                    wheel = r.optBoolean("wheel"); wheelGot = r.optBoolean("wheelGot")
                    watched = r.optBoolean("watched")
                }
            }
        }
    }
}

class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val roomRecordPrefs = ctx.getSharedPreferences("room_coin_records", Context.MODE_PRIVATE)
    private var current: Day = load()
    private var roomRecordsDate = roomRecordPrefs.getString("date", roomRecordDate(LocalDateTime.now()))!!
    private val roomRecords = loadRoomRecords()

    private fun today() = LocalDate.now().toString()

    private fun loadRoomRecords(): HashMap<String, RoomCoinRecord> {
        val raw = roomRecordPrefs.getString("records", null) ?: return hashMapOf()
        return try {
            val json = JSONObject(raw)
            HashMap<String, RoomCoinRecord>().apply {
                for (key in json.keys()) {
                    val record = json.getJSONObject(key)
                    val maxCoins = if (record.has("maxCoins") && !record.isNull("maxCoins")) {
                        normalizeMaxCoins(record.optDouble("maxCoins"))
                    } else null
                    put(key, RoomCoinRecord(maxCoins, record.optInt("claims")))
                }
            }
        } catch (e: Exception) {
            AppLog.w("讀取直播間領取紀錄失敗，從頭開始：$e")
            hashMapOf()
        }
    }

    private fun ensureRoomRecordDate() {
        val date = roomRecordDate(LocalDateTime.now())
        if (roomRecordsDate != date) {
            roomRecords.clear()
            roomRecordsDate = date
            saveRoomRecords()
        }
    }

    private fun saveRoomRecords() {
        roomRecordPrefs.edit()
            .putString("date", roomRecordsDate)
            .putString("records", JSONObject().apply {
                for ((key, record) in roomRecords) put(key, JSONObject().apply {
                    record.maxCoins?.let { put("maxCoins", it) }
                    put("claims", record.claims)
                })
            }.toString())
            .apply()
    }

    private fun normalizeMaxCoins(value: Double): Double = roundStoreDiff(0.0, value)

    private fun updateRoomMax(key: String, value: Double?): Boolean {
        if (key.isEmpty() || value == null) return false
        val maxCoins = normalizeMaxCoins(value)
        val record = roomRecords[key]
        if (record == null) {
            roomRecords[key] = RoomCoinRecord(maxCoins, 0)
            return true
        }
        if (record.maxCoins == maxCoins) return false
        record.maxCoins = maxCoins
        record.claims = 0
        return true
    }

    /** 更新卡片上限；上限變動時只重置該房已領次數。 */
    @Synchronized
    fun observeRoomMax(key: String, maxCoins: Double?): Boolean {
        ensureRoomRecordDate()
        val changed = updateRoomMax(key, maxCoins)
        if (changed) saveRoomRecords()
        return changed
    }

    @Synchronized
    fun roomClaims(key: String): Int {
        ensureRoomRecordDate()
        return roomRecords[key]?.claims ?: 0
    }

    @Synchronized
    fun recordRoomClaim(keys: Set<String>, maxCoins: Double?) {
        ensureRoomRecordDate()
        val validKeys = keys.filter { it.isNotEmpty() }.toSet()
        for (key in validKeys) {
            updateRoomMax(key, maxCoins)
            val record = roomRecords.getOrPut(key) { RoomCoinRecord(null, 0) }
            record.claims++
        }
        saveRoomRecords()
    }

    private fun load(): Day {
        val raw = prefs.getString("day", null)
        val day = try {
            raw?.let { Day.fromJson(JSONObject(it)) }
        } catch (e: Exception) {
            AppLog.w("讀取統計失敗，從頭開始：$e")
            null
        }
        return if (day != null && day.date == today()) day else Day(today())
    }

    /** 換日時自動重來。 */
    @get:Synchronized
    val day: Day
        get() {
            if (current.date != today()) {
                AppLog.i("換日了：${summary()}")
                current = Day(today())
                save()
            }
            return current
        }

    @Synchronized
    fun save() {
        prefs.edit().putString("day", current.toJson().toString()).apply()
    }

    /** 記錄待領取蝦幣的正向變化；來源未知時一律歸入直播間蝦幣。 */
    @Synchronized
    fun observePending(value: Double, sourceHint: String = ""): Double {
        val day = current
        if (!day.pendingBaselineSet) {
            day.pendingBaselineSet = true
            day.pendingBaseline = value
            day.pendingLastObserved = value
        } else {
            val gain = roundStoreDiff(day.pendingLastObserved, value)
            if (gain > 0.0) {
                val source = sourceHint.ifEmpty { day.pendingGainHint }.ifEmpty { "live" }
                when (source) {
                    "wheel" -> day.pendingGainWheel = roundStoreAdd(day.pendingGainWheel, gain)
                    "watch" -> day.pendingGainWatch = roundStoreAdd(day.pendingGainWatch, gain)
                    else -> day.pendingGainLive = roundStoreAdd(day.pendingGainLive, gain)
                }
            }
            day.pendingLastObserved = value
        }
        day.pending = value
        day.pendingGainHint = ""
        save()
        return (value - day.pendingBaseline).coerceAtLeast(0.0)
    }

    fun markPendingGainHint(source: String) {
        current.pendingGainHint = source
        save()
    }

    @Synchronized
    fun recordWheelUnavailable(now: Long = System.currentTimeMillis()) {
        val day = current
        if (day.wheelRetryAfter > 0L && now >= day.wheelRetryAfter) {
            day.wheelUnavailableCount = 0
            day.wheelRetryAfter = 0L
        }
        day.wheelUnavailableCount++
        if (day.wheelUnavailableCount >= 2) {
            day.wheelRetryAfter = now + 60 * 60 * 1000L
            day.wheelUnavailableCount = 0
            AppLog.i("連續兩間直播間無法使用轉盤，暫停尋找轉盤 1 小時")
        }
        save()
    }

    private fun roundStoreDiff(a: Double, b: Double): Double =
        ((b - a) * 100).roundToInt() / 100.0

    private fun roundStoreAdd(a: Double, b: Double): Double =
        ((a + b) * 100).roundToInt() / 100.0

    fun summary(): String {
        val d = current
        val todayGain = d.coinTotal + d.wheelTotal + d.watchTotal
        return "${d.date}｜直播間蝦幣 ${d.coinClaims} 次 +%.2f｜轉盤 ${d.wheelSpins} 次 +%.2f｜觀看任務 ${d.watchMinutes} 分鐘 +%.2f｜提領 ${d.withdrawals}/3 次 -%.2f｜今日起始 %.2f｜今日增加 +%.2f｜待提領 %.2f"
            .format(
                d.coinTotal,
                d.wheelTotal,
                d.watchTotal,
                d.withdrawn,
                d.pendingBaseline,
                todayGain,
                d.pending,
            )
    }
}
