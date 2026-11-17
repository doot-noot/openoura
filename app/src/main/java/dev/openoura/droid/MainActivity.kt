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
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import org.json.JSONObject
import uniffi.oura_core.*
import java.io.File
import java.util.TimeZone

/**
 * One-screen v0.1: pair a factory-reset ring, sync its history into a local
 * SQLite DB through the shared open_health Rust core, and show the summary.
 * Nothing leaves the phone.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var summary: TextView
    private lateinit var ble: RingBle
    private val prefs by lazy { getSharedPreferences("ring", Context.MODE_PRIVATE) }
    private val dbPath by lazy { File(filesDir, "oura.db").absolutePath }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = RingBle(this) { msg -> runOnUiThread { log(msg) } }
        buildUi()
        requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1
        )
        refreshStatus()
        showSummary()
    }

    override fun onDestroy() { scope.cancel(); ble.close(); super.onDestroy() }

    // ---------------- UI ----------------

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        status = TextView(this).apply { textSize = 16f }
        col.addView(status)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
        }.also { col.addView(it) }

        button("Sync now") { sync() }
        button("Pair (factory-reset ring only)") { confirmPair() }
        button("Back up auth key") { showKey() }
        button("Restore auth key") { restoreKey() }
        button("Export database") { exportDb() }

        // ---- Ask AI ----
        col.addView(TextView(this).apply { text = "\nAsk AI about your data"; textSize = 16f })
        button("AI settings") { aiSettings() }
        chatView = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(chatView)
        val input = EditText(this).apply { hint = "e.g. How did I sleep this week vs last?" }
        col.addView(input)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Send"; setOnClickListener { ask(input.text.toString()); input.setText("") }
        })
        row.addView(Button(this).apply {
            text = "New chat"; setOnClickListener { turns.clear(); systemPrompt = null; chatView.text = "" }
        })
        col.addView(row)

        summary = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, pad, 0, pad) }
        col.addView(TextView(this).apply { text = "\nSummary (raw)"; textSize = 14f })
        col.addView(summary)
        col.addView(TextView(this).apply { text = "Log"; textSize = 14f })
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        col.addView(logView)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun log(msg: String) {
        logView.text = (msg + "\n" + logView.text).take(8000)
    }

    private fun refreshStatus() {
        val key = prefs.getString("key", null)
        val serial = prefs.getString("serial", null)
        status.text = if (key == null) "No ring paired yet." else "Ring ${serial ?: "?"} paired. Key stored on this phone only."
    }

    private fun hasPerms() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    // ---------------- BLE + core plumbing ----------------

    /** Connect (remembered address first, else scan) and wire frames into a new RingSession. */
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
        val session = RingSession(object : BleWriter {
            override fun write(data: ByteArray) = ble.write(data)
        })
        ble.onFrame = { session.pushFrame(it) }
        ble.onDisconnect = { session.cancel("BLE link dropped") }
        return session
    }

    private fun busy(label: String, block: suspend () -> Unit) {
        status.text = label
        scope.launch {
            try { block() } catch (e: Exception) { log("ERROR: ${e.message}"); status.text = "Failed: ${e.message}" }
            finally { withContext(Dispatchers.Main) { ble.close() } }
        }
    }

    private fun sync() {
        val key = prefs.getString("key", null) ?: return toast("Pair the ring first (or restore your key).")
        busy("Syncing…") {
            val session = openSession()
            val report = session.sync(dbPath, key, object : SyncProgressListener {
                override fun onProgress(stage: String, bytesLeft: ULong, eventsSynced: UInt) {
                    runOnUiThread {
                        status.text = if (stage == "sync") "Syncing: $eventsSynced events, ${bytesLeft / 1024u} KB left" else "Sync: $stage"
                    }
                }
            })
            prefs.edit().putString("serial", report.serial).apply()
            log("sync done: ${report.eventsSynced} events, ${report.inserted} new, cursor ${report.nextCursor}")
            refreshStatus()
            showSummary()
        }
    }

    private fun confirmPair() {
        if (prefs.getString("key", null) != null) {
            AlertDialog.Builder(this)
                .setTitle("Replace stored key?")
                .setMessage("A key is already stored. Back it up first — pairing again mints a new key and the old one stops working.")
                .setPositiveButton("Pair anyway") { _, _ -> pair() }
                .setNegativeButton("Cancel", null).show()
        } else pair()
    }

    private fun pair() = busy("Pairing…") {
        val session = openSession()
        val report = session.pair(null)
        // Persist immediately: this is the only copy of the ring's key.
        prefs.edit().putString("key", report.keyHex).putString("serial", report.serial).commit()
        log("paired ${report.serial}")
        refreshStatus()
        toast("Paired! Back up the key now.")
        showKey()
    }

    private fun showKey() {
        val key = prefs.getString("key", null) ?: return toast("No key stored.")
        AlertDialog.Builder(this)
            .setTitle("Ring auth key")
            .setMessage("Store this somewhere safe (password manager). Without it, only a factory reset gets the ring back.\n\n$key")
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ring key", key))
            }
            .setNegativeButton("Close", null).show()
    }

    private fun restoreKey() {
        val input = EditText(this).apply { hint = "32 hex characters" }
        AlertDialog.Builder(this)
            .setTitle("Restore auth key").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val k = input.text.toString().trim().lowercase()
                if (Regex("^[0-9a-f]{32}$").matches(k)) {
                    prefs.edit().putString("key", k).commit(); refreshStatus()
                } else toast("Key must be 32 hex characters.")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------- Summary ----------------

    private fun showSummary() {
        if (!File(dbPath).exists()) { summary.text = "No data yet — sync to pull history off the ring."; return }
        scope.launch {
            val tzHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000L
            val text = withContext(Dispatchers.IO) {
                try { JSONObject(summaryJson(dbPath, tzHours)).toString(2) }
                catch (e: Exception) { "Summary error: ${e.message}" }
            }
            summary.text = text.take(20000)
        }
    }

    // ---------------- Export ----------------

    private fun exportDb() {
        if (!File(dbPath).exists()) return toast("Nothing to export yet — sync first.")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.sqlite3"
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
                    // Consistent snapshot via SQLite's backup API, then copy out.
                    val tmp = File(cacheDir, "export.db").apply { delete() }
                    backupDatabase(dbPath, tmp.absolutePath)
                    contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                }
                toast("Exported.")
            } catch (e: Exception) { toast("Export failed: ${e.message}") }
        }
    }

    // ---------------- AI ----------------

    private lateinit var chatView: TextView
    private val turns = mutableListOf<Turn>()
    private var systemPrompt: String? = null

    private fun provider(): AiProvider? {
        val key = prefs.getString("claude_key", null) ?: return null
        return ClaudeProvider(key, prefs.getString("claude_model", null) ?: DEFAULT_MODEL)
    }

    private fun aiSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, 0, pad, 0) }
        val key = EditText(this).apply {
            hint = "Anthropic API key (sk-ant-…)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("claude_key", ""))
        }
        val model = EditText(this).apply { hint = DEFAULT_MODEL; setText(prefs.getString("claude_model", DEFAULT_MODEL)) }
        box.addView(TextView(this).apply {
            text = "Claude sees your data summary when you send a question. Key is stored on this phone only."
        })
        box.addView(key); box.addView(model)
        AlertDialog.Builder(this).setTitle("AI settings").setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("claude_key", key.text.toString().trim())
                    .putString("claude_model", model.text.toString().trim().ifEmpty { DEFAULT_MODEL }).apply()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val ai = provider() ?: return toast("Add your API key in AI settings first.")
        if (!File(dbPath).exists()) return toast("Sync first so there's data to talk about.")
        turns += Turn("user", q)
        renderChat("…thinking")
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    // Build the data context once per chat; "New chat" refreshes it.
                    val sys = systemPrompt ?: HealthPrompt.system(summaryJson(dbPath, tzHours())).also { systemPrompt = it }
                    ai.chat(sys, turns)
                }
                turns += Turn("assistant", reply)
                renderChat()
            } catch (e: Exception) {
                turns.removeAt(turns.lastIndex)
                renderChat("Error: ${e.message}")
            }
        }
    }

    private fun renderChat(pending: String? = null) {
        chatView.text = buildString {
            turns.forEach { append(if (it.role == "user") "You: " else "AI: ").append(it.text).append("\n\n") }
            pending?.let { append(it) }
        }
    }

    private fun tzHours() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000L

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_EXPORT = 42
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
