package tw.avianjay.autocoin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 無障礙服務：讀蝦皮的畫面、用手勢點座標（跟 MacroDroid 按座標一樣），
 * 並在畫面最下方放一條小狀態列（點它可以開始／停止，執行中會讓螢幕保持亮著）。
 */
class CoinService : AccessibilityService(), Phone {

    companion object {
        private const val STOPPED = "蝦幣 ■ 已停止"

        @Volatile
        var instance: CoinService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = true
    @Volatile private var startCountdownTask: Runnable? = null
    @Volatile var startCountdownSeconds = 0
        private set
    @Volatile private var toast = ""
    @Volatile private var shopeeWindowClass = ""
    @Volatile var statusText = "已停止"
        private set

    private var badge: TextView? = null
    private var badgeParams: WindowManager.LayoutParams? = null

    val running get() = worker != null && !stopRequested
    val stopping get() = worker != null && stopRequested
    val countingDown get() = startCountdownTask != null

    override fun onServiceConnected() {
        AppLog.init(this)
        instance = this
        showBadge()
        setBadgeVisible(Settings(this).badgeVisible)
        AppLog.i("無障礙服務已連線")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (event.packageName == SHOPEE && event.className?.contains("Toast") == true) {
                    toast = event.text.joinToString("")
                }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (event.packageName == SHOPEE) shopeeWindowClass = event.className?.toString() ?: ""
        }
    }

    override fun onInterrupt() = stopBot()

    override fun onUnbind(intent: Intent?): Boolean {
        stopBot()
        hideBadge()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopBot()
        hideBadge()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------- 開始 / 停止 ----------------
    fun startBot() {
        cancelStartCountdown()
        // 舊工作確實結束後才能再開，避免兩個 bot 同時操作或寫入統計。
        if (worker != null) return
        stopRequested = false
        val thread = Thread({
            val current = Thread.currentThread()
            try {
                val bot = Bot(this, Store(this), Settings(this)) { text ->
                    main.post {
                        if (worker === current && !stopRequested) setStatus(text)
                    }
                }
                bot.run()
            } finally {
                main.post {
                    if (worker === current) {
                        worker = null
                        stopRequested = true
                        showStopped()
                    }
                }
            }
        }, "autocoin")
        worker = thread
        setStatus("執行中")
        keepScreenOn(true)
        thread.start()
    }

    fun startBotWithCountdown(seconds: Int = 3) {
        if (worker != null || countingDown) return
        require(seconds > 0) { "倒數秒數必須大於 0" }
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        val task = object : Runnable {
            override fun run() {
                val remaining = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000)
                    .toInt()
                    .coerceAtLeast(0)
                if (remaining == 0) {
                    startCountdownTask = null
                    startCountdownSeconds = 0
                    startBot()
                    return
                }
                startCountdownSeconds = remaining
                statusText = "即將開始(${remaining}s)"
                badge?.apply {
                    text = "蝦幣 ▶ 即將開始(${remaining}s)"
                    isEnabled = false
                }
                main.postDelayed(this, 1000)
            }
        }
        startCountdownTask = task
        task.run()
    }

    private fun cancelStartCountdown() {
        val task = startCountdownTask ?: return
        main.removeCallbacks(task)
        startCountdownTask = null
        startCountdownSeconds = 0
        statusText = "已停止"
        badge?.apply {
            text = STOPPED
            isEnabled = true
        }
    }

    fun stopBot() {
        // interrupt 可能被 Android 的讀取 UI 流程消耗；停止狀態必須另外保留。
        cancelStartCountdown()
        stopRequested = true
        worker?.interrupt()
        if (worker == null) {
            showStopped()
        } else {
            statusText = "停止中…"
            badge?.apply {
                text = "蝦幣 ■ 停止中…"
                isEnabled = false
            }
            keepScreenOn(false)
        }
    }

    fun setBadgeVisible(visible: Boolean) {
        if (badge == null) showBadge()
        val view = badge ?: return
        val params = badgeParams ?: return
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        view.isEnabled = visible && !stopping && !countingDown
        params.flags = if (visible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        getSystemService(WindowManager::class.java).updateViewLayout(view, params)
    }

    private fun setStatus(text: String) {
        statusText = text
        badge?.apply {
            this.text = "蝦幣 ▶ $text"
            isEnabled = true
        }
    }

    private fun showStopped() {
        statusText = "已停止"
        badge?.apply {
            text = STOPPED
            isEnabled = true
        }
        keepScreenOn(false)
    }

    // ---------------- 狀態列浮窗 ----------------
    private fun showBadge() {
        if (badge != null) return
        val wm = getSystemService(WindowManager::class.java)
        val view = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(24, 6, 24, 6)
            maxLines = 1
            text = STOPPED
            setOnClickListener { if (running) stopBot() else startBot() }
        }
        // 放在最下面的手勢導覽列上，蝦皮的按鈕都不會在那裡，bot 也不會點到
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
        wm.addView(view, params)
        badge = view
        badgeParams = params
    }

    private fun hideBadge() {
        badge?.let { getSystemService(WindowManager::class.java).removeView(it) }
        badge = null
        badgeParams = null
    }

    private fun keepScreenOn(on: Boolean) {
        val view = badge ?: return
        val params = badgeParams ?: return
        params.flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        getSystemService(WindowManager::class.java).updateViewLayout(view, params)
    }

    // ---------------- Phone ----------------
    override val width: Int get() = resources.displayMetrics.widthPixels
    override val height: Int
        get() = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()

    override fun checkActive() = checkActive(Thread.currentThread())

    private fun checkActive(thread: Thread) {
        if (stopRequested || worker !== thread || thread.isInterrupted) throw InterruptedException()
    }

    // 操作與停止都在主執行緒排序；排隊中的操作執行前也要重新確認。
    private fun <T> botAction(requireShopee: Boolean = true, action: () -> T): T {
        val current = Thread.currentThread()
        checkActive(current)
        val task = FutureTask {
            checkActive(current)
            if (requireShopee && (foreground() != SHOPEE || !screenOn() || locked())) {
                throw AppInBackground()
            }
            action()
        }
        main.post(task)
        try {
            return task.get()
        } catch (e: InterruptedException) {
            task.cancel(false)
            throw e
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    override fun snapshot(): Snap {
        checkActive()
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName == SHOPEE) roots.add(root)
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) rootInActiveWindow?.takeIf { it.packageName == SHOPEE }?.let { roots.add(it) }
        val snap = Snap.build(roots, foreground(), shopeeWindowClass)
        checkActive()
        return snap
    }

    // 點擊位置與按壓時間都加抖動：不要每次都落在節點正中央的同一個像素
    override fun tap(p: Pt) {
        val j = Jitter.wobble(p, width, height)
        gesture(Path().apply { moveTo(j.x.toFloat(), j.y.toFloat()) }, Jitter.ms(60, 0.5))
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        val dx = Jitter.int(-12, 12)
        val dy = Jitter.int(-12, 12)
        gesture(Path().apply {
            moveTo((x1 + dx).toFloat(), (y1 + dy).toFloat())
            lineTo((x2 + dx).toFloat(), (y2 + dy).toFloat())
        }, Jitter.ms(ms, 0.3))
    }

    private fun gesture(path: Path, ms: Long) {
        val done = CountDownLatch(1)
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        val ok = botAction {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = done.countDown()
                override fun onCancelled(g: GestureDescription?) = done.countDown()
            }, main)
        }
        if (ok) done.await(ms + 2000, TimeUnit.MILLISECONDS)
        Thread.sleep(Jitter.ms(80, 0.6))
        checkActive()
    }

    override fun back() {
        botAction { performGlobalAction(GLOBAL_ACTION_BACK) }
        Thread.sleep(Jitter.ms(100, 0.6))
        checkActive()
    }

    override fun foreground(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    override fun screenOn() = getSystemService(PowerManager::class.java).isInteractive

    override fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun launchShopee(initial: Boolean) {
        val intent = packageManager.getLaunchIntentForPackage(SHOPEE) ?: run {
            AppLog.w("找不到蝦皮 App")
            return
        }
        botAction(requireShopee = !initial) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun lastToast() = toast

    override fun clearToast() {
        toast = ""
    }

    override fun save(name: String, content: String) {
        val dir = File(filesDir, "captures").apply { mkdirs() }
        File(dir, name).writeText(content)
        dir.listFiles()?.sortedBy { it.name }?.dropLast(100)?.forEach { it.delete() }
    }
}
