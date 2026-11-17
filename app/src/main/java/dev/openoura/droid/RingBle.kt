package dev.openoura.droid

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android BLE link to an Oura ring. Mirrors the iOS BLETransport in open_health:
 * one write characteristic (with response), and every notify/indicate
 * characteristic in the Oura service merged into a single inbound frame stream.
 *
 * Android's GATT allows only ONE outstanding operation, so every write and
 * descriptor write goes through a serial queue that advances on its callback.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity first
class RingBle(private val ctx: Context, private val log: (String) -> Unit) {

    companion object {
        val SERVICE: UUID = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")
        val WRITE: UUID = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var ready: CompletableDeferred<Unit>? = null

    /** Called with every inbound notification frame. */
    var onFrame: (ByteArray) -> Unit = {}
    /** Called if the link drops. */
    var onDisconnect: () -> Unit = {}

    // ---- serial GATT operation queue ----
    private sealed class Op {
        class Write(val data: ByteArray) : Op()
        class Notify(val c: BluetoothGattCharacteristic) : Op()
    }
    private val queue = ConcurrentLinkedQueue<Op>()
    @Volatile private var busy = false
    private val lock = Any()

    private fun enqueue(op: Op) { queue.add(op); pump() }

    private fun pump() {
        val g = gatt ?: return
        synchronized(lock) {
            if (busy) return
            val op = queue.poll() ?: return
            busy = true
            val status = when (op) {
                is Op.Write -> {
                    val wc = writeChar ?: run { busy = false; return }
                    g.writeCharacteristic(wc, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                }
                is Op.Notify -> {
                    g.setCharacteristicNotification(op.c, true)
                    val d = op.c.getDescriptor(CCCD)
                    if (d == null) { busy = false; BluetoothStatusCodes.SUCCESS.also { pump() } } else {
                        val v = if (op.c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d, v)
                    }
                }
            }
            if (status != BluetoothStatusCodes.SUCCESS) {
                log("gatt op rejected ($status); retrying")
                queue.add(op); busy = false
                CoroutineScope(Dispatchers.IO).launch { delay(50); pump() }
            }
        }
    }

    private fun opDone() { synchronized(lock) { busy = false }; pump() }

    /** Send one request frame (fire-and-forget; replies arrive via [onFrame]). */
    fun write(data: ByteArray) = enqueue(Op.Write(data))

    // ---- scan + connect ----

    /** Scan for a ring advertising the Oura service; returns the strongest one. */
    suspend fun scan(timeoutMs: Long = 15_000): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: error("Bluetooth is off")
        val found = mutableMapOf<String, ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { found[r.device.address] = r }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        val deadline = System.currentTimeMillis() + timeoutMs
        var firstSeen = 0L
        // Stop ~3 s after the first hit so a stronger nearby ring can still win.
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            if (found.isNotEmpty() && firstSeen == 0L) firstSeen = System.currentTimeMillis()
            if (firstSeen != 0L && System.currentTimeMillis() - firstSeen > 3000) break
        }
        scanner.stopScan(cb)
        val best = found.values.maxByOrNull { it.rssi } ?: return null
        log("found ${best.device.address} (${best.scanRecord?.deviceName ?: "unnamed"}) ${best.rssi} dBm")
        return best.device
    }

    /** Connect, raise the MTU, discover, and subscribe to every notify char. */
    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 30_000) {
        close()
        val done = CompletableDeferred<Unit>()
        ready = done
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) { done.await() }
        } catch (e: Exception) {
            close(); throw IllegalStateException("BLE connect failed: ${e.message}")
        }
    }

    fun device(address: String): BluetoothDevice = adapter.getRemoteDevice(address)

    fun close() {
        queue.clear(); synchronized(lock) { busy = false }
        gatt?.let { it.disconnect(); it.close() }
        gatt = null; writeChar = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("connected; requesting MTU")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("disconnected (status $status)")
                ready?.completeExceptionally(IllegalStateException("disconnected, status $status"))
                onDisconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU $mtu; discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) {
                ready?.completeExceptionally(IllegalStateException("Oura service not found")); return
            }
            writeChar = svc.getCharacteristic(WRITE)
            val notify = svc.characteristics.filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            log("subscribing to ${notify.size} characteristic(s)")
            notify.forEach { enqueue(Op.Notify(it)) }
            // Ready once the last subscription confirms (see onDescriptorWrite).
            if (notify.isEmpty()) ready?.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("subscribe failed on ${d.characteristic.uuid}: $status")
            val pendingNotify = queue.any { it is Op.Notify }
            opDone()
            if (!pendingNotify) { log("link ready"); ready?.complete(Unit) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("write failed: $status")
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onFrame(value)
        }
    }
}
