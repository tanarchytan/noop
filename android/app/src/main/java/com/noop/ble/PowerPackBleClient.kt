package com.noop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * What the WHOOP battery pack reported over standard GATT reads. Every field is a value the pack
 * sent; an unread one stays null so the UI can omit the row rather than show a made-up zero.
 */
data class PowerPackState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    /** The pack's BLE address while a link is up, else null. */
    val address: String? = null,
    /** Battery Level (0x2A19), whole percent. */
    val batteryPct: Int? = null,
    /** Firmware Revision (0x2A26), verbatim. */
    val firmware: String? = null,
    /** Hardware Revision (0x2A27), verbatim. */
    val hardwareRev: String? = null,
    /** Serial Number (0x2A25), verbatim. */
    val serial: String? = null,
    /** Model Number (0x2A24), verbatim. */
    val model: String? = null,
    /** Why there is no reading right now, in the user's words. Null once the pack answers. */
    val note: String? = null,
) {
    /** True once the pack answered at least one read. Nothing about the pack is shown before this. */
    val hasReading: Boolean get() = batteryPct != null || firmware != null
}

/**
 * READ-ONLY BLE client for the WHOOP battery pack, a peripheral of its own: the phone talks to it
 * directly, never through the strap, so it needs its own scan and its own GATT link.
 *
 * It issues exactly two kinds of GATT operation, service discovery and characteristic READS, and no
 * write of any kind: no command, no descriptor, no subscribe. The pack's vendor service is therefore
 * never touched and its charge state is not read from here (that is the strap's own CHARGING event,
 * routed in [WhoopBleClient]). Reads are queued one at a time because the stack allows one in flight.
 */
@SuppressLint("MissingPermission")
class PowerPackBleClient(
    private val context: Context,
    /** Mirrors each step into the shared strap log, so a pack that never answers is diagnosable. */
    private val log: (String) -> Unit = {},
) {

    companion object {
        /** The name the pack advertises under. */
        const val ADVERTISED_NAME = "Wireless PowerPack"

        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MODEL_CHAR: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        private val SERIAL_CHAR: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_CHAR: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val HARDWARE_CHAR: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")

        /** How long one look for the pack runs before it reports back empty. */
        private const val SCAN_TIMEOUT_MS = 15_000L

        /** Cadence of the battery re-read while the link is up. */
        private const val BATTERY_POLL_MS = 30_000L

        /** Beat between a dropped link and the next look, so a flapping pack cannot spin the radio. */
        private const val RESCAN_DELAY_MS = 5_000L

        /** Rest between an empty look and the next one. A pack asleep on a full strap starts
         *  advertising again when it wakes, so the screen keeps looking rather than giving up once. */
        private const val RETRY_DELAY_MS = 30_000L

        /** How many distinct advertised names one empty scan reports, so the log stays short. */
        private const val SEEN_NAMES_LOGGED = 12

        /** Does an advertised [name] identify the pack? Prefix, case-insensitive and trimmed, so a
         *  pack that appends a unit number or shifts case still matches; the prefix is specific enough
         *  that nothing else can claim it. */
        fun isPowerPackName(name: String?): Boolean =
            name != null && name.trim().startsWith(ADVERTISED_NAME, ignoreCase = true)

        /** A GATT string characteristic as text, or null when it carries nothing readable. */
        fun decodeString(bytes: ByteArray): String? =
            bytes.toString(Charsets.UTF_8).trim { it <= ' ' }.ifEmpty { null }

        /** Battery Level (0x2A19): first byte, whole percent. Anything outside 0..100 is no reading
         *  rather than a clamped one, so a stub or a garbled byte cannot show as a charge. */
        fun decodeBatteryPct(bytes: ByteArray): Int? =
            bytes.firstOrNull()?.let { it.toInt() and 0xFF }?.takeIf { it in 0..100 }
    }

    private val _state = MutableStateFlow(PowerPackState())

    /** The pack as last read. Observed by the Devices screen. */
    val state: StateFlow<PowerPackState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** True between [start] and [stop]; every timer and callback checks it before doing work. */
    private var watching = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null

    /** Characteristics still to be read on this link, drained one at a time. */
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    /** Advertised names seen during a scan that found no pack, for the empty-scan log line. */
    private val seenNames = linkedSetOf<String>()

    /**
     * Look for the pack, read it, and keep the reading fresh until [stop]. A no-op while already
     * watching, so re-entering the screen keeps a live link instead of rescanning.
     */
    fun start() {
        if (watching) return
        watching = true
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            _state.update { it.copy(note = "Bluetooth is off") }
            watching = false
            return
        }
        startScan()
    }

    /** Drop the scan and the link. The last reading is cleared with it, so nothing outlives the pack. */
    fun stop() {
        watching = false
        handler.removeCallbacks(rescan)
        stopScan()
        teardownGatt()
        _state.value = PowerPackState()
    }

    /** Re-open the search after a drop or an empty look, if anyone is still watching. */
    private val rescan: Runnable = Runnable { if (watching) startScan() }

    // MARK: - Scan

    private fun startScan() {
        handler.removeCallbacks(rescan)
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.update { it.copy(scanning = false, note = "Bluetooth is unavailable") }
            return
        }
        seenNames.clear()
        scanning = true
        _state.update { it.copy(scanning = true, note = null) }
        // No service filter: the pack's vendor service UUID is not something we hold, and its name is
        // what identifies it. The window is short and only ever opened from a foreground screen.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val started = runCatching { scanner.startScan(emptyList(), settings, scanCallback) }
            .onFailure { log("PowerPack scan failed to start: ${it.message}") }
            .isSuccess
        if (started) {
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
            return
        }
        scanning = false
        _state.update { it.copy(scanning = false, note = "could not look for it") }
        handler.postDelayed(rescan, RETRY_DELAY_MS)
    }

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        _state.update { it.copy(scanning = false, note = "not found nearby") }
        log(
            "PowerPack not found in ${SCAN_TIMEOUT_MS / 1000}s " +
                "(${seenNames.size} named devices, first ${SEEN_NAMES_LOGGED}: ${seenNames.take(SEEN_NAMES_LOGGED)})",
        )
        if (watching) handler.postDelayed(rescan, RETRY_DELAY_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _state.update { it.copy(scanning = false) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
            if (!isPowerPackName(name)) {
                name?.takeIf { it.isNotBlank() }?.let { seenNames.add(it) }
                return
            }
            if (!watching || gatt != null) return
            stopScan()
            log("PowerPack found at ${result.device.address} — connecting to read it")
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(scanTimeout)
            scanning = false
            _state.update { it.copy(scanning = false, note = "could not look for it") }
            log("PowerPack scan failed: code=$errorCode")
            if (watching) handler.postDelayed(rescan, RETRY_DELAY_MS)
        }
    }

    // MARK: - Link

    private fun connectTo(device: BluetoothDevice) {
        gatt = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) log("PowerPack connectGatt refused by the stack")
    }

    private fun teardownGatt() {
        handler.removeCallbacks(batteryPoll)
        readQueue.clear()
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private val batteryPoll: Runnable = Runnable {
        if (!watching) return@Runnable
        val g = gatt ?: return@Runnable
        // Skip a tick whose predecessor never came back, so a stalled read cannot grow the queue.
        if (readQueue.isEmpty()) {
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_CHAR)?.let { queueRead(g, it) }
        }
        handler.postDelayed(batteryPoll, BATTERY_POLL_MS)
    }

    /** Add [ch] to the read queue and start it if nothing else is in flight. */
    private fun queueRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) return
        val wasIdle = readQueue.isEmpty()
        readQueue.addLast(ch)
        if (wasIdle) runCatching { g.readCharacteristic(ch) }
    }

    private fun readNext(g: BluetoothGatt) {
        readQueue.removeFirstOrNull()
        readQueue.firstOrNull()?.let { runCatching { g.readCharacteristic(it) } }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.update { it.copy(connected = true, address = g.device.address, note = null) }
                runCatching { g.discoverServices() }
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            log("PowerPack link down (status=$status)")
            teardownGatt()
            _state.value = PowerPackState(note = "disconnected")
            // A dropped link while the screen is still open means look again, not go quiet — after a
            // beat, so a pack that keeps dropping cannot spin the radio on a tight scan loop.
            if (watching) handler.postDelayed(rescan, RESCAN_DELAY_MS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt || status != BluetoothGatt.GATT_SUCCESS) return
            val info = g.getService(DEVICE_INFO_SERVICE)
            listOfNotNull(
                g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_CHAR),
                info?.getCharacteristic(FIRMWARE_CHAR),
                info?.getCharacteristic(HARDWARE_CHAR),
                info?.getCharacteristic(SERIAL_CHAR),
                info?.getCharacteristic(MODEL_CHAR),
            ).forEach { queueRead(g, it) }
            handler.postDelayed(batteryPoll, BATTERY_POLL_MS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) apply(characteristic.uuid, value)
            readNext(g)
        }

        @Deprecated("Deprecated in API 33; retained for API 26..32 where the value-bearing overload isn't called")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (g !== gatt) return
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value?.let { apply(characteristic.uuid, it) }
            readNext(g)
        }
    }

    /** Fold one answered read into the state. An unreadable value leaves its field as it was. */
    private fun apply(uuid: UUID, bytes: ByteArray) {
        when (uuid) {
            BATTERY_CHAR -> decodeBatteryPct(bytes)?.let { pct -> _state.update { it.copy(batteryPct = pct, note = null) } }
            FIRMWARE_CHAR -> decodeString(bytes)?.let { fw -> _state.update { it.copy(firmware = fw) } }
            HARDWARE_CHAR -> decodeString(bytes)?.let { hw -> _state.update { it.copy(hardwareRev = hw) } }
            SERIAL_CHAR -> decodeString(bytes)?.let { sn -> _state.update { it.copy(serial = sn) } }
            MODEL_CHAR -> decodeString(bytes)?.let { m -> _state.update { it.copy(model = m) } }
            else -> Unit
        }
    }
}
