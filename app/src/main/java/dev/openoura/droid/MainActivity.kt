package dev.openoura.droid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import uniffi.oura_core.*
import java.io.File
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * OpenRing: pair a factory-reset ring, sync its history into a local SQLite DB
 * through the shared open_health Rust core, and browse it. The only network use
 * is the optional Ask tab.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var ble: RingBle
    private val prefs by lazy { getSharedPreferences("ring", Context.MODE_PRIVATE) }
    private val dbPath by lazy { File(filesDir, "oura.db").absolutePath }

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private var tab = "today"
    private var summary: JSONObject? = null
    private val logLines = StringBuilder()
    private var syncStatus: String? = null

    // Ask tab state survives tab switches.
    private val turns = mutableListOf<Turn>()
    private var systemPrompt: String? = null
    private var pending: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.CARD
        ble = RingBle(this) { msg -> runOnUiThread { log(msg) } }
        val root = vbox().apply { setBackgroundColor(Theme.BG) }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = hbox().apply { setBackgroundColor(Theme.CARD); setPadding(0, dp(6), 0, dp(10)) }
        root.addView(nav)
        setContentView(root)
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
        loadSummary()
    }

    override fun onDestroy() { scope.cancel(); ble.close(); super.onDestroy() }

    // ================= navigation =================

    private val tabs = listOf("today" to "Today", "sleep" to "Sleep", "trends" to "Trends", "ask" to "Ask", "ring" to "Ring")

    private fun render() {
        nav.removeAllViews()
        tabs.forEach { (id, label) ->
            val on = id == tab
            nav.addView(text(label, 13f, if (on) Theme.ACCENT else Theme.MUTED, bold = on).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = id; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.removeAllViews()
        val page = vbox(16)
        when (tab) {
            "today" -> today(page)
            "sleep" -> sleep(page)
            "trends" -> trends(page)
            "ask" -> ask(page)
            "ring" -> ring(page)
        }
        content.addView(ScrollView(this).apply { addView(page); isFillViewport = true })
    }

    private fun header(page: LinearLayout, title: String, sub: String? = null) {
        page.addView(text(title, 28f, Theme.TEXT, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        sub?.let { page.addView(text(it, 13f, Theme.MUTED).apply { setPadding(0, dp(2), 0, dp(16)) }) }
            ?: page.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
    }

    private fun empty(page: LinearLayout, msg: String) {
        card(page).addView(text(msg, 15f, Theme.MUTED))
    }

    // ================= data helpers =================

    private fun nights(): List<JSONObject> {
        val arr = summary?.optJSONArray("nights") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.sortedByDescending { it.ymd() }
    }

    private fun JSONObject.ymd() = optStringOrNull("wake_ymd") ?: optStringOrNull("ymd") ?: optStringOrNull("date") ?: ""
    private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k, "").ifEmpty { null }
    private fun JSONObject.num(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
    private fun JSONArray?.doubles(): List<Double> = this?.let { a -> (0 until a.length()).mapNotNull { a.optDouble(it).takeIf { v -> !v.isNaN() } } } ?: emptyList()
    private fun JSONArray?.ints(): List<Int> = this?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
    private fun score(n: JSONObject) = n.optJSONObject("sleep_score")?.num("score")?.roundToInt()
    private fun f(v: Double?, digits: Int = 0) = v?.let { if (digits == 0) it.roundToInt().toString() else "%.${digits}f".format(it) } ?: "–"
    private fun cToF(c: Double?) = c?.let { it * 9 / 5 + 32 }
    private fun hrs(h: Double?) = h?.let { "${it.toInt()}h ${((it - it.toInt()) * 60).roundToInt()}m" } ?: "–"
    private fun pretty(ymd: String) = runCatching {
        java.time.LocalDate.parse(ymd).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
    }.getOrDefault(ymd)

    private fun deltaLine(t: JSONObject?, unit: String, higherIsBetter: Boolean): Pair<String?, Int> {
        val d = t?.num("delta_pct") ?: return (t?.num("baseline")?.let { "baseline ${f(it)} $unit" } to Theme.MUTED)
        val good = if (higherIsBetter) d >= 0 else d <= 0
        val arrow = if (d >= 0) "▲" else "▼"
        return "$arrow ${f(kotlin.math.abs(d), 1)}% vs baseline" to (if (kotlin.math.abs(d) < 3) Theme.MUTED else if (good) Theme.ACCENT else Theme.WARN)
    }

    // ================= Today =================

    private fun today(page: LinearLayout) {
        val dev = summary?.optJSONObject("device")
        val sub = listOfNotNull(
            dev?.num("battery_pct")?.let { "Battery ${f(it)}%" },
            dev?.optStringOrNull("synced")?.let { "Synced $it ${dev.optStringOrNull("synced_hm") ?: ""}".trim() }
        ).joinToString("  ·  ").ifEmpty { if (prefs.getString("key", null) == null) "No ring paired" else "Not synced yet" }
        header(page, "Today", sub)

        page.addView(pill(syncStatus ?: "Sync ring") { if (syncStatus == null) sync() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        if (prefs.getString("key", null) == null) return empty(page, "Pair your factory-reset ring from the Ring tab to get started.")
        val last = nights().firstOrNull() ?: return empty(page, "No nights yet. Wear the ring overnight, then sync.")

        val c = card(page, "Last night · ${pretty(last.ymd())}")
        val top = hbox().apply { setPadding(0, dp(8), 0, 0) }
        top.addView(ScoreRing(this, score(last), "sleep"))
        val side = vbox().apply { setPadding(dp(16), 0, 0, 0) }
        side.addView(metric("Time in bed", hrs(last.num("in_bed_h"))))
        side.addView(metric("Efficiency", f(last.num("efficiency")), "%"))
        top.addView(side, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top)
        last.optJSONArray("stages").ints().takeIf { it.isNotEmpty() }?.let {
            c.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
            c.addView(Hypnogram(this, it))
            c.addView(stageLegend(last))
        }

        val v = summary?.optJSONObject("vitals")
        val hrv = v?.optJSONObject("hrv"); val rhr = v?.optJSONObject("rhr")
        val (hrvSub, hrvCol) = deltaLine(hrv, "ms", true)
        val (rhrSub, rhrCol) = deltaLine(rhr, "bpm", false)
        card(page, "Recovery signals").addView(row(
            metric("HRV", f(hrv?.num("latest") ?: last.num("hrv_ms")), "ms", hrvSub, hrvCol),
            metric("Resting HR", f(rhr?.num("latest") ?: last.num("rhr")), "bpm", rhrSub, rhrCol),
        ))
        card(page, "Overnight").addView(row(
            metric("Skin temp", f(cToF(last.num("skin_temp")), 1), "°F"),
            metric("SpO₂", f(last.num("spo2_mean")), "%"),
        ))

        val daily = summary?.optJSONObject("activity_daily")
        val latestDay = daily?.keys()?.asSequence()?.maxOrNull()
        if (daily != null && latestDay != null) daily.optJSONObject(latestDay)?.let { d ->
            card(page, "Activity · ${pretty(latestDay)}").addView(row(
                metric("Steps", f(d.num("steps"))),
                metric("Active", f(d.num("active_kcal")), "kcal"),
                metric("Distance", f(d.num("distance_m")?.div(1609.34), 1), "mi"),
            ))
        }
    }

    private fun stageLegend(n: JSONObject) = hbox().apply {
        setPadding(0, dp(8), 0, 0)
        listOf("Deep" to (Theme.DEEP to "deep_pct"), "Light" to (Theme.LIGHT to "light_pct"),
            "REM" to (Theme.REM to "rem_pct"), "Awake" to (Theme.WAKE to "wake_pct")).forEach { (label, cfg) ->
            addView(text("● ", 12f, cfg.first))
            addView(text("$label ${f(n.num(cfg.second))}%   ", 12f, Theme.MUTED))
        }
    }

    // ================= Sleep =================

    private fun sleep(page: LinearLayout) {
        header(page, "Sleep", summary?.optJSONObject("sleep_debt")?.let { d ->
            if (d.optBoolean("valid")) "Sleep debt ${hrs(d.num("debt_min")?.div(60))} over ${d.optInt("window_days")} days" else null
        })
        val ns = nights()
        if (ns.isEmpty()) return empty(page, "No nights yet.")
        ns.take(30).forEach { n ->
            val c = card(page)
            c.addView(hbox().apply {
                addView(vbox().apply {
                    addView(text(pretty(n.ymd()), 16f, Theme.TEXT, bold = true))
                    addView(text(listOfNotNull(n.optStringOrNull("start"), n.optStringOrNull("end")).joinToString(" → "), 12f, Theme.MUTED))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(score(n)?.toString() ?: "–", 28f, Theme.ACCENT, bold = true))
            })
            n.optJSONArray("stages").ints().takeIf { it.isNotEmpty() }?.let {
                c.addView(View(this), LinearLayout.LayoutParams(1, dp(10))); c.addView(Hypnogram(this, it))
            }
            c.addView(row(
                metric("In bed", hrs(n.num("in_bed_h"))),
                metric("HRV", f(n.num("hrv_ms")), "ms"),
                metric("RHR", f(n.num("rhr")), "bpm"),
            ).apply { setPadding(0, dp(10), 0, 0) })
        }
    }

    // ================= Trends =================

    private fun trends(page: LinearLayout) {
        header(page, "Trends", "Dashed line = your baseline")
        if (summary == null) return empty(page, "No data yet.")
        val v = summary?.optJSONObject("vitals")
        val ns = nights().reversed() // oldest → newest

        fun trendCard(title: String, t: JSONObject?, fallback: List<Double>, unit: String, color: Int) {
            val series = t?.optJSONArray("series").doubles().ifEmpty { fallback }
            if (series.size < 2) return
            val c = card(page, title)
            c.addView(text("${f(series.last(), if (unit == "°F" || unit == "h") 1 else 0)} $unit", 22f, Theme.TEXT, bold = true))
            c.addView(Sparkline(this, series.takeLast(60), color, t?.num("baseline")))
        }
        trendCard("HRV", v?.optJSONObject("hrv"), ns.mapNotNull { it.num("hrv_ms") }, "ms", Theme.ACCENT)
        trendCard("Resting heart rate", v?.optJSONObject("rhr"), ns.mapNotNull { it.num("rhr") }, "bpm", Theme.WARN)
        trendCard("Time in bed", null, ns.mapNotNull { it.num("in_bed_h") }, "h", Theme.LIGHT)
        trendCard("Skin temperature", null, ns.mapNotNull { cToF(it.num("skin_temp")) }, "°F", Theme.REM)

        val daily = summary?.optJSONObject("activity_daily") ?: return
        val days = daily.keys().asSequence().sorted().toList().takeLast(14)
        val steps = days.map { daily.optJSONObject(it)?.num("steps") ?: 0.0 }
        if (steps.any { it > 0 }) {
            val c = card(page, "Steps · last ${days.size} days")
            c.addView(text("avg ${f(steps.average())}", 22f, Theme.TEXT, bold = true))
            c.addView(Bars(this, steps))
        }
    }

    // ================= Ask =================

    private fun ask(page: LinearLayout) {
        header(page, "Ask", if (prefs.getString("claude_key", null).isNullOrEmpty()) "Add an API key in the Ring tab" else
            "Claude · ${prefs.getString("claude_model", DEFAULT_MODEL)} · sees your summary when you send")
        if (turns.isEmpty() && pending == null) {
            val c = card(page, "Try")
            listOf("How did I sleep this week compared to last?", "Is my HRV trending up or down, and why might that be?",
                "Anything unusual in my overnight vitals?").forEach { q ->
                c.addView(text("→ $q", 14f, Theme.ACCENT).apply { setPadding(0, dp(8), 0, dp(8)); setOnClickListener { send(q) } })
            }
        }
        turns.forEach { t ->
            val mine = t.role == "user"
            page.addView(text(t.text, 15f, if (mine) Theme.BG else Theme.TEXT).apply {
                setTextIsSelectable(true)
                background = rounded(if (mine) Theme.ACCENT else Theme.CARD, dp(16).toFloat())
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }, LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dp(8); gravity = if (mine) Gravity.END else Gravity.START
                if (mine) leftMargin = dp(48) else rightMargin = dp(24)
            })
        }
        pending?.let { page.addView(text(it, 14f, Theme.MUTED).apply { setPadding(dp(4), 0, 0, dp(8)) }) }

        val input = EditText(this).apply {
            hint = "Ask about your data…"; setHintTextColor(Theme.MUTED); setTextColor(Theme.TEXT)
            background = rounded(Theme.CARD, dp(20).toFloat(), Theme.LINE); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        page.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        page.addView(row(
            pill("Send") { send(input.text.toString()) },
            pill("New chat", filled = false) { turns.clear(); systemPrompt = null; pending = null; render() },
        ).apply { setPadding(0, dp(10), 0, 0) })
        content.post { (content.getChildAt(0) as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send(question: String) {
        val q = question.trim()
        if (q.isEmpty() || pending == "Thinking…") return
        val key = prefs.getString("claude_key", null)?.ifEmpty { null } ?: return toast("Add your API key in the Ring tab first.")
        if (!File(dbPath).exists()) return toast("Sync first so there's data to talk about.")
        val ai: AiProvider = ClaudeProvider(key, prefs.getString("claude_model", null) ?: DEFAULT_MODEL)
        turns += Turn("user", q); pending = "Thinking…"; render()
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    val sys = systemPrompt ?: HealthPrompt.system(summaryJson(dbPath, tzHours())).also { systemPrompt = it }
                    ai.chat(sys, turns)
                }
                turns += Turn("assistant", reply); pending = null
            } catch (e: Exception) {
                turns.removeAt(turns.lastIndex); pending = "Error: ${e.message}"
            }
            if (tab == "ask") render()
        }
    }

    // ================= Ring =================

    private fun ring(page: LinearLayout) {
        val serial = prefs.getString("serial", null)
        header(page, "Ring", if (prefs.getString("key", null) == null) "Not paired" else "Ring ${serial ?: "?"} · key stored on this phone only")

        fun actions(caption: String, vararg items: Pair<String, () -> Unit>) {
            val c = card(page, caption)
            items.forEach { (label, fn) ->
                c.addView(text(label, 16f).apply { setPadding(0, dp(12), 0, dp(12)); setOnClickListener { fn() } })
            }
        }
        actions("Ring", "Pair a factory-reset ring" to { confirmPair() }, "Back up auth key" to { showKey() }, "Restore auth key" to { restoreKey() })
        actions("Data", "Export database" to { exportDb() }, "Raw summary JSON" to { showRaw() })
        actions("Ask AI", "AI settings" to { aiSettings() })

        val c = card(page, "Log")
        c.addView(text(logLines.toString().ifEmpty { "Nothing yet." }, 11f, Theme.MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
    }

    private fun log(msg: String) {
        logLines.insert(0, msg + "\n"); if (logLines.length > 8000) logLines.setLength(8000)
    }

    private fun showRaw() {
        AlertDialog.Builder(this).setTitle("Summary JSON")
            .setMessage(summary?.toString(2)?.take(30000) ?: "No data").setPositiveButton("Close", null).show()
    }

    // ================= BLE + core =================

    private fun hasPerms() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private suspend fun openSession(): RingSession {
        if (!hasPerms()) error("Grant Nearby devices permission first")
        val addr = prefs.getString("address", null)
        var connected = false
        if (addr != null) {
            try { log("connecting to $addr"); ble.connect(ble.device(addr)); connected = true }
            catch (e: Exception) { log("direct connect failed, scanning: ${e.message}") }
        }
        if (!connected) {
            log("scanning… (keep the ring on its charger)")
            val dev = ble.scan() ?: error("No ring found. Put it on the charger and try again.")
            ble.connect(dev)
            prefs.edit().putString("address", dev.address).apply()
        }
        val session = RingSession(object : BleWriter { override fun write(data: ByteArray) = ble.write(data) })
        ble.onFrame = { session.pushFrame(it) }
        ble.onDisconnect = { session.cancel("BLE link dropped") }
        return session
    }

    private fun setSync(s: String?) { syncStatus = s; if (tab == "today") render() }

    private fun busy(label: String, block: suspend () -> Unit) {
        setSync(label)
        scope.launch {
            try { block() } catch (e: Exception) { log("ERROR: ${e.message}"); toast("Failed: ${e.message}") }
            finally { ble.close(); setSync(null) }
        }
    }

    private fun sync() {
        val key = prefs.getString("key", null) ?: return toast("Pair the ring first (Ring tab).")
        busy("Connecting…") {
            val session = openSession()
            val report = session.sync(dbPath, key, object : SyncProgressListener {
                override fun onProgress(stage: String, bytesLeft: ULong, eventsSynced: UInt) = runOnUiThread {
                    setSync(if (stage == "sync") "Syncing · $eventsSynced events · ${bytesLeft / 1024u} KB left" else "Sync: $stage")
                }
            })
            prefs.edit().putString("serial", report.serial).apply()
            log("sync done: ${report.eventsSynced} events, ${report.inserted} new, cursor ${report.nextCursor}")
            systemPrompt = null // new data → fresh AI context
            loadSummary()
        }
    }

    private fun loadSummary() {
        if (!File(dbPath).exists()) { summary = null; render(); return }
        scope.launch {
            summary = withContext(Dispatchers.IO) {
                runCatching { JSONObject(summaryJson(dbPath, tzHours())) }.getOrNull()?.takeIf { !it.has("error") }
            }
            render()
        }
    }

    private fun confirmPair() {
        if (prefs.getString("key", null) != null) {
            AlertDialog.Builder(this).setTitle("Replace stored key?")
                .setMessage("A key is already stored. Back it up first — pairing again mints a new key and the old one stops working.")
                .setPositiveButton("Pair anyway") { _, _ -> pair() }.setNegativeButton("Cancel", null).show()
        } else pair()
    }

    private fun pair() = busy("Pairing…") {
        val report = openSession().pair(null)
        prefs.edit().putString("key", report.keyHex).putString("serial", report.serial).commit()
        log("paired ${report.serial}")
        toast("Paired! Back up the key now.")
        showKey()
    }

    private fun showKey() {
        val key = prefs.getString("key", null) ?: return toast("No key stored.")
        AlertDialog.Builder(this).setTitle("Ring auth key")
            .setMessage("Store this somewhere safe (password manager). Without it, only a factory reset gets the ring back.\n\n$key")
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ring key", key))
            }.setNegativeButton("Close", null).show()
    }

    private fun restoreKey() {
        val input = EditText(this).apply { hint = "32 hex characters" }
        AlertDialog.Builder(this).setTitle("Restore auth key").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val k = input.text.toString().trim().lowercase()
                if (Regex("^[0-9a-f]{32}$").matches(k)) { prefs.edit().putString("key", k).commit(); render() }
                else toast("Key must be 32 hex characters.")
            }.setNegativeButton("Cancel", null).show()
    }

    // ================= Export =================

    private fun exportDb() {
        if (!File(dbPath).exists()) return toast("Nothing to export yet — sync first.")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.sqlite3"
            putExtra(Intent.EXTRA_TITLE, "openring-${System.currentTimeMillis() / 1000}.db")
        }, REQ_EXPORT)
    }

    @Deprecated("Activity result API kept dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || uri == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val tmp = File(cacheDir, "export.db").apply { delete() }
                    backupDatabase(dbPath, tmp.absolutePath) // consistent SQLite snapshot
                    contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                }
                toast("Exported.")
            } catch (e: Exception) { toast("Export failed: ${e.message}") }
        }
    }

    // ================= AI settings =================

    private fun aiSettings() {
        val box = vbox(16)
        val key = EditText(this).apply {
            hint = "Anthropic API key (sk-ant-…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("claude_key", ""))
        }
        val model = EditText(this).apply { hint = DEFAULT_MODEL; setText(prefs.getString("claude_model", DEFAULT_MODEL)) }
        box.addView(TextView(this).apply { text = "Claude sees your data summary when you send a question. The key is stored on this phone only." })
        box.addView(key); box.addView(model)
        AlertDialog.Builder(this).setTitle("AI settings").setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("claude_key", key.text.toString().trim())
                    .putString("claude_model", model.text.toString().trim().ifEmpty { DEFAULT_MODEL }).apply()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun tzHours() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000L
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_EXPORT = 42
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
