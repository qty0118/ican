package com.example.baoyancompanion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// ==================== 竞赛报名监控数据模型 ====================

/**
 * 被监控的竞赛（学校认可的竞赛目录 → 监控清单）。
 * status: unknown | not_started | enrolling | closed
 * enrollStart/EnrollEnd: yyyy-MM-dd 或 ""
 * url: 由 AI 提议 + 程序探测验证得到的官方信息页
 */
data class WatchedCompetition(
    val name: String,
    val url: String = "",
    val source: String = "",
    val enrollStart: String = "",
    val enrollEnd: String = "",
    val status: String = "unknown",
    val lastChecked: Long = 0L,
    val lastNote: String = "",
    val enabled: Boolean = true,
    val timeText: String = ""   // 竞赛的报名/举办时间（自由文本）
)

// ==================== 预置竞赛信息渠道（已联网验证可达，2026-10-11） ====================

data class PresetCompetition(val name: String, val url: String, val category: String)

val PreloadedCompetitions: List<PresetCompetition> = listOf(
    PresetCompetition("赛氪竞赛网", "https://www.saikr.com", "聚合平台"),
    PresetCompetition("我爱竞赛网", "http://www.52jingsai.com", "聚合平台"),
    PresetCompetition("全国大学生数学建模竞赛", "http://www.mcm.edu.cn", "学科竞赛"),
    PresetCompetition("美国大学生数学建模竞赛(MCM/ICM)", "https://www.comap.com", "国际竞赛"),
    PresetCompetition("挑战杯·全国大学生课外学术科技作品竞赛", "http://www.tiaozhanbei.net", "创新创业"),
    PresetCompetition("中国国际大学生创新大赛(原“互联网+”)", "https://cy.ncss.cn", "创新创业"),
    PresetCompetition("iCAN国际大学生创新创业大赛", "http://www.ican-contest.org", "创新创业"),
    PresetCompetition("蓝桥杯全国软件和信息技术专业人才大赛", "https://dasai.lanqiao.cn", "学科竞赛"),
    PresetCompetition("中国大学生程序设计竞赛(CCPC)", "https://ccpc.io", "学科竞赛"),
    PresetCompetition("全国大学生电子设计竞赛", "http://www.nuedc-training.com.cn", "学科竞赛"),
    PresetCompetition("全国大学生英语竞赛(NECCS)", "http://www.chinaneccs.cn", "学科竞赛"),
    PresetCompetition("全国大学生数学竞赛", "http://www.cmathc.cn", "学科竞赛"),
    PresetCompetition("“外研社·国才杯”系列赛", "https://uchallenge.unipus.cn", "学科竞赛"),
    PresetCompetition("全国大学生广告艺术大赛", "http://www.sun-ada.net", "学科竞赛"),
    PresetCompetition("中国高校计算机大赛", "http://www.c4best.cn", "学科竞赛"),
    PresetCompetition("“华为杯”研究生数学建模竞赛", "https://cpipc.acge.org.cn", "学科竞赛")
)

data class CheckOutcome(
    val updated: WatchedCompetition,
    val alerts: List<Pair<String, String>>   // title to body
)

// ==================== 监控核心 ====================

object Monitor {
    const val CHANNEL_COMP = "baoyan_competition"
    private val jsonType = "application/json".toMediaType()

    fun statusText(s: String): String = when (s) {
        "enrolling" -> "报名中"
        "closed" -> "已截止"
        "not_started" -> "未开始"
        else -> "未知"
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_COMP) == null) {
                val ch = NotificationChannel(
                    CHANNEL_COMP, "竞赛报名提醒", NotificationManager.IMPORTANCE_HIGH
                )
                ch.description = "竞赛报名开始 / 截止临近提醒"
                mgr.createNotificationChannel(ch)
            }
        }
    }

    fun notify(ctx: Context, title: String, body: String, id: Int) {
        ensureChannel(ctx)
        try {
            val intent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_COMP)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(id, notif)
        } catch (e: Exception) { }
    }

    // ---------- 网络 ----------

    suspend fun httpGet(url: String, timeoutSec: Long = 20): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val req = Request.Builder().url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                .get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                if (body.length > 400_000) body.substring(0, 400_000) else body
            }
        } catch (e: Exception) {
            null
        }
    }

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?</style>"), " ")
        s = s.replace(Regex("(?is)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ")
        s = s.replace("&amp;", "&")
        s = s.replace("&lt;", "<")
        s = s.replace("&gt;", ">")
        s = s.replace(Regex("\\s+"), " ")
        return s.trim()
    }

    // ---------- LLM ----------

    suspend fun deepSeekRaw(apiKey: String, system: String, user: String, maxTokens: Int = 2000): String =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", system))
                msgs.put(JSONObject().put("role", "user").put("content", user))
                val body = JSONObject()
                    .put("model", "deepseek-chat")
                    .put("messages", msgs)
                    .put("temperature", 0.2)
                    .put("max_tokens", maxTokens)
                    .toString()
                val req = Request.Builder()
                    .url("https://api.deepseek.com/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val rb = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) return@use ""
                    JSONObject(rb).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content")
                }
            } catch (e: Exception) {
                ""
            }
        }

    // ---------- AI 提议 + 程序探测验证 官网 ----------

    suspend fun discoverUrl(apiKey: String, name: String): String {
        val sys = "你是竞赛信息助手。只输出 JSON 数组，不要任何解释文字。"
        val user = "请给出竞赛《$name》最可能的【官方】信息渠道网址（官网/官方通知页），最多3个候选，按可能性排序。" +
                "只输出 JSON 数组，例如 [\"https://a.com\",\"https://b.com\"]。若不确定就返回 []。"
        val raw = deepSeekRaw(apiKey, sys, user, 800)
        val urls = extractUrlArray(raw)
        var firstReachable = ""
        for (u in urls) {
            val html = httpGet(u) ?: continue
            if (firstReachable.isBlank()) firstReachable = u
            val text = htmlToText(html)
            if (looksRelevant(name, text)) return u
        }
        // 退而求其次：返回第一个可达但未验证相关的地址，交由用户判断
        return firstReachable
    }

    private fun extractUrlArray(raw: String): List<String> {
        val list = mutableListOf<String>()
        try {
            val a = raw.indexOf('['); val b = raw.lastIndexOf(']')
            if (a < 0 || b <= a) return list
            val arr = JSONArray(raw.substring(a, b + 1))
            for (i in 0 until arr.length()) {
                val u = arr.optString(i).trim()
                if (u.startsWith("http")) list.add(u)
            }
        } catch (e: Exception) { }
        return list
    }

    private fun looksRelevant(name: String, text: String): Boolean {
        val t = text.replace(" ", "")
        val tokens = name.split(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9]+")).filter { it.length >= 2 }
        return if (tokens.isEmpty()) listOf("报名", "竞赛", "大赛").any { t.contains(it) }
        else tokens.any { t.contains(it) }
    }

    // ---------- 单条检查（提取时间）----------

    suspend fun checkOne(ctx: Context, apiKey: String, w: WatchedCompetition): CheckOutcome {
        val now = System.currentTimeMillis()
        if (w.url.isBlank()) {
            return CheckOutcome(w.copy(lastChecked = now, lastNote = "未设置通知页网址"), emptyList())
        }
        val html = httpGet(w.url)
            ?: return CheckOutcome(w.copy(lastChecked = now, lastNote = "抓取失败（网络或网站不可达）"), emptyList())
        val text = htmlToText(html)
        val alerts = mutableListOf<Pair<String, String>>()

        val judged = judgeTime(apiKey, w.name, text)
        var updated = w.copy(lastChecked = now)
        if (judged != null) {
            val t = judged.optString("time").trim()
            val note = judged.optString("note").trim()
            val oldTime = w.timeText
            val newTime = if (t.isNotBlank() && !t.equals("null", true)) t else w.timeText
            updated = updated.copy(
                timeText = newTime,
                lastNote = if (newTime.isBlank()) "页面里没找到时间，建议换成该竞赛的通知/公告页" else note
            )
            if (newTime.isNotBlank() && newTime != oldTime) {
                alerts.add("🔔 ${w.name} 时间有更新" to "$newTime\n数据来源：${w.url}")
            }
        } else {
            updated = updated.copy(lastNote = "未能解析页面，建议换成该竞赛的通知/公告页")
        }
        return CheckOutcome(updated, alerts)
    }

    private suspend fun judgeTime(apiKey: String, name: String, pageText: String): JSONObject? {
        val sys = "你是竞赛信息解析助手。只输出一个 JSON 对象，不要多余解释。"
        val user = "竞赛名称：$name\n\n页面正文（可能不完整）：\n${pageText.take(6000)}\n\n" +
                "请从页面中找出该竞赛的【时间信息】（报名时间或比赛/举办时间），原样摘录日期与时间，不要臆造。" +
                "只输出 JSON：{\"time\":\"如 2026年3月1日-31日报名；9月比赛；找不到就填空字符串\",\"note\":\"一句话\"}"
        val raw = deepSeekRaw(apiKey, sys, user, 800)
        return try {
            val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) null else JSONObject(raw.substring(a, b + 1))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun checkAll(
        ctx: Context, apiKey: String, list: List<WatchedCompetition>
    ): Pair<List<WatchedCompetition>, List<Pair<String, String>>> {
        val out = mutableListOf<WatchedCompetition>()
        val alerts = mutableListOf<Pair<String, String>>()
        for (w in list) {
            if (!w.enabled) { out.add(w); continue }
            val o = checkOne(ctx, apiKey, w)
            out.add(o.updated)
            alerts.addAll(o.alerts)
        }
        return Pair(out, alerts)
    }

    // ---------- 工具 ----------

    fun normDate(s: String): String {
        if (s.isBlank() || s.equals("null", true)) return ""
        val cleaned = s.trim()
            .replace("年", "-").replace("月", "-").replace("日", "")
            .replace("/", "-").replace(".", "-")
        val fmts = listOf("yyyy-MM-dd", "yyyy-M-d", "yyyyMMdd")
        for (f in fmts) {
            try {
                val sdf = SimpleDateFormat(f, Locale.getDefault()); sdf.isLenient = false
                val d = sdf.parse(cleaned) ?: continue
                return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(d)
            } catch (e: Exception) { }
        }
        return ""
    }

    fun fmtTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    fun daysUntil(date: String): Int {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val d = sdf.parse(date)?.time ?: return Int.MIN_VALUE
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            ((d - c.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        } catch (e: Exception) {
            Int.MIN_VALUE
        }
    }
}

// ==================== 后台定时检查 ====================

class CompetitionCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        ensureNotificationChannel(ctx)
        Monitor.ensureChannel(ctx)
        val prefs = ctx.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("deepseek_key", "") ?: ""
        if (apiKey.isBlank()) return Result.success()
        val list = Store.loadWatched(prefs)
        if (list.isEmpty()) return Result.success()
        val res = runBlocking { Monitor.checkAll(ctx, apiKey, list) }
        Store.saveWatched(prefs, res.first)
        res.second.forEachIndexed { i, p -> Monitor.notify(ctx, p.first, p.second, 2100 + i) }
        return Result.success()
    }
}

fun scheduleCompetitionCheck(context: Context) {
    val prefs = context.getSharedPreferences("baoyan_prefs", Context.MODE_PRIVATE)
    val hours = prefs.getInt("check_interval_hours", 12).coerceIn(1, 72).toLong()
    val req = PeriodicWorkRequestBuilder<CompetitionCheckWorker>(hours, TimeUnit.HOURS)
        .setInitialDelay(10, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "baoyan_competition_check",
        ExistingPeriodicWorkPolicy.UPDATE,
        req
    )
}
