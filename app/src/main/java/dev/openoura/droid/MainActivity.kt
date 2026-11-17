package dev.openoura.droid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

        summary = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, pad, 0, pad) }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
