package com.example.baoyancompanion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.hwpf.HWPFDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// ==================== 通知渠道 ====================

const val CHANNEL_ID = "baoyan_reminder"

fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "保研提醒", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "任务到期、竞赛截止提醒"
            mgr.createNotificationChannel(ch)
        }
    }
}

// ==================== WorkManager 每日提醒 ====================

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        ensureNotificationChannel(ctx)
        val prefs = ctx.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE)
        val tasks = Store.loadTasks(prefs)
        val comps = Store.loadCompetitions(prefs)
        val watched = Store.loadWatched(prefs)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val todayMs = System.currentTimeMillis()

        val dueTasks = tasks.filter { !it.done && it.date.isNotBlank() && it.date <= today }
        val soonComps = comps.filter {
            try {
                val d = sdf.parse(it.deadline)?.time ?: 0L
                val daysLeft = ((d - todayMs) / (1000 * 60 * 60 * 24)).toInt()
                daysLeft in 0..7
            } catch (e: Exception) { false }
        }

        val lines = mutableListOf<String>()
        if (dueTasks.isNotEmpty()) {
            lines.add("📋 ${dueTasks.size} 个任务已到期：")
            dueTasks.take(3).forEach { lines.add("· ${it.title}") }
        }
        if (soonComps.isNotEmpty()) {
            lines.add("🏆 ${soonComps.size} 个竞赛快截止：")
            soonComps.take(3).forEach { lines.add("· ${it.name}（${it.deadline}）") }
        }
        val enrollingW = watched.filter { it.enabled && it.status == "enrolling" }
        val nearW = watched.filter { it.enabled && it.enrollEnd.isNotBlank() && Monitor.daysUntil(it.enrollEnd) in 0..7 }
        if (enrollingW.isNotEmpty()) {
            lines.add("🔔 ${enrollingW.size} 个竞赛正在报名：")
            enrollingW.take(3).forEach { lines.add("· ${it.name}${if (it.enrollEnd.isNotBlank()) "（截止 ${it.enrollEnd}）" else ""}") }
        } else if (nearW.isNotEmpty()) {
            lines.add("⏰ ${nearW.size} 个竞赛报名将截止（7 天内）：")
            nearW.take(3).forEach { lines.add("· ${it.name}（${it.enrollEnd}）") }
        }
        val reminderCount = dueTasks.size + soonComps.size + (if (enrollingW.isNotEmpty()) enrollingW.size else nearW.size)

        if (lines.isNotEmpty()) {
            val intent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("保研陪伴提醒")
                .setContentText("你有 $reminderCount 条待处理")
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try {
                val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.notify(1001, notif)
            } catch (e: Exception) { }
        }
        return Result.success()
    }
}

fun scheduleDailyReminder(context: Context) {
    val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(10, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "baoyan_daily_reminder",
        ExistingPeriodicWorkPolicy.KEEP,
        req
    )
}

// ==================== 数据模型 ====================

data class Task(val title: String, val date: String = "", val done: Boolean = false)
data class Competition(val name: String, val deadline: String = "", val note: String = "")

// achieved=false -> 只是政策里的候选规则，不计分
// achieved=true  -> 用户明确说"我拿了XX奖"，计入总分
data class Bonus(
    val item: String,
    val points: Double = 0.0,
    val category: String = "",
    val note: String = "",
    val achieved: Boolean = false
)
data class UserProfile(
    val school: String = "", val major: String = "", val grade: String = "",
    val rank: String = "", val gpa: String = "", val english: String = "",
    val research: String = "", val competitions: String = ""
)
data class ChatMessage(val role: String, val content: String)

// 论文/期刊认定目录条目
data class PaperEntry(val name: String, val level: String = "", val category: String = "", val note: String = "")

// 其他参考资料（无法归入上述类别的文件内容记录）
data class ReferenceDoc(val title: String, val summary: String = "", val kind: String = "")

// 学校认可的竞赛/加分项目录（只作参考，不计分）
data class CatalogItem(val name: String, val level: String = "", val points: Double = 0.0, val note: String = "")

// 番茄钟状态（提到 App 级，切页/重建不丢）
class PomoState {
    var left by androidx.compose.runtime.mutableStateOf(25 * 60)
    var total by androidx.compose.runtime.mutableStateOf(25 * 60)
    var running by androidx.compose.runtime.mutableStateOf(false)
    var done by androidx.compose.runtime.mutableStateOf(0)
    var finished by androidx.compose.runtime.mutableStateOf(false)
}

// ==================== 持久化 ====================

object Store {
    fun save(context: Context, tasks: List<Task>, comps: List<Competition>, bonuses: List<Bonus>,
             profile: UserProfile, messages: List<ChatMessage>, memories: List<String>,
             watched: List<WatchedCompetition>, papers: List<PaperEntry>, docs: List<ReferenceDoc>,
             catalog: List<CatalogItem>) {
        val prefs = context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE)
        val tArr = JSONArray()
        tasks.forEach { tArr.put(JSONObject().put("title", it.title).put("date", it.date).put("done", it.done)) }
        val cArr = JSONArray()
        comps.forEach { cArr.put(JSONObject().put("name", it.name).put("deadline", it.deadline).put("note", it.note)) }
        val bArr = JSONArray()
        bonuses.forEach {
            bArr.put(JSONObject()
                .put("item", it.item).put("points", it.points)
                .put("category", it.category).put("note", it.note)
                .put("achieved", it.achieved))
        }
        val pObj = JSONObject()
            .put("school", profile.school).put("major", profile.major).put("grade", profile.grade)
            .put("rank", profile.rank).put("gpa", profile.gpa).put("english", profile.english)
            .put("research", profile.research).put("competitions", profile.competitions)
        val mArr = JSONArray()
        messages.takeLast(30).forEach { mArr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val memArr = JSONArray()
        memories.forEach { memArr.put(it) }
        val wArr = JSONArray()
        watched.forEach {
            wArr.put(JSONObject()
                .put("name", it.name).put("url", it.url).put("source", it.source)
                .put("enrollStart", it.enrollStart).put("enrollEnd", it.enrollEnd)
                .put("status", it.status).put("lastChecked", it.lastChecked)
                .put("lastNote", it.lastNote).put("enabled", it.enabled))
        }
        val pArr = JSONArray()
        papers.forEach {
            pArr.put(JSONObject()
                .put("name", it.name).put("level", it.level)
                .put("category", it.category).put("note", it.note))
        }
        val dArr = JSONArray()
        docs.forEach {
            dArr.put(JSONObject()
                .put("title", it.title).put("summary", it.summary).put("kind", it.kind))
        }
        val catArr = JSONArray()
        catalog.forEach {
            catArr.put(JSONObject()
                .put("name", it.name).put("level", it.level)
                .put("points", it.points).put("note", it.note))
        }

        prefs.edit()
            .putString("tasks", tArr.toString())
            .putString("competitions", cArr.toString())
            .putString("bonuses", bArr.toString())
            .putString("profile", pObj.toString())
            .putString("chat_history", mArr.toString())
            .putString("memories", memArr.toString())
            .putString("watched", wArr.toString())
            .putString("papers", pArr.toString())
            .putString("docs", dArr.toString())
            .putString("catalog", catArr.toString())
            .apply()
    }

    fun loadTasks(prefs: SharedPreferences): List<Task> {
        val list = mutableListOf<Task>()
        try {
            val arr = JSONArray(prefs.getString("tasks", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Task(o.optString("title"), o.optString("date"), o.optBoolean("done")))
            }
        } catch (e: Exception) {}
        return list
    }

    fun loadCompetitions(prefs: SharedPreferences): List<Competition> {
        val list = mutableListOf<Competition>()
        try {
            val arr = JSONArray(prefs.getString("competitions", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Competition(o.optString("name"), o.optString("deadline"), o.optString("note")))
            }
        } catch (e: Exception) {}
        return list
    }

    fun loadBonuses(prefs: SharedPreferences): List<Bonus> {
        val list = mutableListOf<Bonus>()
        try {
            val arr = JSONArray(prefs.getString("bonuses", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Bonus(
                    o.optString("item"), o.optDouble("points", 0.0),
                    o.optString("category"), o.optString("note"),
                    o.optBoolean("achieved", false)
                ))
            }
        } catch (e: Exception) {}
        return list
    }

    fun loadProfile(prefs: SharedPreferences): UserProfile {
        try {
            val o = JSONObject(prefs.getString("profile", "{}") ?: "{}")
            return UserProfile(
                o.optString("school"), o.optString("major"), o.optString("grade"),
                o.optString("rank"), o.optString("gpa"), o.optString("english"),
                o.optString("research"), o.optString("competitions")
            )
        } catch (e: Exception) {}
        return UserProfile()
    }

    fun loadChat(prefs: SharedPreferences): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        try {
            val arr = JSONArray(prefs.getString("chat_history", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(ChatMessage(o.optString("role"), o.optString("content")))
            }
        } catch (e: Exception) {}
        return list
    }

    fun loadMemories(prefs: SharedPreferences): List<String> {
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs.getString("memories", "[]") ?: "[]")
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        } catch (e: Exception) {}
        return list
    }

    fun loadWatched(prefs: SharedPreferences): List<WatchedCompetition> {
        val list = mutableListOf<WatchedCompetition>()
        try {
            val arr = JSONArray(prefs.getString("watched", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(WatchedCompetition(
                    o.optString("name"), o.optString("url"), o.optString("source"),
                    o.optString("enrollStart"), o.optString("enrollEnd"),
                    o.optString("status", "unknown"), o.optLong("lastChecked", 0L),
                    o.optString("lastNote"), o.optBoolean("enabled", true)
                ))
            }
        } catch (e: Exception) {}
        return list
    }

    fun saveWatched(prefs: SharedPreferences, watched: List<WatchedCompetition>) {
        val wArr = JSONArray()
        watched.forEach {
            wArr.put(JSONObject()
                .put("name", it.name).put("url", it.url).put("source", it.source)
                .put("enrollStart", it.enrollStart).put("enrollEnd", it.enrollEnd)
                .put("status", it.status).put("lastChecked", it.lastChecked)
                .put("lastNote", it.lastNote).put("enabled", it.enabled))
        }
        prefs.edit().putString("watched", wArr.toString()).apply()
    }

    fun loadPapers(prefs: SharedPreferences): List<PaperEntry> {
        val list = mutableListOf<PaperEntry>()
        try {
            val arr = JSONArray(prefs.getString("papers", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(PaperEntry(o.optString("name"), o.optString("level"), o.optString("category"), o.optString("note")))
            }
        } catch (e: Exception) {}
        return list
    }

    fun loadDocs(prefs: SharedPreferences): List<ReferenceDoc> {
        val list = mutableListOf<ReferenceDoc>()
        try {
            val arr = JSONArray(prefs.getString("docs", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(ReferenceDoc(o.optString("title"), o.optString("summary"), o.optString("kind")))
            }
        } catch (e: Exception) {}
        return list
    }

    fun loadCatalog(prefs: SharedPreferences): List<CatalogItem> {
        val list = mutableListOf<CatalogItem>()
        try {
            val arr = JSONArray(prefs.getString("catalog", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(CatalogItem(o.optString("name"), o.optString("level"), o.optDouble("points", 0.0), o.optString("note")))
            }
        } catch (e: Exception) {}
        return list
    }
}

// ==================== 备份与恢复 ====================

object PrefsBackup {
    // 导出时排除 API Key，避免把密钥写进备份文件
    private const val EXCLUDE_KEY = "deepseek_key"

    fun mapToJson(map: Map<String, String?>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> if (v != null) o.put(k, v) }
        return o.toString()
    }

    fun jsonToMap(json: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        val o = JSONObject(json)
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            m[k] = o.optString(k)
        }
        return m
    }

    fun export(context: Context): String {
        val prefs = context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE)
        val m = mutableMapOf<String, String?>()
        prefs.all.forEach { (k, v) -> if (k != EXCLUDE_KEY) m[k] = v?.toString() }
        return mapToJson(m)
    }

    /** 恢复数据；不覆盖 API Key。返回写入的键数。 */
    fun restore(context: Context, json: String): Int {
        val m = jsonToMap(json).filterKeys { it != EXCLUDE_KEY }
        val prefs = context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        m.forEach { (k, v) -> ed.putString(k, v) }
        ed.apply()
        return m.size
    }
}

// ==================== 导出申报清单 ====================

object Reporter {
    fun build(context: Context): String {
        val prefs = context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE)
        return buildFrom(
            tasks = Store.loadTasks(prefs),
            comps = Store.loadCompetitions(prefs),
            bonuses = Store.loadBonuses(prefs),
            profile = Store.loadProfile(prefs),
            watched = Store.loadWatched(prefs),
            papers = Store.loadPapers(prefs),
            docs = Store.loadDocs(prefs),
            catalog = Store.loadCatalog(prefs),
            target = prefs.getString("target_date", "") ?: "",
            now = System.currentTimeMillis(),
            pomodoro = prefs.getInt("pomodoro_done", 0)
        )
    }

    fun buildFrom(
        tasks: List<Task>, comps: List<Competition>, bonuses: List<Bonus>,
        profile: UserProfile, watched: List<WatchedCompetition>,
        papers: List<PaperEntry>, docs: List<ReferenceDoc>, catalog: List<CatalogItem>,
        target: String, now: Long, pomodoro: Int = 0
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# 保研加分与规划清单")
        sb.appendLine()
        sb.appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))}")
        if (target.isNotBlank()) sb.appendLine("目标日期：$target")
        sb.appendLine()

        if (bonuses.isNotEmpty() || catalog.isNotEmpty() || watched.isNotEmpty() || tasks.isNotEmpty() || pomodoro > 0) {
            sb.appendLine("## 汇总")
            sb.appendLine("- 已获得加分：${bonuses.filter { it.achieved }.sumOf { it.points }} 分（${bonuses.count { it.achieved }} 项）")
            sb.appendLine("- 竞赛目录：${catalog.size} 项")
            sb.appendLine("- 报名监控：${watched.size} 项")
            sb.appendLine("- 任务：未完成 ${tasks.count { !it.done }} / 共 ${tasks.size}")
            if (pomodoro > 0) sb.appendLine("- 番茄钟：$pomodoro 个")
            sb.appendLine()
        }

        val profileLines = listOf(
            "学校" to profile.school, "专业" to profile.major, "年级" to profile.grade,
            "排名" to profile.rank, "绩点" to profile.gpa, "英语" to profile.english,
            "科研" to profile.research, "已有竞赛" to profile.competitions
        ).filter { it.second.isNotBlank() }
        if (profileLines.isNotEmpty()) {
            sb.appendLine("## 个人档案")
            profileLines.forEach { sb.appendLine("- ${it.first}：${it.second}") }
            sb.appendLine()
        }

        val ach = bonuses.filter { it.achieved }
        if (ach.isNotEmpty()) {
            sb.appendLine("## 已获得加分（计入总分）")
            sb.appendLine("合计：${ach.sumOf { it.points }} 分，共 ${ach.size} 项")
            val catSum = ach.filter { it.points > 0 }
                .groupBy { it.category.ifBlank { "未分类" } }
                .map { (k, v) -> k to v.sumOf { it.points } }
                .sortedByDescending { it.second }
            if (catSum.isNotEmpty()) {
                sb.appendLine("按类别：" + catSum.joinToString("；") { "${it.first} ${it.second} 分" })
            }
            ach.forEach { sb.appendLine(bonusLine(it)) }
            sb.appendLine()
        }

        if (catalog.isNotEmpty()) {
            sb.appendLine("## 竞赛目录（学校认可，共 ${catalog.size} 项）")
            catalog.forEach { c ->
                sb.appendLine("- ${c.name}${if (c.level.isNotBlank()) "｜${c.level}" else ""}${if (c.points > 0) "｜${c.points} 分" else ""}")
            }
            sb.appendLine()
        }

        if (watched.isNotEmpty()) {
            sb.appendLine("## 竞赛报名监控（${watched.size} 项）")
            watched.forEach {
                val extra = if (it.url.isNotBlank()) "｜${it.url}" else ""
                sb.appendLine("- ${it.name}｜${Monitor.statusText(it.status)}｜报名 ${it.enrollStart.ifBlank { "?" }}~${it.enrollEnd.ifBlank { "?" }}$extra")
            }
            sb.appendLine()
        }

        if (papers.isNotEmpty()) {
            sb.appendLine("## 论文 / 期刊认定目录（${papers.size} 项）")
            papers.forEach { sb.appendLine("- ${it.name}${if (it.level.isNotBlank()) "｜${it.level}" else ""}${if (it.category.isNotBlank()) "｜${it.category}" else ""}") }
            sb.appendLine()
        }

        if (comps.isNotEmpty()) {
            sb.appendLine("## 竞赛清单（手动，${comps.size} 项）")
            comps.forEach { sb.appendLine("- ${it.name}${if (it.deadline.isNotBlank()) "（截止 ${it.deadline}）" else ""}") }
            sb.appendLine()
        }

        if (tasks.isNotEmpty()) {
            sb.appendLine("## 任务（${tasks.size} 项，未完成 ${tasks.count { !it.done }}）")
            tasks.forEach { sb.appendLine("- [${if (it.done) "x" else " "}] ${it.title}${if (it.date.isNotBlank()) "（${it.date}）" else ""}") }
            sb.appendLine()
        }

        if (pomodoro > 0) {
            sb.appendLine("## 学习记录")
            sb.appendLine("- 已完成番茄钟：$pomodoro 个")
            sb.appendLine()
        }

        if (docs.isNotEmpty()) {
            sb.appendLine("## 资料库（${docs.size} 份）")
            docs.forEach { sb.appendLine("- ${it.title}${if (it.summary.isNotBlank()) "：${it.summary}" else ""}") }
            sb.appendLine()
        }
        sb.appendLine("— 由「保研陪伴」生成 —")
        return sb.toString()
    }

    private fun bonusLine(b: Bonus): String =
        "- ${b.item}${if (b.points > 0) " +${b.points}" else ""}${if (b.category.isNotBlank()) "（${b.category}）" else ""}"
}

// ==================== 文件读取工具 ====================

fun getFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) { null }
}

suspend fun readDocument(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    var result = ""
    try {
        val mime = context.contentResolver.getType(uri) ?: ""
        val name = getFileName(context, uri) ?: ""
        val ins = context.contentResolver.openInputStream(uri)
        if (ins == null) {
            result = "文件读取失败：无法打开文件流"
        } else {
            ins.use { input ->
                when {
                    mime.contains("pdf") || name.endsWith(".pdf", true) -> {
                        val doc = PDDocument.load(input)
                        try {
                            result = PDFTextStripper().getText(doc)
                        } finally {
                            doc.close()
                        }
                    }
                    mime.contains("wordprocessingml") || name.endsWith(".docx", true) -> {
                        result = readDocx(input)
                    }
                    name.endsWith(".doc", true) || mime.contains("msword") -> {
                        result = readDoc(input)
                    }
                    else -> {
                        result = input.bufferedReader().readText()
                    }
                }
            }
        }
    } catch (e: Exception) {
        result = "文件读取失败：${e.message}"
    }
    result
}

fun readDocx(ins: InputStream): String {
    var result = ""
    try {
        val zip = ZipInputStream(ins)
        var entry = zip.nextEntry
        var xml = ""
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                xml = zip.bufferedReader().readText()
                break
            }
            entry = zip.nextEntry
        }
        zip.close()
        if (xml.isNotBlank()) {
            result = xml
                .replace(Regex("</w:p>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim()
        }
    } catch (e: Exception) {
        result = "Word .docx 文件读取失败：${e.message}"
    }
    return result
}

fun readDoc(ins: InputStream): String {
    var result = ""
    try {
        val doc = HWPFDocument(ins)
        try {
            result = doc.range.text()
        } finally {
            doc.close()
        }
    } catch (e: Exception) {
        result = "Word .doc 文件读取失败：${e.message}"
    }
    return result
}

// ==================== 主 Activity ====================

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { PDFBoxResourceLoader.init(applicationContext) } catch (e: Exception) { }

        ensureNotificationChannel(this)
        scheduleDailyReminder(this)
        scheduleCompetitionCheck(this)
        requestNotifPermissionIfNeeded()
        setContent { App() }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                try {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) { }
            }
        }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE) }

    var apiKey by remember { mutableStateOf(prefs.getString("deepseek_key", "") ?: "") }
    var targetDate by remember { mutableStateOf(prefs.getString("target_date", "2028-09-01") ?: "2028-09-01") }
    var selectedTab by remember { mutableStateOf(0) }

    val tasks = remember { mutableStateListOf<Task>().apply { addAll(Store.loadTasks(prefs)) } }
    val competitions = remember { mutableStateListOf<Competition>().apply { addAll(Store.loadCompetitions(prefs)) } }
    val bonuses = remember { mutableStateListOf<Bonus>().apply { addAll(Store.loadBonuses(prefs)) } }
    val chatMessages = remember { mutableStateListOf<ChatMessage>().apply { addAll(Store.loadChat(prefs)) } }
    val memories = remember { mutableStateListOf<String>().apply { addAll(Store.loadMemories(prefs)) } }
    val watched = remember { mutableStateListOf<WatchedCompetition>().apply { addAll(Store.loadWatched(prefs)) } }
    val papers = remember { mutableStateListOf<PaperEntry>().apply { addAll(Store.loadPapers(prefs)) } }
    val docs = remember { mutableStateListOf<ReferenceDoc>().apply { addAll(Store.loadDocs(prefs)) } }
    val catalog = remember { mutableStateListOf<CatalogItem>().apply { addAll(Store.loadCatalog(prefs)) } }
    val pomo = remember { PomoState().apply { done = prefs.getInt("pomodoro_done", 0) } }
    LaunchedEffect(pomo.running, pomo.left) {
        if (pomo.running && pomo.left > 0) {
            kotlinx.coroutines.delay(1000)
            pomo.left -= 1
        } else if (pomo.running && pomo.left <= 0) {
            pomo.running = false
            pomo.done += 1
            pomo.finished = true
            prefs.edit().putInt("pomodoro_done", pomo.done).apply()
        }
    }
    var userProfile by remember { mutableStateOf(Store.loadProfile(prefs)) }

    fun persist() {
        Store.save(context, tasks.toList(), competitions.toList(), bonuses.toList(),
            userProfile, chatMessages.toList(), memories.toList(), watched.toList(),
            papers.toList(), docs.toList(), catalog.toList())
    }

    // 旧数据迁移：把“候选规则”（achieved=false 的加分项）一次性并入竞赛目录
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("catalog_migrated_v1", false)) {
            bonuses.filter { !it.achieved }.forEach { b ->
                if (catalog.none { it.name == b.item }) {
                    catalog.add(CatalogItem(b.item, level = b.category, note = b.note))
                }
            }
            bonuses.removeAll { !it.achieved }
            prefs.edit().putBoolean("catalog_migrated_v1", true).apply()
            persist()
        }
        // 首次载入内置竞赛目录（148 项）+ 评分规则
        if (!prefs.getBoolean("seed_builtin_v1", false)) {
            catalog.clear()
            catalog.addAll(DefaultData.catalog)
            DefaultData.scoreRules.forEach { r -> if (!memories.contains(r)) memories.add(r) }
            prefs.edit().putBoolean("seed_builtin_v1", true).apply()
            persist()
        }
    }

    fun reloadAll() {
        tasks.clear(); tasks.addAll(Store.loadTasks(prefs))
        competitions.clear(); competitions.addAll(Store.loadCompetitions(prefs))
        bonuses.clear(); bonuses.addAll(Store.loadBonuses(prefs))
        chatMessages.clear(); chatMessages.addAll(Store.loadChat(prefs))
        memories.clear(); memories.addAll(Store.loadMemories(prefs))
        watched.clear(); watched.addAll(Store.loadWatched(prefs))
        papers.clear(); papers.addAll(Store.loadPapers(prefs))
        docs.clear(); docs.addAll(Store.loadDocs(prefs))
        catalog.clear(); catalog.addAll(Store.loadCatalog(prefs))
        userProfile = Store.loadProfile(prefs)
        apiKey = prefs.getString("deepseek_key", "") ?: ""
        targetDate = prefs.getString("target_date", "2028-09-01") ?: "2028-09-01"
    }

    val tabs = listOf("AI 助手", "我的")
    val icons = listOf(Icons.Default.Chat, Icons.Default.Person)

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ChatScreen(
                    messages = chatMessages, apiKey = apiKey, targetDate = targetDate,
                    tasks = tasks, competitions = competitions, bonuses = bonuses,
                    memories = memories, watched = watched, papers = papers, docs = docs, catalog = catalog,
                    profile = userProfile,
                    onUpdate = { persist() }
                )
                1 -> ProfileScreen(
                    apiKey = apiKey,
                    onSaveApiKey = { apiKey = it; prefs.edit().putString("deepseek_key", it).apply() },
                    targetDate = targetDate,
                    onSaveDate = { targetDate = it; prefs.edit().putString("target_date", it).apply() },
                    profile = userProfile,
                    onSaveProfile = { userProfile = it; persist() },
                    tasks = tasks, competitions = competitions, bonuses = bonuses,
                    memories = memories,
                    watched = watched, papers = papers, docs = docs, catalog = catalog,
                    pomo = pomo,
                    onUpdate = { persist() },
                    onReload = { reloadAll() },
                    onClearChat = {
                        chatMessages.clear()
                        memories.clear()
                        persist()
                    },
                    onRemoveBonus = { idx ->
                        if (idx in bonuses.indices) {
                            bonuses.removeAt(idx)
                            persist()
                        }
                    },
                    onSaveBonus = { idx, item, pts, cat ->
                        if (item.isNotBlank()) {
                            if (idx in bonuses.indices) {
                                bonuses[idx] = bonuses[idx].copy(item = item, points = pts, category = cat)
                            } else {
                                bonuses.add(Bonus(item, pts, cat, "", true))
                            }
                            persist()
                        }
                    },
                    onRemoveTask = { idx -> if (idx in tasks.indices) { tasks.removeAt(idx); persist() } },
                    onToggleTask = { idx -> if (idx in tasks.indices) { tasks[idx] = tasks[idx].copy(done = !tasks[idx].done); persist() } },
                    onSaveTask = { idx, title, date, done ->
                        if (idx in tasks.indices) {
                            tasks[idx] = tasks[idx].copy(title = title.ifBlank { tasks[idx].title }, date = date, done = done)
                            persist()
                        } else if (title.isNotBlank()) {
                            tasks.add(Task(title, date, done))
                            persist()
                        }
                    },
                    onRemoveCompetition = { idx -> if (idx in competitions.indices) { competitions.removeAt(idx); persist() } },
                    onSaveCompetition = { idx, name, deadline, note ->
                        if (idx in competitions.indices) {
                            competitions[idx] = competitions[idx].copy(name = name.ifBlank { competitions[idx].name }, deadline = deadline, note = note)
                            persist()
                        } else if (name.isNotBlank()) {
                            competitions.add(Competition(name, deadline, note))
                            persist()
                        }
                    }
                )
            }
        }
    }
}

// ==================== 可展开数据区 ====================

@Composable
fun ExpandableSection(
    title: String, count: Int,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("$count 项", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun CatalogRowItem(c: CatalogItem, onAdd: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        val sub = listOf(c.level, DefaultData.scoreHint(c.level)).filter { it.isNotBlank() }.joinToString("｜")
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall)
        if (onAdd != null) {
            TextButton(onClick = onAdd) { Text("加入监控") }
        }
    }
}

// ==================== AI 聊天页 ====================

@Composable
fun ChatScreen(
    messages: MutableList<ChatMessage>,
    apiKey: String, targetDate: String,
    tasks: MutableList<Task>, competitions: MutableList<Competition>, bonuses: MutableList<Bonus>,
    memories: MutableList<String>, watched: MutableList<WatchedCompetition>,
    papers: MutableList<PaperEntry>, docs: MutableList<ReferenceDoc>,
    catalog: MutableList<CatalogItem>,
    profile: UserProfile, onUpdate: () -> Unit
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf("auto") }
    val importMimeTypes = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword",
        "text/plain"
    )
    val activity = context as? androidx.activity.ComponentActivity
    val scope = remember { activity?.lifecycleScope ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main) }

    fun runSend(userVisibleText: String, textForAI: String) {
        messages.add(ChatMessage("user", userVisibleText))
        loading = true
        scope.launch {
            try {
                val state = buildStateSummary(targetDate, tasks, competitions, bonuses, profile, memories, watched, papers, docs, catalog)
                val history = messages.dropLast(1).takeLast(6) + ChatMessage("user", textForAI)
                val replyRaw = callDeepSeek(apiKey, state, history)
                val (afterActions, actions) = parseActions(replyRaw)
                val (cleanText, newMemories) = parseMemory(afterActions)
                val log = applyActions(actions, tasks, competitions, bonuses, watched, papers, docs, catalog)
                newMemories.forEach { if (it.isNotBlank() && !memories.contains(it)) memories.add(it) }
                val displayText = buildString {
                    append(cleanText)
                    if (log.isNotEmpty()) { append("\n\n"); append(log.joinToString("\n")) }
                    if (newMemories.isNotEmpty()) {
                        append("\n\n💾 已记住："); append(newMemories.joinToString("；"))
                    }
                }
                messages.add(ChatMessage("assistant", displayText))
                onUpdate()
            } catch (e: Exception) {
                messages.add(ChatMessage("assistant", "❌ 出错了：${e.message}"))
            }
            loading = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && !loading) {
            val mode = importMode
            scope.launch {
                loading = true
                try {
                    val fileName = getFileName(context, uri) ?: "文件"
                    val text = readDocument(context, uri)
                    if (text.isBlank()) {
                        messages.add(ChatMessage("assistant", "❌ 读不到文件内容，可能文件是扫描图片版 PDF，无法提取文字。"))
                        loading = false
                    } else if (text.startsWith("文件读取失败") || text.startsWith("Word .doc") || text.startsWith("Word .docx")) {
                        messages.add(ChatMessage("assistant", "❌ $text"))
                        loading = false
                    } else {
                        val maxLen = 20000
                        val truncated = if (text.length > maxLen) text.substring(0, maxLen) + "\n\n【文本过长，已截断】" else text
                        if (mode == "auto") {
                            val shortMsg = "📎 上传了文件：$fileName（约 ${text.length} 字），请自动识别类型并记录。"
                            val fullPrompt = "用户上传了一份学校保研相关文件：$fileName。\n\n【文件内容】\n$truncated\n\n" +
                                    "请先判断该文件属于哪一类：①保研/加分政策或推免实施细则 ②竞赛目录 ③论文/期刊认定目录 ④其他资料。\n" +
                                    "然后按类别输出对应动作：\n" +
                                    "· 政策 → 提取评分规则/竞赛目录为 add_catalog（name/level/points 尽量填），类别上限/排名折算写入 <MEMORY>\n" +
                                    "· 竞赛目录 → 提取竞赛名称为 add_watch（不要编造 URL）\n" +
                                    "· 论文/期刊目录 → 提取条目为 add_paper\n" +
                                    "· 其他 → 用 add_reference 记录标题与一句话摘要\n" +
                                    "开头用一句话说明你的类型判断，再给出动作。"
                            loading = false
                            runSend(shortMsg, fullPrompt)
                        } else if (mode == "catalog") {
                            val shortMsg = "📎 上传了竞赛目录：$fileName（约 ${text.length} 字），请提取竞赛清单。"
                            val fullPrompt = "用户上传了一份学校认可的竞赛目录文件：$fileName。\n\n【文件内容】\n$truncated\n\n请提取其中的竞赛名称，用 add_catalog 逐项存入竞赛目录（name 必填，level/points 有则填）。"
                            loading = false
                            runSend(shortMsg, fullPrompt)
                        } else {
                            val shortMsg = "📎 上传了文件：$fileName（约 ${text.length} 字），请提取保研加分项。"
                            val fullPrompt = "用户上传了一份保研政策文件：$fileName。\n\n【文件内容】\n$truncated\n\n请提取其中的评分规则与学校认可的竞赛/加分项目录，用 add_catalog 逐项存入（每项尽量填 name、level、points，points 为纯数字单位分，查不到填 0）；类别满分上限、排名折算（如第 1 名 100%、2-3 名 60%）等文字规则用 <MEMORY> 记录。"
                            loading = false
                            runSend(shortMsg, fullPrompt)
                        }
                    }
                } catch (e: Exception) {
                    messages.add(ChatMessage("assistant", "❌ 处理文件失败：${e.message}"))
                    loading = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("保研陪伴 AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("政策文件 → 竞赛目录（参考）；说“我拿了XX奖”才计入总分", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runSend(
                        "帮我生成这周的保研准备周计划",
                        "请根据我的个人档案、任务、竞赛报名监控、竞赛目录与评分规则，生成一份可执行的【本周保研准备计划】：按天列出，每天给出具体任务与建议投入的番茄钟数；末尾给一句本周重点。"
                    )
                }) { Text("🗓️ 生成周计划") }
            }
        }

        if (messages.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("试试这样说：", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• 📎 上传政策 PDF/Word → 提取评分规则与竞赛目录", style = MaterialTheme.typography.bodySmall)
                    Text("• 我拿了数学建模省二 → 查竞赛目录，计入总分", style = MaterialTheme.typography.bodySmall)
                    Text("• 我现在加了多少分？", style = MaterialTheme.typography.bodySmall)
                    Text("• 明天下午背200个单词", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) { Text(msg.content, modifier = Modifier.padding(12.dp)) }
                }
            }
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("AI 正在处理...", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(
                    onClick = { attachMenu = true },
                    enabled = !loading && apiKey.isNotBlank()
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "导入文件")
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("智能识别导入（推荐）") },
                        onClick = {
                            attachMenu = false; importMode = "auto"
                            filePicker.launch(importMimeTypes)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("按加分政策提取") },
                        onClick = {
                            attachMenu = false; importMode = "policy"
                            filePicker.launch(importMimeTypes)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("按竞赛目录提取") },
                        onClick = {
                            attachMenu = false; importMode = "catalog"
                            filePicker.launch(importMimeTypes)
                        }
                    )
                }
            }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("跟我说点什么…") },
                modifier = Modifier.weight(1f), maxLines = 5
            )
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = {
                    if (input.isNotBlank() && !loading) {
                        val userText = input
                        input = ""
                        runSend(userText, userText)
                    }
                },
                enabled = !loading && apiKey.isNotBlank() && input.isNotBlank()
            ) { Text("发送") }
        }

        if (apiKey.isBlank()) {
            Text("请先在“我的”页面填 API Key", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun buildStateSummary(
    targetDate: String,
    tasks: List<Task>, comps: List<Competition>, bonuses: List<Bonus>, profile: UserProfile,
    memories: List<String>, watched: List<WatchedCompetition>,
    papers: List<PaperEntry>, docs: List<ReferenceDoc>,
    catalog: List<CatalogItem>
): String {
    val sb = StringBuilder()
    sb.appendLine("【当前用户状态】")
    sb.appendLine("目标日期：$targetDate")
    sb.appendLine("个人档案：学校=${profile.school}，专业=${profile.major}，年级=${profile.grade}，排名=${profile.rank}，绩点=${profile.gpa}，英语=${profile.english}，科研=${profile.research}，已有竞赛=${profile.competitions}")
    sb.appendLine("任务：${tasks.size} 条，未完成 ${tasks.count { !it.done }} 条")
    if (tasks.isNotEmpty()) sb.appendLine("任务列表：${tasks.take(8).joinToString("；") { "${it.title}${if (it.date.isNotBlank()) "(${it.date})" else ""}" }}")
    if (comps.isNotEmpty()) sb.appendLine("竞赛列表：${comps.joinToString("；") { it.name }}")
    if (watched.isNotEmpty()) {
        sb.appendLine("竞赛监控清单（${watched.size} 项）：")
        watched.take(15).forEach { w ->
            sb.appendLine("· ${w.name}｜状态=${Monitor.statusText(w.status)}｜报名=${w.enrollStart.ifBlank { "?" }}~${w.enrollEnd.ifBlank { "?" }}｜网址=${w.url.ifBlank { "未设置" }}")
        }
    }
    if (papers.isNotEmpty()) sb.appendLine("论文认定目录：${papers.size} 条（例：${papers.take(3).joinToString("；") { it.name }}）")
    if (docs.isNotEmpty()) sb.appendLine("资料库：${docs.size} 份（${docs.take(3).joinToString("；") { it.title }}）")

    val achievedSum = bonuses.filter { it.achieved }.sumOf { it.points }
    val achievedCount = bonuses.count { it.achieved }
    sb.appendLine("已获得加分：$achievedSum 分（$achievedCount 项）")
    if (catalog.isNotEmpty()) {
        sb.appendLine("竞赛目录（学校认可，共 ${catalog.size} 项，格式 名称(类别)）：")
        sb.appendLine(catalog.joinToString("；") { "${it.name}(${it.level.ifBlank { "?" }})" })
    }

    if (memories.isNotEmpty()) {
        sb.appendLine("【长期记忆】")
        memories.takeLast(20).forEach { sb.appendLine("· $it") }
    }
    return sb.toString()
}

suspend fun callDeepSeek(apiKey: String, stateSummary: String, messages: List<ChatMessage>): String =
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()

            val systemPrompt = """
                你是"保研陪伴"App的核心智能体。你既是保研顾问，也是本App的数据管家。

                $stateSummary

                【核心概念：竞赛目录 vs 已获得加分】
                - 竞赛目录 = 学校认可的竞赛/加分项清单（来自政策文件），只作参考，不计分
                - 已获得加分 = 用户明确说"我参加了/拿了XX"的项，这些才计入总分
                - 分值优先取竞赛目录里对应项；目录里没有就按用户说明，说不清先用 0 并请用户确认

                【结构化操作】
                需要"记录/添加/删除/修改"数据时，在回答的最后另起一行输出：
                <ACTIONS>
                [{"type":"add_catalog","name":"全国大学生数学建模竞赛","level":"A类","points":4.0}]
                </ACTIONS>

                支持的 type：
                - add_task: {title, date(可空)}
                - complete_task: {keyword}
                - delete_task: {keyword}
                - clear_tasks: {}
                - add_competition: {name, deadline(可空), note(可空)}
                - delete_competition: {keyword}
                - clear_competitions: {}
                - add_catalog: {name, level(可空), points(可空), note(可空)} -- 加入竞赛目录（学校认可的竞赛/加分项）
                - clear_catalog: {}                             -- 清空竞赛目录
                - add_bonus: {item, points, category}           -- 记录"已获得"的加分（计入总分）
                - delete_bonus: {keyword}
                - clear_bonuses: {}
                - add_watch: {name, url(可空), deadline(可空)}  -- 加入竞赛报名监控清单
                - set_watch_url: {keyword, url}                 -- 为监控竞赛设置官网/信息页网址
                - remove_watch: {keyword}                       -- 从监控清单移除
                - check_watch_now: {keyword}                    -- 用户要求立即检查某竞赛报名
                - add_paper: {name, level(可空), category(可空), note(可空)} -- 论文/期刊认定目录条目
                - clear_papers: {}                              -- 清空论文目录
                - add_reference: {title, summary(可空), kind(可空)} -- 其他资料记录
                - clear_references: {}                          -- 清空资料库
                - edit_task: {keyword, title(可空), date(可空), done(可空)}        -- 修改已有任务
                - edit_competition: {keyword, name(可空), deadline(可空), note(可空)}
                - edit_bonus: {keyword, item(可空), points(可空), category(可空), note(可空)}
                - edit_watch: {keyword, name(可空), url(可空), deadline(可空)}    -- 修改监控项

                【长期记忆】
                用户告诉你重要信息时（保研目标、个人偏好等），在回答最后输出：
                <MEMORY>简洁信息</MEMORY>

                【重要规则】
                - 用户上传政策文件时，提取评分规则与竞赛目录为 add_catalog（每项填 name、level、points）；类别满分上限、排名折算等文字规则写入 <MEMORY>
                - 用户上传竞赛目录/学科竞赛目录时，提取竞赛名称为 add_catalog
                - 用户上传文件但未说明类型时：先判断类型（保研/加分政策、竞赛目录、论文或期刊认定目录、其他资料），再按类型输出动作：政策→add_catalog；竞赛目录→add_catalog；论文目录→add_paper；其他→add_reference。开头用一句话说明判断结果。
                - 用户说“帮我盯一下XX竞赛报名”时，用 add_watch；若用户给了网址，用 set_watch_url
                - 用户要求“修改/改成/更正/改一下”已有数据时，用 edit_task / edit_competition / edit_bonus / edit_watch；keyword 用能唯一定位的片段
                - 用户说“我参加了/拿了XX”时：先在竞赛目录里找匹配项，按类别与获奖等级算出分值；**先用一句话复述“按 X 类、Y 等奖 → Z 分”，并问“确认计入吗？”，本轮不要 add_bonus**。只有用户明确确认（如“确认/对/是”）后，才输出 add_bonus 计入总分。目录里找不到该项时，说明并请用户补充。
                - 用户问"我现在加了多少分" → 基于 state summary 里的"已获得加分"回答
                - <ACTIONS> 和 <MEMORY> 放回答最后

                【回答风格】
                简洁、专业。政策提取后只给一句总结（如“已提取 X 条评分项/竞赛目录”），不要罗列。
            """.trimIndent()

            val jsonMessages = JSONArray()
            jsonMessages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.forEach { msg ->
                jsonMessages.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }

            val body = JSONObject()
                .put("model", "deepseek-chat")
                .put("messages", jsonMessages)
                .put("temperature", 0.6)
                .put("max_tokens", 8000)
                .toString()

            val request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "无响应"
            if (!response.isSuccessful) return@withContext "请求失败：${response.code}\n$responseBody"

            JSONObject(responseBody).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            "出错了：${e.message}"
        }
    }

fun parseActions(reply: String): Pair<String, List<JSONObject>> {
    try {
        val start = reply.indexOf("<ACTIONS>")
        if (start < 0) return Pair(reply, emptyList())
        val endIdx = reply.indexOf("</ACTIONS>")
        val jsonStart = start + "<ACTIONS>".length

        var jsonStr: String
        val cleanText: String
        if (endIdx > start) {
            jsonStr = reply.substring(jsonStart, endIdx).trim()
            cleanText = (reply.substring(0, start) + reply.substring(endIdx + "</ACTIONS>".length)).trim()
        } else {
            jsonStr = reply.substring(jsonStart).trim()
            cleanText = reply.substring(0, start).trim()
        }

        val arrStart = jsonStr.indexOf('[')
        if (arrStart < 0) return Pair(cleanText, emptyList())
        var arrEnd = jsonStr.lastIndexOf(']')
        if (arrEnd < 0) {
            val lastBrace = jsonStr.lastIndexOf('}')
            if (lastBrace < 0) return Pair(cleanText, emptyList())
            jsonStr = jsonStr.substring(0, lastBrace + 1) + "]"
            arrEnd = jsonStr.length - 1
        }

        val cleanJson = jsonStr.substring(arrStart, arrEnd + 1)
        val arr = JSONArray(cleanJson)
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return Pair(cleanText, list)
    } catch (e: Exception) {
        val start = reply.indexOf("<ACTIONS>")
        return if (start >= 0) Pair(reply.substring(0, start).trim(), emptyList())
        else Pair(reply, emptyList())
    }
}

fun parseMemory(reply: String): Pair<String, List<String>> {
    val memories = mutableListOf<String>()
    var clean = reply
    try {
        val regex = Regex("<MEMORY>(.*?)</MEMORY>", RegexOption.DOT_MATCHES_ALL)
        regex.findAll(reply).forEach { m ->
            val content = m.groupValues[1].trim()
            if (content.isNotBlank()) memories.add(content)
        }
        clean = reply.replace(regex, "").trim()
    } catch (e: Exception) { }
    return Pair(clean, memories)
}

fun applyActions(
    actions: List<JSONObject>,
    tasks: MutableList<Task>, comps: MutableList<Competition>, bonuses: MutableList<Bonus>,
    watched: MutableList<WatchedCompetition>,
    papers: MutableList<PaperEntry>, docs: MutableList<ReferenceDoc>,
    catalog: MutableList<CatalogItem>
): List<String> {
    val log = mutableListOf<String>()
    for (a in actions) {
        try {
            when (a.optString("type")) {
                "add_task" -> {
                    val title = a.optString("title")
                    if (title.isNotBlank()) {
                        tasks.add(Task(title, a.optString("date")))
                        log.add("✅ 已添加任务：$title")
                    }
                }
                "complete_task" -> {
                    val kw = a.optString("keyword")
                    val idx = tasks.indexOfFirst { it.title.contains(kw) && !it.done }
                    if (idx >= 0) {
                        tasks[idx] = tasks[idx].copy(done = true)
                        log.add("✅ 已完成任务：${tasks[idx].title}")
                    }
                }
                "delete_task" -> {
                    val kw = a.optString("keyword")
                    val idx = tasks.indexOfFirst { it.title.contains(kw) }
                    if (idx >= 0) {
                        log.add("🗑️ 已删除任务：${tasks[idx].title}")
                        tasks.removeAt(idx)
                    }
                }
                "clear_tasks" -> { tasks.clear(); log.add("🗑️ 已清空所有任务") }
                "add_competition" -> {
                    val name = a.optString("name")
                    if (name.isNotBlank()) {
                        comps.add(Competition(name, a.optString("deadline"), a.optString("note")))
                        log.add("🏆 已记录竞赛：$name")
                    }
                }
                "delete_competition" -> {
                    val kw = a.optString("keyword")
                    val idx = comps.indexOfFirst { it.name.contains(kw) }
                    if (idx >= 0) {
                        log.add("🗑️ 已删除竞赛：${comps[idx].name}")
                        comps.removeAt(idx)
                    } else {
                        log.add("⚠️ 未找到匹配竞赛：$kw")
                    }
                }
                "clear_competitions" -> { comps.clear(); log.add("🗑️ 已清空竞赛列表") }
                "add_bonus" -> {
                    val item = a.optString("item")
                    if (item.isNotBlank()) {
                        val pts = a.optDouble("points", 0.0)
                        val ach = a.optBoolean("achieved", true)
                        bonuses.add(Bonus(item, pts, a.optString("category"), a.optString("note"), ach))
                        if (ach) {
                            log.add("⭐ 已获得加分：$item +$pts 分（计入总分）")
                        } else {
                            log.add("📋 已加入候选规则：$item +$pts 分（未获得，暂不计分）")
                        }
                    }
                }
                "mark_bonus_achieved" -> {
                    val kw = a.optString("keyword")
                    val idx = bonuses.indexOfFirst { it.item.contains(kw) && !it.achieved }
                    if (idx >= 0) {
                        bonuses[idx] = bonuses[idx].copy(achieved = true)
                        log.add("⭐ 已标记为获得：${bonuses[idx].item} +${bonuses[idx].points} 分（计入总分）")
                    } else {
                        val idx2 = bonuses.indexOfFirst { it.item.contains(kw) }
                        if (idx2 >= 0) log.add("ℹ️ 已获得，无需重复标记：${bonuses[idx2].item}")
                        else log.add("⚠️ 规则库中未找到匹配项：$kw")
                    }
                }
                "unmark_bonus_achieved" -> {
                    val kw = a.optString("keyword")
                    val idx = bonuses.indexOfFirst { it.item.contains(kw) && it.achieved }
                    if (idx >= 0) {
                        bonuses[idx] = bonuses[idx].copy(achieved = false)
                        log.add("↩️ 已取消获得：${bonuses[idx].item}")
                    }
                }
                "delete_bonus" -> {
                    val kw = a.optString("keyword")
                    val idx = bonuses.indexOfFirst { it.item.contains(kw) }
                    if (idx >= 0) {
                        log.add("🗑️ 已删除加分项：${bonuses[idx].item}")
                        bonuses.removeAt(idx)
                    }
                }
                "clear_bonuses" -> { bonuses.clear(); log.add("🗑️ 已清空加分记录") }
                "add_watch" -> {
                    val name = a.optString("name")
                    if (name.isNotBlank()) {
                        val url = a.optString("url")
                        val dl = Monitor.normDate(a.optString("deadline"))
                        val idx = watched.indexOfFirst { it.name == name }
                        if (idx >= 0) {
                            watched[idx] = watched[idx].copy(
                                url = url.ifBlank { watched[idx].url },
                                enrollEnd = dl.ifBlank { watched[idx].enrollEnd }
                            )
                            log.add("🔔 已更新监控：$name")
                        } else {
                            watched.add(WatchedCompetition(name = name, url = url, enrollEnd = dl))
                            log.add("🔔 已加入监控清单：$name")
                        }
                    }
                }
                "set_watch_url" -> {
                    val kw = a.optString("keyword"); val url = a.optString("url")
                    val idx = watched.indexOfFirst { it.name.contains(kw) }
                    if (idx >= 0 && url.isNotBlank()) {
                        watched[idx] = watched[idx].copy(url = url, source = "用户设置")
                        log.add("🔗 已设置监控网址：${watched[idx].name}")
                    } else log.add("⚠️ 未找到匹配竞赛：$kw")
                }
                "remove_watch" -> {
                    val kw = a.optString("keyword")
                    val idx = watched.indexOfFirst { it.name.contains(kw) }
                    if (idx >= 0) {
                        log.add("🗑️ 已移出监控：${watched[idx].name}")
                        watched.removeAt(idx)
                    } else log.add("⚠️ 未找到匹配竞赛：$kw")
                }
                "check_watch_now" -> {
                    log.add("🔄 已请求检查监控竞赛，请到“我的”页查看最新结果")
                }
                "add_paper" -> {
                    val name = a.optString("name")
                    if (name.isNotBlank()) {
                        papers.add(PaperEntry(name, a.optString("level"), a.optString("category"), a.optString("note")))
                        log.add("📄 已记录论文目录：$name")
                    }
                }
                "clear_papers" -> { papers.clear(); log.add("🗑️ 已清空论文目录") }
                "add_reference" -> {
                    val title = a.optString("title")
                    if (title.isNotBlank()) {
                        docs.add(ReferenceDoc(title, a.optString("summary"), a.optString("kind")))
                        log.add("📁 已收录资料：$title")
                    }
                }
                "clear_references" -> { docs.clear(); log.add("🗑️ 已清空资料库") }
                "edit_task" -> {
                    val kw = a.optString("keyword")
                    val idx = tasks.indexOfFirst { it.title.contains(kw) }
                    if (idx >= 0) {
                        val t = tasks[idx]
                        tasks[idx] = t.copy(
                            title = a.optString("title").ifBlank { t.title },
                            date = if (a.has("date")) a.optString("date") else t.date,
                            done = if (a.has("done")) a.optBoolean("done", t.done) else t.done
                        )
                        log.add("✏️ 已修改任务：${tasks[idx].title}")
                    } else log.add("⚠️ 未找到匹配任务：$kw")
                }
                "edit_competition" -> {
                    val kw = a.optString("keyword")
                    val idx = comps.indexOfFirst { it.name.contains(kw) }
                    if (idx >= 0) {
                        val c = comps[idx]
                        comps[idx] = c.copy(
                            name = a.optString("name").ifBlank { c.name },
                            deadline = if (a.has("deadline")) a.optString("deadline") else c.deadline,
                            note = if (a.has("note")) a.optString("note") else c.note
                        )
                        log.add("✏️ 已修改竞赛：${comps[idx].name}")
                    } else log.add("⚠️ 未找到匹配竞赛：$kw")
                }
                "edit_bonus" -> {
                    val kw = a.optString("keyword")
                    val idx = bonuses.indexOfFirst { it.item.contains(kw) }
                    if (idx >= 0) {
                        val b = bonuses[idx]
                        bonuses[idx] = b.copy(
                            item = a.optString("item").ifBlank { b.item },
                            points = if (a.has("points")) a.optDouble("points", b.points) else b.points,
                            category = if (a.has("category")) a.optString("category") else b.category,
                            note = if (a.has("note")) a.optString("note") else b.note
                        )
                        log.add("✏️ 已修改加分项：${bonuses[idx].item}")
                    } else log.add("⚠️ 未找到匹配加分项：$kw")
                }
                "edit_watch" -> {
                    val kw = a.optString("keyword")
                    val idx = watched.indexOfFirst { it.name.contains(kw) }
                    if (idx >= 0) {
                        val w = watched[idx]
                        watched[idx] = w.copy(
                            name = a.optString("name").ifBlank { w.name },
                            url = if (a.has("url")) a.optString("url") else w.url,
                            enrollEnd = if (a.has("deadline")) Monitor.normDate(a.optString("deadline")) else w.enrollEnd
                        )
                        log.add("✏️ 已修改监控：${watched[idx].name}")
                    } else log.add("⚠️ 未找到匹配竞赛：$kw")
                }
                "add_catalog" -> {
                    val name = a.optString("name")
                    if (name.isNotBlank()) {
                        val idx = catalog.indexOfFirst { it.name == name }
                        if (idx >= 0) {
                            catalog[idx] = catalog[idx].copy(
                                level = a.optString("level").ifBlank { catalog[idx].level },
                                points = if (a.has("points")) a.optDouble("points", catalog[idx].points) else catalog[idx].points,
                                note = if (a.has("note")) a.optString("note") else catalog[idx].note
                            )
                            log.add("🏅 已更新竞赛目录：$name")
                        } else {
                            catalog.add(CatalogItem(name, a.optString("level"), a.optDouble("points", 0.0), a.optString("note")))
                            log.add("🏅 已加入竞赛目录：$name")
                        }
                    }
                }
                "clear_catalog" -> { catalog.clear(); log.add("🗑️ 已清空竞赛目录") }
            }
        } catch (e: Exception) { log.add("⚠️ 一条操作执行失败") }
    }
    return log
}

// ==================== 我的页 ====================

@Composable
fun ProfileScreen(
    apiKey: String, onSaveApiKey: (String) -> Unit,
    targetDate: String, onSaveDate: (String) -> Unit,
    profile: UserProfile, onSaveProfile: (UserProfile) -> Unit,
    tasks: MutableList<Task>, competitions: MutableList<Competition>, bonuses: List<Bonus>,
    memories: MutableList<String>,
    watched: MutableList<WatchedCompetition>,
    papers: MutableList<PaperEntry>, docs: MutableList<ReferenceDoc>,
    catalog: MutableList<CatalogItem>,
    pomo: PomoState,
    onUpdate: () -> Unit,
    onReload: () -> Unit,
    onClearChat: () -> Unit,
    onRemoveBonus: (Int) -> Unit,
    onSaveBonus: (Int, String, Double, String) -> Unit,
    onRemoveTask: (Int) -> Unit,
    onToggleTask: (Int) -> Unit,
    onSaveTask: (Int, String, String, Boolean) -> Unit,
    onRemoveCompetition: (Int) -> Unit,
    onSaveCompetition: (Int, String, String, String) -> Unit
) {
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf(apiKey) }
    var dateInput by remember { mutableStateOf(targetDate) }
    var profileInput by remember { mutableStateOf(profile) }

    val achievedSum = bonuses.filter { it.achieved }.sumOf { it.points }
    val achievedCount = bonuses.count { it.achieved }
    val catalogCount = catalog.size
    val byCat = bonuses.filter { it.achieved && it.points > 0 }
        .groupBy { it.category.ifBlank { "未分类" } }
        .map { (k, v) -> k to v.sumOf { it.points } }
        .sortedByDescending { it.second }
    val watchCount = watched.size
    val paperCount = papers.size
    val docCount = docs.size
    var addDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var backupMsg by remember { mutableStateOf("") }
    var catQuery by remember { mutableStateOf("") }
    var watchQuery by remember { mutableStateOf("") }
    var checkingName by remember { mutableStateOf("") }
    var bonusSort by remember { mutableStateOf("none") }
    var bonusEditIdx by remember { mutableStateOf(-99) }
    var tTitle by remember { mutableStateOf("") }
    var tDate by remember { mutableStateOf("") }
    var taskEditIdx by remember { mutableStateOf(-99) }
    var cName by remember { mutableStateOf("") }
    var cDeadline by remember { mutableStateOf("") }
    var cNote by remember { mutableStateOf("") }
    var compEditIdx by remember { mutableStateOf(-99) }
    var bItemInput by remember { mutableStateOf("") }
    var bPointsInput by remember { mutableStateOf("") }
    var bCatInput by remember { mutableStateOf("") }
    var intervalHours by remember {
        mutableStateOf(context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE).getInt("check_interval_hours", 12))
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(PrefsBackup.export(context).toByteArray(Charsets.UTF_8))
                }
                backupMsg = "✅ 备份已导出"
            } catch (e: Exception) {
                backupMsg = "导出失败：${e.message}"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val txt = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (txt.isBlank()) {
                    backupMsg = "文件为空或不可读"
                } else {
                    val n = PrefsBackup.restore(context, txt)
                    onReload()
                    backupMsg = "✅ 已恢复 $n 项数据"
                }
            } catch (e: Exception) {
                backupMsg = "导入失败：${e.message}"
            }
        }
    }
    val reportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(Reporter.build(context).toByteArray(Charsets.UTF_8))
                }
                backupMsg = "✅ 申报清单已导出"
            } catch (e: Exception) {
                backupMsg = "导出清单失败：${e.message}"
            }
        }
    }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkMsg by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("当前状态", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("目标日期：$targetDate", style = MaterialTheme.typography.bodySmall)
                Text("待办：${tasks.count { !it.done }}/${tasks.size}", style = MaterialTheme.typography.bodySmall)
                Text("竞赛：${competitions.size} 项", style = MaterialTheme.typography.bodySmall)
                Text("已获得加分：$achievedSum 分（$achievedCount 项）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("竞赛目录：$catalogCount 项（参考，不计分）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (byCat.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("按类别：", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    byCat.forEach { (cat, sum) ->
                        Text("· $cat：${sum} 分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        ExpandableSection("📋 任务列表", tasks.size) {
            if (tasks.isEmpty()) {
                Text("AI 还没帮你记录任务，也可手动添加。", style = MaterialTheme.typography.bodySmall)
            } else {
                tasks.forEachIndexed { idx, t ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (t.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).clickable { onToggleTask(idx) },
                                tint = if (t.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (t.done) FontWeight.Normal else FontWeight.Medium)
                                if (t.date.isNotBlank()) {
                                    Text("截止：${t.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                            TextButton(onClick = { taskEditIdx = idx; tTitle = t.title; tDate = t.date }) { Text("编辑") }
                            TextButton(onClick = { onRemoveTask(idx) }) { Text("删除") }
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = { taskEditIdx = -1; tTitle = ""; tDate = "" }) { Text("＋ 添加任务") }
                if (tasks.any { it.done }) {
                    TextButton(onClick = { tasks.removeAll { it.done }; onUpdate() }) { Text("清除已完成") }
                }
            }
        }

        ExpandableSection("🏆 竞赛清单（手动添加）", competitions.size) {
            if (competitions.isEmpty()) {
                Text("在 AI 里说“我想参加数学建模”，也可手动添加。", style = MaterialTheme.typography.bodySmall)
            } else {
                competitions.forEachIndexed { idx, c ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (c.deadline.isNotBlank()) {
                            Text("截止：${c.deadline}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Row {
                            TextButton(onClick = {
                                if (watched.none { it.name == c.name }) {
                                    watched.add(WatchedCompetition(name = c.name, enrollEnd = c.deadline, source = "手动"))
                                    onUpdate()
                                }
                            }) { Text("加入监控") }
                            TextButton(onClick = { compEditIdx = idx; cName = c.name; cDeadline = c.deadline; cNote = c.note }) { Text("编辑") }
                            TextButton(onClick = { onRemoveCompetition(idx) }) { Text("删除") }
                        }
                    }
                }
            }
            TextButton(onClick = { compEditIdx = -1; cName = ""; cDeadline = ""; cNote = "" }) { Text("＋ 添加竞赛") }
        }

        ExpandableSection("🔔 竞赛报名监控", watchCount) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (!checking && apiKey.isNotBlank()) {
                            checking = true
                            checkMsg = "检查中…"
                            scope.launch {
                                try {
                                    val res = Monitor.checkAll(context, apiKey, watched)
                                    watched.clear(); watched.addAll(res.first)
                                    onUpdate()
                                    res.second.forEachIndexed { i, p -> Monitor.notify(context, p.first, p.second, 2000 + i) }
                                    checkMsg = if (res.second.isEmpty()) "检查完成，暂无新变化（共 ${watched.size} 项）"
                                    else "检查完成，产生 ${res.second.size} 条提醒"
                                } catch (e: Exception) {
                                    checkMsg = "检查失败：${e.message}"
                                }
                                checking = false
                            }
                        }
                    },
                    enabled = !checking && apiKey.isNotBlank()
                ) { Text(if (checking) "检查中…" else "立即检查全部") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = { addDialog = true }) { Text("➕ 添加") }
            }
            if (checkMsg.isNotBlank()) {
                Text(checkMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (watched.size > 4) {
                OutlinedTextField(value = watchQuery, onValueChange = { watchQuery = it }, label = { Text("搜索监控项") }, modifier = Modifier.fillMaxWidth())
            }
            val watchList = if (watchQuery.isBlank()) watched else watched.filter { it.name.contains(watchQuery, true) }
            if (watched.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("在 AI 里说“帮我盯一下数学建模报名”，或从“竞赛目录/竞赛清单”加入监控。", style = MaterialTheme.typography.bodySmall)
            } else {
                watchList.forEach { w ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            AssistChip(onClick = { }, label = { Text(Monitor.statusText(w.status), style = MaterialTheme.typography.labelSmall) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(checked = w.enabled, onCheckedChange = { on ->
                                val i = watched.indexOfFirst { it.name == w.name }
                                if (i >= 0) { watched[i] = watched[i].copy(enabled = on); onUpdate() }
                            })
                        }
                        Text("报名：${w.enrollStart.ifBlank { "?" }} ~ ${w.enrollEnd.ifBlank { "?" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        if (w.lastChecked > 0) Text("上次检查：${Monitor.fmtTime(w.lastChecked)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        if (w.lastChecked > 0 && w.enabled) {
                            val hrs = context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE).getInt("check_interval_hours", 12)
                            Text("下次约：${Monitor.fmtTime(w.lastChecked + hrs * 3600000L)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (w.url.isNotBlank()) Text("来源：${w.url}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        if (w.lastNote.isNotBlank()) Text(w.lastNote, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                if (apiKey.isNotBlank()) {
                                    checkMsg = "正在为「${w.name}」探测官网…"
                                    scope.launch {
                                        try {
                                            val u = Monitor.discoverUrl(apiKey, w.name)
                                            val idx = watched.indexOfFirst { it.name == w.name }
                                            if (idx >= 0 && u.isNotBlank()) {
                                                watched[idx] = watched[idx].copy(url = u, source = "AI 探测")
                                                onUpdate()
                                                checkMsg = "已找到官网：$u"
                                            } else {
                                                checkMsg = "未自动找到可验证的官网，请手动设置"
                                            }
                                        } catch (e: Exception) {
                                            checkMsg = "探测失败：${e.message}"
                                        }
                                    }
                                }
                            }) { Text("找官网") }
                            TextButton(onClick = {
                                if (apiKey.isNotBlank() && checkingName != w.name) {
                                    checkingName = w.name
                                    checkMsg = "正在检查「${w.name}」…"
                                    scope.launch {
                                        try {
                                            val o = Monitor.checkOne(context, apiKey, w)
                                            val idx = watched.indexOfFirst { it.name == w.name }
                                            if (idx >= 0) { watched[idx] = o.updated; onUpdate() }
                                            o.alerts.forEachIndexed { i, p -> Monitor.notify(context, p.first, p.second, 3000 + i) }
                                            checkMsg = "「${w.name}」${o.updated.lastNote.ifBlank { "检查完成" }}"
                                        } catch (e: Exception) {
                                            checkMsg = "「${w.name}」检查失败：${e.message}"
                                        }
                                        checkingName = ""
                                    }
                                }
                            }) { Text(if (checkingName == w.name) "检查中…" else "检查") }
                            TextButton(onClick = {
                                if (w.url.isNotBlank()) {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w.url)))
                                    } catch (e: Exception) { checkMsg = "无法打开：${e.message}" }
                                } else checkMsg = "该竞赛还没有网址"
                            }) { Text("打开") }
                            TextButton(onClick = {
                                editName = w.name; nameInput = w.name; urlInput = w.url; editDialog = true
                            }) { Text("编辑") }
                            TextButton(onClick = {
                                watched.removeAll { it.name == w.name }
                                onUpdate()
                            }) { Text("移除") }
                        }
                        HorizontalDivider()
                    }
                }
                Text("💡 报名开始 / 临近截止会自动推送通知；数据来自所设网址。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                TextButton(onClick = { watched.clear(); onUpdate(); checkMsg = "已清空监控" }) { Text("清空监控") }
            }
        }

        ExpandableSection("📄 论文 / 期刊认定目录", paperCount) {
            if (papers.isEmpty()) {
                Text("上传学校的论文/期刊认定目录，AI 会自动识别并记录。", style = MaterialTheme.typography.bodySmall)
            } else {
                papers.forEach { p ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        val sub = listOf(p.level, p.category).filter { it.isNotBlank() }.joinToString(" / ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { papers.clear(); onUpdate() }) { Text("清空论文目录") }
            }
        }

        ExpandableSection("📁 资料库（其他文件）", docCount) {
            if (docs.isEmpty()) {
                Text("无法归类的保研文件会记录在这里；也可在 AI 里说“把这份文件记到资料库”。", style = MaterialTheme.typography.bodySmall)
            } else {
                docs.forEach { d ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(d.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (d.kind.isNotBlank()) Text(d.kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        if (d.summary.isNotBlank()) Text(d.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { docs.clear(); onUpdate() }) { Text("清空资料库") }
            }
        }

        ExpandableSection("⭐ 已获得加分（$achievedCount 项）", achievedCount) {
            val achievedList = bonuses.withIndex().filter { it.value.achieved }
            val displayList = when (bonusSort) {
                "points" -> achievedList.sortedByDescending { it.value.points }
                "category" -> achievedList.sortedBy { it.value.category }
                else -> achievedList
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { bonusSort = "none" }) { Text(if (bonusSort == "none") "·默认" else "默认") }
                TextButton(onClick = { bonusSort = "points" }) { Text(if (bonusSort == "points") "·按分数" else "按分数") }
                TextButton(onClick = { bonusSort = "category" }) { Text(if (bonusSort == "category") "·按类别" else "按类别") }
            }
            if (achievedList.isEmpty()) {
                Text("还没有已获得的加分。在 AI 里说“我拿了XX奖”，或在下方手动添加。", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(displayList) { (idx, b) ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(b.item, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    if (b.category.isNotBlank()) {
                                        Text(b.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                if (b.points > 0) Text("+${b.points}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Row {
                                TextButton(onClick = {
                                    bonusEditIdx = idx; bItemInput = b.item
                                    bPointsInput = if (b.points > 0) b.points.toString() else ""
                                    bCatInput = b.category
                                }) { Text("编辑") }
                                TextButton(onClick = { onRemoveBonus(idx) }) { Text("移除") }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = {
                bonusEditIdx = -1; bItemInput = ""; bPointsInput = ""; bCatInput = ""
            }) { Text("＋ 手动添加加分") }
        }

        val addCatalogToWatch: (CatalogItem) -> Unit = { c ->
            if (watched.none { it.name == c.name }) {
                watched.add(WatchedCompetition(name = c.name, url = "", source = c.level))
                onUpdate()
            }
        }
        ExpandableSection("🏅 竞赛目录（$catalogCount 项，参考）", catalogCount) {
            OutlinedTextField(
                value = catQuery, onValueChange = { catQuery = it },
                label = { Text("搜索（名称或类别，如 数学 / B类）") },
                modifier = Modifier.fillMaxWidth()
            )
            val filtered = if (catQuery.isBlank()) catalog else catalog.filter {
                it.name.contains(catQuery, true) || it.level.contains(catQuery, true)
            }
            Text("匹配 ${filtered.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            if (catalog.isEmpty()) {
                Text("上传政策文件，AI 会把学校认可的竞赛目录存进来；也可在 AI 里说“把 XX 加入竞赛目录”。", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (catQuery.isBlank()) {
                        val groups = filtered.groupBy { it.level.ifBlank { "未分类" } }.toSortedMap()
                        groups.forEach { (lv, group) ->
                            item {
                                Text("$lv（${group.size}）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                            }
                            items(group) { c -> CatalogRowItem(c, onAdd = { addCatalogToWatch(c) }) }
                        }
                    } else {
                        items(filtered) { c -> CatalogRowItem(c, onAdd = { addCatalogToWatch(c) }) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    TextButton(onClick = {
                        catalog.clear()
                        catalog.addAll(DefaultData.catalog)
                        DefaultData.scoreRules.forEach { r -> if (!memories.contains(r)) memories.add(r) }
                        onUpdate()
                    }) { Text("载入内置目录（148 项）") }
                    TextButton(onClick = { catalog.clear(); onUpdate() }) { Text("清空竞赛目录") }
                }
            }
        }

        ExpandableSection("🍅 番茄钟（已完成 ${pomo.done} 个）", pomo.done) {
            Text(
                String.format("%02d:%02d", pomo.left / 60, pomo.left % 60),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (pomo.left > 0) { pomo.running = !pomo.running; if (pomo.running) pomo.finished = false } }) {
                    Text(if (pomo.running) "暂停" else "开始")
                }
                OutlinedButton(onClick = { pomo.running = false; pomo.finished = false; pomo.total = 25 * 60; pomo.left = 25 * 60 }) { Text("专注 25 分") }
                OutlinedButton(onClick = { pomo.running = false; pomo.finished = false; pomo.total = 5 * 60; pomo.left = 5 * 60 }) { Text("休息 5 分") }
            }
            if (pomo.finished) Text("✅ 上一段已完成", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Text("💡 切页不会中断；完成次数自动保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        ExpandableSection("📐 评分规则（学校）", DefaultData.scoreRules.size) {
            DefaultData.scoreRules.forEach { r ->
                Text("· $r", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        ExpandableSection("🧠 长期记忆", memories.size) {
            if (memories.isEmpty()) {
                Text("AI 会自动记住你告诉它的重要信息（在 AI 里说“记住…”）。", style = MaterialTheme.typography.bodySmall)
            } else {
                memories.forEach { mem ->
                    Text("· $mem", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }

        HorizontalDivider()
        Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, label = { Text("DeepSeek API Key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = dateInput, onValueChange = { dateInput = it }, label = { Text("目标日期 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())

        OutlinedButton(
            onClick = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                }
                try { context.startActivity(intent) } catch (e: Exception) { }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("打开系统通知设置") }

        OutlinedButton(
            onClick = {
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        i.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(i)
                    } else {
                        backupMsg = "已在电池优化白名单中"
                    }
                } catch (e: Exception) {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) { backupMsg = "无法打开设置：${e2.message}" }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("加入电池优化白名单（保证提醒）") }

        OutlinedButton(
            onClick = onClearChat,
            modifier = Modifier.fillMaxWidth()
        ) { Text("清空对话历史和长期记忆") }

        ExpandableSection("❓ 使用说明", 0) {
            Text("1. 先到下面“设置”填 DeepSeek API Key（联网必需）。", style = MaterialTheme.typography.bodySmall)
            Text("2. 在 AI 里上传政策文件 → 自动生成竞赛目录与评分规则。", style = MaterialTheme.typography.bodySmall)
            Text("3. 说“我拿了 XX 奖” → AI 查目录算分，确认后计入总分。", style = MaterialTheme.typography.bodySmall)
            Text("4. 说“帮我盯一下 XX 报名”或从“竞赛目录”加入监控，到点自动提醒。", style = MaterialTheme.typography.bodySmall)
            Text("5. 提醒不灵时可点下面“加入电池优化白名单”。", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("个人档案（AI 会参考）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(value = profileInput.school, onValueChange = { profileInput = profileInput.copy(school = it) }, label = { Text("学校") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = profileInput.major, onValueChange = { profileInput = profileInput.copy(major = it) }, label = { Text("专业") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = profileInput.grade, onValueChange = { profileInput = profileInput.copy(grade = it) }, label = { Text("年级") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = profileInput.rank, onValueChange = { profileInput = profileInput.copy(rank = it) }, label = { Text("专业排名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = profileInput.gpa, onValueChange = { profileInput = profileInput.copy(gpa = it) }, label = { Text("绩点") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = profileInput.english, onValueChange = { profileInput = profileInput.copy(english = it) }, label = { Text("英语") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = profileInput.research, onValueChange = { profileInput = profileInput.copy(research = it) }, label = { Text("科研") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = profileInput.competitions, onValueChange = { profileInput = profileInput.copy(competitions = it) }, label = { Text("已有竞赛") }, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                onSaveApiKey(keyInput)
                onSaveDate(dateInput)
                onSaveProfile(profileInput)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("保存所有设置") }

        HorizontalDivider()
        Text("监控检查间隔", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 12, 24).forEach { h ->
                if (intervalHours == h) {
                    Button(onClick = { }) { Text("$h 小时") }
                } else {
                    OutlinedButton(onClick = {
                        context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE).edit()
                            .putInt("check_interval_hours", h).apply()
                        intervalHours = h
                        scheduleCompetitionCheck(context)
                        backupMsg = "检查间隔已设为 $h 小时"
                    }) { Text("$h 小时") }
                }
            }
        }
        Text("后台自动检查竞赛报名状态的频率（越短越及时，但更耗电/流量）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

        HorizontalDivider()
        Text("备份与恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("备份不含 API Key；导入会自动刷新数据。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("baoyan_backup.json") }, modifier = Modifier.weight(1f)) { Text("导出备份") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text("导入恢复") }
        }
        OutlinedButton(onClick = { reportLauncher.launch("baoyan_report.md") }, modifier = Modifier.fillMaxWidth()) { Text("导出申报清单（加分 / 竞赛 / 论文）") }
        if (backupMsg.isNotBlank()) Text(backupMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        if (addDialog) {
            AlertDialog(
                onDismissRequest = { addDialog = false },
                title = { Text("添加竞赛监控") },
                text = {
                    Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text("推荐（点击即添加）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        PreloadedCompetitions.forEach { p ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (watched.none { it.name == p.name }) {
                                        watched.add(WatchedCompetition(name = p.name, url = p.url, source = p.category))
                                        onUpdate()
                                    }
                                    addDialog = false
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${p.category} · ${p.url}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                                }
                                Text("＋", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("手动添加", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("竞赛名称") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("官网 / 信息页网址") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (nameInput.isNotBlank()) {
                            watched.add(WatchedCompetition(name = nameInput.trim(), url = urlInput.trim()))
                            nameInput = ""; urlInput = ""
                            onUpdate()
                        }
                        addDialog = false
                    }) { Text("手动添加") }
                },
                dismissButton = { TextButton(onClick = { addDialog = false }) { Text("关闭") } }
            )
        }

        if (editDialog) {
            AlertDialog(
                onDismissRequest = { editDialog = false },
                title = { Text("编辑监控项") },
                text = {
                    Column {
                        OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("竞赛名称") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("官网 / 信息页网址") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val idx = watched.indexOfFirst { it.name == editName }
                        if (idx >= 0) {
                            watched[idx] = watched[idx].copy(
                                name = nameInput.trim().ifBlank { editName },
                                url = urlInput.trim(),
                                source = "手动设置"
                            )
                            onUpdate()
                        }
                        editDialog = false
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editDialog = false }) { Text("取消") } }
            )
        }

        if (bonusEditIdx != -99) {
            AlertDialog(
                onDismissRequest = { bonusEditIdx = -99 },
                title = { Text(if (bonusEditIdx >= 0) "编辑加分" else "手动添加加分") },
                text = {
                    Column {
                        OutlinedTextField(value = bItemInput, onValueChange = { bItemInput = it }, label = { Text("项目名称") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = bPointsInput, onValueChange = { bPointsInput = it }, label = { Text("分值（数字）") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = bCatInput, onValueChange = { bCatInput = it }, label = { Text("类别（可空）") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (bItemInput.isNotBlank()) {
                            onSaveBonus(bonusEditIdx, bItemInput.trim(), bPointsInput.trim().toDoubleOrNull() ?: 0.0, bCatInput.trim())
                        }
                        bonusEditIdx = -99
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { bonusEditIdx = -99 }) { Text("取消") } }
            )
        }

        if (taskEditIdx != -99) {
            AlertDialog(
                onDismissRequest = { taskEditIdx = -99 },
                title = { Text(if (taskEditIdx >= 0) "编辑任务" else "添加任务") },
                text = {
                    Column {
                        OutlinedTextField(value = tTitle, onValueChange = { tTitle = it }, label = { Text("任务内容") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = tDate, onValueChange = { tDate = it }, label = { Text("截止日期 yyyy-MM-dd（可空）") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val curDone = if (taskEditIdx in tasks.indices) tasks[taskEditIdx].done else false
                        onSaveTask(taskEditIdx, tTitle.trim(), tDate.trim(), curDone)
                        taskEditIdx = -99
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { taskEditIdx = -99 }) { Text("取消") } }
            )
        }

        if (compEditIdx != -99) {
            AlertDialog(
                onDismissRequest = { compEditIdx = -99 },
                title = { Text(if (compEditIdx >= 0) "编辑竞赛" else "添加竞赛") },
                text = {
                    Column {
                        OutlinedTextField(value = cName, onValueChange = { cName = it }, label = { Text("竞赛名称") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = cDeadline, onValueChange = { cDeadline = it }, label = { Text("截止日期 yyyy-MM-dd（可空）") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(value = cNote, onValueChange = { cNote = it }, label = { Text("备注（可空）") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSaveCompetition(compEditIdx, cName.trim(), cDeadline.trim(), cNote.trim())
                        compEditIdx = -99
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { compEditIdx = -99 }) { Text("取消") } }
            )
        }
    }
}