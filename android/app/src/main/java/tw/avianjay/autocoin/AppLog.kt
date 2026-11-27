package tw.avianjay.autocoin

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal data class ConsoleLogEntry(val date: LocalDate, val line: String)

internal fun shouldClearConsole(lastClearDate: LocalDate?, now: LocalDateTime): Boolean {
    if (lastClearDate == null) return now.toLocalTime() >= LocalTime.of(3, 0)
    val today = now.toLocalDate()
    return lastClearDate.isBefore(today.minusDays(1)) ||
        (lastClearDate.isBefore(today) && now.toLocalTime() >= LocalTime.of(3, 0))
}

internal fun renderConsoleLines(entries: List<ConsoleLogEntry>, limit: Int): List<String> {
    val selected = entries.takeLast(limit.coerceAtLeast(0))
    val rendered = mutableListOf<String>()
    var previousDate: LocalDate? = null
    for (entry in selected) {
        if (previousDate != null && previousDate != entry.date) {
            rendered.add("--------------------")
        }
        rendered.add(entry.line)
        previousDate = entry.date
    }
    return rendered
}

/** App 內的紀錄：console 保留最近兩個日期，診斷紀錄另寫入檔案。 */
object AppLog {
    private const val TAG = "autocoin"
    private const val CONSOLE_PREFS = "console"
    private const val LAST_CLEAR_DATE = "last_clear_date"
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val lines = ArrayDeque<ConsoleLogEntry>()
    private var file: File? = null
    private var consoleFile: File? = null
    private var consolePrefs: SharedPreferences? = null
    private var loadedConsolePath: String? = null

    @Synchronized
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "autocoin.log")
        consoleFile = File(ctx.filesDir, "autocoin-console.log")
        consolePrefs = ctx.getSharedPreferences(CONSOLE_PREFS, Context.MODE_PRIVATE)
        prepareConsole(LocalDateTime.now())
    }

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String, t: Throwable? = null) = add("E", if (t == null) msg else "$msg\n${Log.getStackTraceString(t)}")
    fun d(msg: String) = add("D", msg, show = false)

    @Synchronized
    private fun add(level: String, msg: String, show: Boolean = true) {
        val now = LocalDateTime.now()
        prepareConsole(now)
        val line = "${now.format(fmt)} $level $msg"
        Log.i(TAG, line)
        if (show) {
            val entry = ConsoleLogEntry(now.toLocalDate(), line)
            lines.addLast(entry)
            appendConsole(entry)
        }
        file?.let { f ->
            try {
                if (f.length() > 2_000_000) f.renameTo(File(f.parentFile, "autocoin.old.log"))
                f.appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun recent(n: Int = 200): List<String> {
        prepareConsole(LocalDateTime.now())
        return renderConsoleLines(lines.toList(), n)
    }

    private fun prepareConsole(now: LocalDateTime) {
        val target = consoleFile ?: return
        val prefs = consolePrefs ?: return
        var needsRewrite = false
        if (loadedConsolePath != target.absolutePath) {
            lines.clear()
            if (target.exists()) {
                try {
                    RandomAccessFile(target, "r").use { file ->
                        if (file.length() > 0) {
                            file.seek(file.length() - 1)
                            needsRewrite = file.read() != '\n'.code
                        }
                    }
                    target.forEachLine { raw ->
                        try {
                            val obj = JSONObject(raw)
                            val date = LocalDate.parse(obj.getString("date"))
                            lines.addLast(ConsoleLogEntry(date, obj.getString("line")))
                        } catch (e: Exception) {
                            needsRewrite = true
                            Log.w(TAG, "略過不完整的 console 紀錄", e)
                        }
                    }
                } catch (e: Exception) {
                    needsRewrite = false
                    Log.e(TAG, "讀取 console 紀錄失敗", e)
                }
            }
            loadedConsolePath = target.absolutePath
        }

        val today = now.toLocalDate()
        val lastClearDate = prefs.getString(LAST_CLEAR_DATE, null)?.let {
            try {
                LocalDate.parse(it)
            } catch (e: Exception) {
                Log.w(TAG, "console 清除日期格式錯誤", e)
                null
            }
        }
        if (shouldClearConsole(lastClearDate, now)) {
            lines.clear()
            try {
                target.writeText("")
            } catch (e: Exception) {
                Log.e(TAG, "清除 console 紀錄失敗", e)
            }
            prefs.edit().putString(LAST_CLEAR_DATE, today.toString()).apply()
            return
        }
        if (lastClearDate == null) {
            val initialClearDate = if (now.toLocalTime() >= LocalTime.of(3, 0)) today else today.minusDays(1)
            prefs.edit().putString(LAST_CLEAR_DATE, initialClearDate.toString()).apply()
        }

        val firstAllowedDate = today.minusDays(1)
        val retained = lines.filter {
            !it.date.isBefore(firstAllowedDate) && !it.date.isAfter(today)
        }
        if (retained.size != lines.size) {
            lines.clear()
            lines.addAll(retained)
            needsRewrite = true
        }
        if (needsRewrite) rewriteConsole()
    }

    private fun appendConsole(entry: ConsoleLogEntry) {
        val target = consoleFile ?: return
        try {
            target.appendText(
                JSONObject()
                    .put("date", entry.date.toString())
                    .put("line", entry.line)
                    .toString() + "\n",
            )
        } catch (e: Exception) {
            Log.e(TAG, "寫入 console 紀錄失敗", e)
        }
    }

    private fun rewriteConsole() {
        val target = consoleFile ?: return
        val atomicFile = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            val text = lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n") {
                JSONObject()
                    .put("date", it.date.toString())
                    .put("line", it.line)
                    .toString()
            }
            stream = atomicFile.startWrite()
            stream.write(text.toByteArray())
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            stream?.let(atomicFile::failWrite)
            Log.e(TAG, "整理 console 紀錄失敗", e)
        }
    }
}
