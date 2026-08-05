package com.noop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.Manifest
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.noop.data.HrRow
import com.noop.data.RrRow
import com.noop.data.StreamBatch
import com.noop.data.StreamPersistence
import com.noop.data.WhoopRepository
import com.noop.data.LIVE_FLUSH_ROWS
import com.noop.data.liveFlushCutoff
import com.noop.protocol.BackfillCaptureJsonl
import com.noop.protocol.BackfillCaptureRecord
import com.noop.protocol.BackfillCaptureSummary
import com.noop.protocol.CommandNumber
import com.noop.protocol.DeviceFamily
import com.noop.protocol.HapticClock
import com.noop.protocol.Reassembler
import com.noop.protocol.RebootProbeVariant
import com.noop.protocol.RustAdapter
import com.noop.protocol.RustCodec
import com.noop.protocol.Whoop5Config
import com.noop.protocol.extractStreams
import com.noop.protocol.gen
import com.noop.analytics.Baselines
import com.noop.analytics.BatterySocLine
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.NapDetector
import com.noop.analytics.NapPrefs
import com.noop.analytics.NapVerdict
import com.noop.analytics.SedentaryDetector
import com.noop.analytics.StressOnsetDetector
import com.noop.analytics.UserProfile
import com.noop.analytics.WorkoutDetector
import com.noop.data.NapStore
import com.noop.ingest.HealthConnectWriter
import com.noop.notif.InactivityNotifier
import com.noop.ui.BiofeedbackPrefs
import com.noop.ui.InactivityPrefs
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.StressNudgeCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.whoop_ffi.Gen
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** What [WhoopBleClient.renameStrap] did with a name. [message] is the user-facing wording, and is
 *  also what lands in [LiveState.renameStatus], so the two can never drift apart. */
enum class StrapRename(val message: String) {
    Sent("Sent - your strap will reboot to apply, then reconnect with the new name."),
    NotWhoop4("Saved on this phone. Only a WHOOP 4.0 can take the name over Bluetooth."),
    NotConnected("Saved on this phone. Connect the strap to give it the same Bluetooth name."),
    EmptyName("Enter a name first."),
}

/** Whether renaming [device] should also write the strap's advertising name, so one rename keeps the
 *  registry row and the band's Bluetooth name identical. [WhoopBleClient.renameStrap] writes to
 *  whichever strap the client holds, so only the ACTIVE WHOOP row may reach it: an archived or merely
 *  paired row, and any non-WHOOP source, renames locally. */
fun writesStrapName(device: com.noop.data.PairedDeviceRow): Boolean =
    SourceCoordinator.isWhoop(device) && device.status == com.noop.data.DeviceStatus.active.name

/** What a rename reports once the wire write has, or has not, been queued. Only a write the link
 *  accepted may read as [StrapRename.Sent]: the local name is already saved either way, so a refused
 *  write costs nothing but claiming it went would tell the user their band answers to a name it never
 *  took. `sendCommand` returns false when the GATT link or the command characteristic is gone, which
 *  the connected/bonded state flags can lag behind. */
internal fun renameOutcome(queued: Boolean): StrapRename =
    if (queued) StrapRename.Sent else StrapRename.NotConnected

/**
 * Immutable snapshot of the live connection + biometric state. The ViewModel observes this flow.
 *
 *  - [connected] GATT link up. [bonded] one confirmed cmd-char write ACKed (the WHOOP "bond").
 *  - [heartRate] plausible BPM (30..220) from the standard 0x2A37 profile OR the custom REALTIME_DATA frame.
 *  - [rr] most-recent R-R intervals (ms); the standard profile is the reliable source.
 *  - [batteryPct] 5/MG: 0x2A19 whole %; WHOOP 4: GET_BATTERY_LEVEL response u16/10 (4.0's 0x2A19
 *    is a stub constant 100, ignored).
 *  - [worn] wrist-wear from WRIST_ON/WRIST_OFF, defaults true until the first event lands.
 *  - [lastEvent] most-recent strap EVENT string ("WRIST_ON(9)", "DOUBLE_TAP(14)", …).
 */
data class LiveState(
    val connected: Boolean = false,
    val bonded: Boolean = false,
    /** True ONLY when the link reached a GENUINE encrypted bond — 5/MG CLIENT_HELLO ack, WHOOP4
     *  confirmed-write bond, or a strap-reported BLE_BONDED event. NOT set by the live-HR shortcut
     *  that flips [bonded] true on unbonded 5/MG HR streaming, so [bonded] can be true while this is false. */
    val encryptedBond: Boolean = false,
    /** True ONLY when a non-WHOOP live source (currently the Oura ring) is actively streaming live HR.
     *  Deliberately separate from [bonded], which carries WHOOP encrypted-bond + buzz semantics and must
     *  NOT be set by the Oura path. The owning source sets it true while streaming, false at teardown. */
    val streamingLiveHR: Boolean = false,
    val heartRate: Int? = null,
    val rr: List<Int> = emptyList(),
    /** Rolling UI buffer of recent R-R intervals (capped, oldest dropped first). The standard HR
     *  notification usually carries only one or two intervals per packet, so the Live console needs
     *  a short history for a moving R-R strip / rolling RMSSD. Appended via [withRRIntervals]; emptied by [clearedBiometrics]. */
    val rrRecent: List<Int> = emptyList(),
    val batteryPct: Double? = null,
    /** Strap firmware version captured during the connect handshake: WHOOP 4.0 reports `fw_harvard`
     *  (a.b.c.d) via REPORT_VERSION_INFO, WHOOP 5/MG reports `fw_version` via GET_HELLO. Shown on the
     *  Devices card. Null until the handshake response decodes. */
    val strapFirmware: String? = null,
    /** The 5.0 battery pack's charge, as the STRAP reports it (GET_BATTERY_PACK_INFO). The pack talks
     *  NFC to the strap and never BLE to the phone, so the strap is the only path to it. Null on a 4.0,
     *  with no pack attached, or before the first reply. */
    val packSocPct: Double? = null,
    /** The pack's own serial, from the same reply. Null until it answers. */
    val packSerial: String? = null,
    /** The pack's cell voltage in millivolts, from the same reply. */
    val packMillivolts: Int? = null,
    /** Historical record layout version (`hist_version`, e.g. v24/v25 on WHOOP 4.0) observed from the
     *  active connection's backfill. This is distinct from [strapFirmware]: FW 41.17.6.0 is the strap
     *  firmware build, while v24/v25 is the binary layout used by banked history records. */
    val historyLayoutVersion: Int? = null,
    /** True while a user-initiated reboot is in flight — from sending REBOOT_STRAP until the strap
     *  reconnects (or the settle timeout gives up). With `!connected` it drives the Devices card's
     *  transient "Reconnecting…" pill. */
    val rebootInProgress: Boolean = false,
    /** Charging flag from BATTERY_LEVEL events — wire observation: u8 bit0 (4.0 @26 / 5.0 @30,
     *  ~every 8 min on captured links). Flag only; battery % keeps its own family source.
     *  Cleared on disconnect so a stale flag can't outlive the link. */
    val charging: Boolean? = null,
    /** Wrist-wear from WRIST_ON/WRIST_OFF events. Defaults TRUE — assume worn until the strap says
     *  otherwise, so wear-gated features don't read "off" before the first event arrives. */
    val worn: Boolean = true,
    val lastEvent: String? = null,
    /** The strap's current BLE advertising name (the WHOOP 4.0 device name from the OS), captured on
     *  connect. This is the name the Devices rename writes over. Null until connected. */
    val advertisingName: String? = null,
    /** Wording of the last strap-rename attempt, the [StrapRename] the Devices rename returned.
     *  Replaced by the next attempt. */
    val renameStatus: String? = null,
    /** True while actively scanning for the strap (so the UI can show "Searching…"). */
    val scanning: Boolean = false,
    /** Human-readable reason for the current state (why it can't connect, what to try). */
    val statusNote: String? = null,
    /** A WHOOP 5/MG strap was found. It connects and its battery reads, but live data needs an
     *  MG secure handshake that isn't supported yet — so the UI explains that honestly instead of
     *  showing the generic "charge it and put it on" checklist. */
    val whoop5Detected: Boolean = false,
    /** True while a historical offload session is running, so screens can say "Syncing strap
     *  history…" instead of presenting half-loaded data as final. */
    val backfilling: Boolean = false,
    /** Chunks acked during the current offload session — an honest progress signal (total pending is
     *  unknowable from the protocol, so no percent). Republished every ~10 chunks: the foreground
     *  service re-posts its notification on EVERY LiveState emission, so per-chunk would spam it. */
    val syncChunksThisSession: Int = 0,
    /** Wall-clock (unix seconds) of the last offload that ran to HISTORY_COMPLETE, or null if none
     *  this process. For a cloud-free app this is the honest "is sync actually working?" answer — the
     *  UI renders it as a relative "Last synced N ago". */
    val lastSyncAt: Long? = null,
    /** Set when an offload ended abnormally (strap went quiet mid-sync / idle-watchdog fired), so a
     *  stalled history download isn't silent. Cleared on the next successful HISTORY_COMPLETE. */
    val lastSyncError: String? = null,
    /** Set when a connect attempt fails because the strap wiped its Bluetooth bond (firmware reset, or
     *  the official WHOOP app re-bonding it) — the OS still holds a stale bond, so a direct connect
     *  just re-fails. Carries a forget+re-pair guide; cleared on the next successful connect. */
    val reconnectGuide: String? = null,
    /** Set when a WHOOP 5/MG strap keeps REFUSING the encrypted bond (still bonded to the official
     *  WHOOP app, so a fresh just-works bond can't start). Carries pairing guidance; set once refusals
     *  reach two, cleared on a genuine bond or a fresh connect. Mirrored into [statusNote]. */
    val pairingHint: String? = null,
    /** EXPERIMENTAL R22 telemetry: how many of the 15 enable_r22 SET_CONFIG flags the strap has ACKed
     *  since the last "Send enable sequence" tap. 15 = the strap accepted the whole sequence (it returns
     *  a COMMAND_RESPONSE per flag). Reset per attempt + per session. */
    val r22FlagsAccepted: Int = 0,
    /** Count of type-0x2F records seen this session OUTSIDE our own history offload — e.g. another
     *  BLE client pulling the strap's backlog over the shared notify channel. NOT a separate live R22
     *  stream; type-0x2F is only ever the historical offload. Diagnostic counter only. */
    val deepPacketsThisSession: Int = 0,
    /** TRUE when a connected WHOOP 5/MG streams live HR fine but its firmware hands over NO history
     *  offload (acks SEND_HISTORICAL_DATA, emits zero type-0x2F frames). Surfaces as "history sync
     *  experimental on 5.0" instead of a sync error; also backs off the 120s liveness bounce. */
    val historySyncExperimental: Boolean = false,
) {
    /** Set the fresh-packet [rr] AND append the valid intervals onto the bounded [rrRecent] rolling
     *  buffer (oldest fall off first). Non-positive sentinels are dropped from the rolling buffer. */
    fun withRRIntervals(intervals: List<Int>, recentLimit: Int = 60): LiveState {
        val valid = intervals.filter { it > 0 }
        if (valid.isEmpty()) return copy(rr = intervals)
        val merged = rrRecent + valid
        val capped = if (merged.size > recentLimit) merged.takeLast(recentLimit) else merged
        return copy(rr = intervals, rrRecent = capped)
    }

    /** Blank all live biometric readouts (HR + R-R + the rolling buffer + battery) so a stale reading
     *  can't outlive the link. Applied on disconnect alongside the charging/bond clears.
     *
     *  [batteryPct] belongs here because it is as strap-specific as the firmware readout beside it, and
     *  a 5/MG only ever reports it from a polled 0x2A19 read — so connecting a DIFFERENT strap would
     *  otherwise keep showing the previous one's charge until a read happened to land. */
    fun clearedBiometrics(): LiveState = copy(heartRate = null, rr = emptyList(), rrRecent = emptyList(),
                                              batteryPct = null,
                                              streamingLiveHR = false)   // a dropped link is no longer streaming
}

/**
 * Thin injectable indirection over the raw [BluetoothGatt] operations the client calls.
 *
 * Production wires [RealGattOps] (a straight delegate to a live `BluetoothGatt`). Unit tests inject a
 * stub whose methods throw `android.os.DeadObjectException` to exercise crash-safety teardown without
 * a full GATT mock. Deliberately minimal: only calls that can throw once the OS Bluetooth binder dies
 * mid-link are routed through it; everything else stays on the concrete handle.
 *
 * Boolean returns mirror `BluetoothGatt`'s contract (true = accepted by the stack). A THROW is
 * distinct from `false`: `false` is a transient BUSY (retry), a throw is a dead binder (tear down).
 * See [WhoopBleClient.safeGatt].
 */
interface GattOps {
    fun writeCharacteristicCompat(
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean

    fun writeDescriptorCompat(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean

    fun readCharacteristicCompat(ch: BluetoothGattCharacteristic): Boolean
    fun setCharacteristicNotificationCompat(ch: BluetoothGattCharacteristic, enable: Boolean): Boolean
    fun requestMtuCompat(mtu: Int): Boolean
    fun readRemoteRssiCompat(): Boolean
    fun discoverServicesCompat(): Boolean
}

/**
 * Production [GattOps]: a straight delegate to a live [BluetoothGatt]. The TIRAMISU+/legacy branch
 * for the value-bearing write/descriptor calls lives here (one place) so the client call sites read
 * uniformly. Permission is owned by the caller (the client is @SuppressLint("MissingPermission")).
 */
@SuppressLint("MissingPermission")
class RealGattOps(private val gatt: BluetoothGatt) : GattOps {
    override fun writeCharacteristicCompat(
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, value, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = value
                gatt.writeCharacteristic(ch)
            }
        }

    override fun writeDescriptorCompat(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }

    override fun readCharacteristicCompat(ch: BluetoothGattCharacteristic): Boolean =
        gatt.readCharacteristic(ch)

    override fun setCharacteristicNotificationCompat(
        ch: BluetoothGattCharacteristic,
        enable: Boolean,
    ): Boolean = gatt.setCharacteristicNotification(ch, enable)

    override fun requestMtuCompat(mtu: Int): Boolean = gatt.requestMtu(mtu)
    override fun readRemoteRssiCompat(): Boolean = gatt.readRemoteRssi()
    override fun discoverServicesCompat(): Boolean = gatt.discoverServices()
}

/**
 * BLE engine for the WHOOP 4.0 and WHOOP 5.0/MG straps.
 *
 * Lifecycle: [connect] scans the WHOOP4 custom-service UUID → onScanResult stops the scan and calls
 * connectGatt → CONNECTED triggers discoverServices → onServicesDiscovered captures the cmd-write
 * char, fires THE BOND (one confirmed GET_BATTERY_LEVEL write), then subscribes the three custom
 * notify chars plus the standard HR/battery chars → onCharacteristicWrite's ACK means bonding
 * succeeded and runs the connect handshake EXACTLY ONCE (connectHandshakeDone guard) →
 * onCharacteristicChanged routes inbound bytes: HR char 0x2A37 → standard HR + R-R, battery char
 * 0x2A19 first byte = percent, custom notify chars → Reassembler.feed → RustAdapter.parseFrame → LiveState.
 *
 * API 31+: caller must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT before [connect]. On API <= 30,
 * BLUETOOTH/BLUETOOTH_ADMIN are install-time but scanning also needs a LOCATION grant unless
 * BLUETOOTH_SCAN declares `neverForLocation` (scan filters by service UUID only, never derives
 * location). Every android.bluetooth call here is @SuppressLint("MissingPermission"); the caller
 * owns the permission request and must not call in before it's granted.
 */
class WhoopBleClient(
    private val context: Context,
    /**
     * Local store the decoded live + historical streams are persisted into. Defaults to the
     * process-wide Room-backed repository.
     */
    private val repository: WhoopRepository = WhoopRepository.from(context),
    /**
     * Stable device id; all rows are stamped with this. Resolved at startup from
     * [DeviceRegistry.activeDeviceId], falling back to [DEFAULT_DEVICE_ID] ("my-whoop"). MUTABLE
     * (multi-WHOOP): [setActiveDeviceId] re-points it so a WHOOP switch attributes new samples to the
     * newly-active WHOOP immediately; the [Backfiller] captured its own copy at construction, so
     * [setActiveDeviceId] re-points that too.
     */
    private var deviceId: String = DEFAULT_DEVICE_ID,
    /** Durable trim-cursor store for the offload safe-trim watermark (see [Backfiller]). */
    private val cursorStore: TrimCursorStore = PrefsTrimCursorStore(context),
    /**
     * Opt-in switch for the EXPERIMENTAL WHOOP 5.0/MG ("puffin") protocol probes (default OFF).
     * Read fresh from SharedPreferences each connect so a Settings toggle takes effect on the next
     * scan. NEVER consulted for WHOOP 4.0.
     */
    private val puffinExperiment: PuffinExperiment = PuffinExperiment.from(context),
    /**
     * Builds the [GattOps] indirection from a live [BluetoothGatt]. Production uses [RealGattOps];
     * unit tests inject a factory that returns a stub whose calls throw `DeadObjectException` to
     * exercise the crash-safety teardown without a full GATT mock.
     */
    private val gattOpsFactory: (BluetoothGatt) -> GattOps = ::RealGattOps,
) {

    companion object {
        private const val TAG = "WhoopBleClient"
        /**
         * Cap on the in-app strap-log ring buffer (for the "Share strap log" diagnostics export).
         * Retains a rolling ~24h of activity: a busy live session emits a few lines a minute, so
         * 5,000 short lines comfortably spans a day while staying well under ~1 MB.
         */
        private const val LOG_BUFFER_MAX = 5000

        /**
         * Fallback device id when the registry has no active device yet (fresh install before it
         * seeds, or an all-archived registry).
         */
        const val DEFAULT_DEVICE_ID = "my-whoop"

        // GATT UUIDs. WHOOP 4.0 custom service + its four characteristics. The shared contract also
        // lists a WHOOP5 service UUID; we scan for both so a v5 strap is discoverable, but the verified
        // characteristic/bond flow is the v4 layout (the only hardware-verified path).
        val WHOOP4_SERVICE: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")
        private val CMD_WRITE_CHAR: UUID = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")   // CMD → strap
        private val CMD_NOTIFY_CHAR: UUID = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6")  // responses
        private val EVENT_NOTIFY_CHAR: UUID = UUID.fromString("61080004-8d6d-82b8-614a-1c8cb0f8dcc6") // events
        private val DATA_NOTIFY_CHAR: UUID = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6")  // data (fragmented)

        val WHOOP5_SERVICE: UUID = UUID.fromString("fd4b0001-cce1-4033-93ce-002d5875f58a")
        // WHOOP 5.0/MG command-write char — takes the static CLIENT_HELLO (EXPERIMENTAL).
        val WHOOP5_CMD_WRITE_CHAR: UUID = UUID.fromString("fd4b0002-cce1-4033-93ce-002d5875f58a")
        // WHOOP 5.0/MG ("puffin") notify chars — realtime HR rides these as REALTIME_DATA frames, NOT
        // the standard 0x2A37 profile. They require an encrypted/bonded link, so they're subscribed
        // only AFTER the CLIENT_HELLO confirmed-write bonds.
        private val WHOOP5_NOTIFY_CHARS: List<UUID> = listOf(
            UUID.fromString("fd4b0003-cce1-4033-93ce-002d5875f58a"),
            UUID.fromString("fd4b0004-cce1-4033-93ce-002d5875f58a"),
            UUID.fromString("fd4b0005-cce1-4033-93ce-002d5875f58a"),
            UUID.fromString("fd4b0007-cce1-4033-93ce-002d5875f58a"),
        )

        // Standard BLE profiles. HR + R-R works UNBONDED; battery is a plain %.
        private val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Device Information: the strap's own serial. Not advertised, so it is only readable over a
        // connection; it is what keeps a band's history when its BLE address changes.
        private val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val SERIAL_NUMBER_CHAR: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")

        /** Device Information: the strap's hardware revision, stored verbatim on its registry row. It is
         *  what separates a 5.0 board from an MG one; the prefix table that classifies it stays in
         *  whoop-rs, so nothing here reads meaning into the string. */
        private val HARDWARE_REVISION_CHAR: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")

        /** Attempts at the Device Information reads (0x2A25 then 0x2A27), and the delay before each.
         *  Spaced past the CCCD drain and the bond so a read cannot take the single in-flight GATT slot
         *  from them; a strap that never answers keeps its address-derived identity. Two values are
         *  wanted and one read goes out per tick, so the ladder carries a spare beyond the retries. */
        private val DEVICE_INFO_READ_DELAYS_MS = longArrayOf(3_000L, 8_000L, 10_000L, 20_000L)

        // Client Characteristic Configuration Descriptor — written to enable notifications
        // (Android requires the explicit write; the local stack also needs setCharacteristicNotification).
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** After this many CONSECUTIVE involuntary reconnect attempts, drop the scan from LOW_LATENCY
         *  to a lower-power mode — a strap genuinely out of range would otherwise hold the radio at
         *  full power indefinitely. A user-driven Connect resets [failedReconnectAttempts], so a manual reconnect always scans at LOW_LATENCY. */
        const val SCAN_POWER_BACKOFF_THRESHOLD = 6

        /** Scan-mode decision: an INVOLUNTARY reconnect scan past [SCAN_POWER_BACKOFF_THRESHOLD]
         *  consecutive attempts uses BALANCED; everything below that, and every user-initiated connect
         *  (streak 0), stays LOW_LATENCY. The Add-a-WHOOP wizard's present-scan is hard-wired LOW_LATENCY, never calls this. */
        fun scanModeForReconnectAttempts(attempts: Int): Int =
            if (attempts >= SCAN_POWER_BACKOFF_THRESHOLD) ScanSettings.SCAN_MODE_BALANCED
            else ScanSettings.SCAN_MODE_LOW_LATENCY

        /** Escalate a reconnect to PASSIVE (autoConnect=true) by WHY the link is down, not just attempt
         *  count. A strap the OS still holds ACL-connected never re-emits the advertisement PASSIVE
         *  waits for, so PASSIVE stalls it — keep an ACL-held band on DIRECT; only a genuinely-out-of-range band falls back to PASSIVE past [threshold]. */
        fun passiveReconnectDecision(failedAttempts: Int, aclHeld: Boolean, threshold: Int = 3): Boolean =
            failedAttempts >= threshold && !aclHeld

        /** Low-battery throttle gate. Armed by [thresholdPct] > 0 (0 disables it — not even Battery
         *  Saver can force it). Once armed, engages while DISCHARGING at/below [thresholdPct] or when
         *  OS Battery Saver ([powerSave]) is on; charging never throttles. */
        fun idleThrottleActive(batteryPct: Int, charging: Boolean, thresholdPct: Int, powerSave: Boolean): Boolean =
            thresholdPct > 0 && !charging && (batteryPct <= thresholdPct || powerSave)

        /** Stretched periodic-offload interval when the phone is low on battery. Pure sync timer
         *  (separate from the live-stream keep-alive), so stretching can't affect link health — the
         *  strap banks everything to flash meanwhile. [LOW_BATTERY_BACKFILL_INTERVAL_MS] while discharging at/below [thresholdPct], else [baseMs]. */
        fun offloadIntervalMsFor(
            baseMs: Long,
            lowBatteryMs: Long,
            batteryPct: Int,
            charging: Boolean,
            thresholdPct: Int,
            powerSave: Boolean,
        ): Long = if (idleThrottleActive(batteryPct, charging, thresholdPct, powerSave)) maxOf(baseMs, lowBatteryMs) else baseMs

        /** Keep/teardown decision for [prepareForPresentScan]: keep the live link ONLY when one exists
         *  AND the wizard is scanning the SAME model. [WhoopModel] has exactly two members (one per
         *  family), so enum equality IS the family check — do not invent a deviceFamily accessor. */
        fun shouldKeepLiveConnectionForPresentScan(
            connected: Boolean,
            selected: WhoopModel,
            requested: WhoopModel,
        ): Boolean = connected && selected == requested

        /** Minimum time since the bond-loop pause tripped (or the last probe) before another salvage
         *  probe may fire. 10 min: long enough to keep a still-held strap to a handful of attempts per
         *  day, short enough that a freed strap reconnects on the next natural app open. */
        const val BOND_LOOP_SALVAGE_FLOOR_MS = 10L * 60_000L

        /** Gate for the one-shot bond-loop salvage probe: probe ONLY while the pause is latched, no
         *  live link, no user teardown in force, and at least [BOND_LOOP_SALVAGE_FLOOR_MS] since the
         *  pause tripped or the previous probe. null ms (no trip timestamp) = never probe. */
        fun shouldSalvageProbe(
            pausedForBondLoop: Boolean,
            connected: Boolean,
            intentionalDisconnect: Boolean,
            msSincePauseTripped: Long?,
        ): Boolean = pausedForBondLoop && !connected && !intentionalDisconnect &&
            msSincePauseTripped != null && msSincePauseTripped >= BOND_LOOP_SALVAGE_FLOOR_MS

        /** Give up a scan after this long with no strap found, and tell the user why. */
        private const val SCAN_TIMEOUT_MS = 20_000L
        /** Rotate to the other WHOOP family after this long with no discovery, in case the persisted
         *  preference went stale after an update/restore. */
        private const val SCAN_FALLBACK_DELAY_MS = 8_000L

        // Live-persistence cadence.
        /** Flush the live buffer after this many frames OR [FLUSH_MAX_INTERVAL_MS], whichever first. */
        private const val FLUSH_MAX_FRAMES = 64
        private const val FLUSH_MAX_INTERVAL_MS = 30_000L

        // Historical-offload timers.
        /** Periodic re-offload of the type-47 store while connected+bonded. 900s = 15 min (matches WHOOP). */
        private const val BACKFILL_INTERVAL_MS = 900_000L
        /** Battery: stretched offload cadence while low on battery (45 min). The strap banks to flash
         *  meanwhile, so this only delays sync (larger batches), never loses data. Gated on the discharging
         *  battery-% threshold; 0 = disabled → always [BACKFILL_INTERVAL_MS]. */
        private const val LOW_BATTERY_BACKFILL_INTERVAL_MS = 2_700_000L
        /** How far back the inactivity check reads gravity on each offload completion (4 h comfortably
         *  spans the threshold + re-nudge cadence and a separating Active break for bout continuity). */
        private const val INACTIVITY_LOOKBACK_S = 4 * 3600L
        /**
         * Idle watchdog: if no genuine offload frame arrives for this long mid-session, end the
         * session (the durable strap_trim cursor means the next session resumes where we left off).
         * Generous (60s, not 20s) because the type-43 raw flood eats BLE airtime between chunks.
         */
        private const val BACKFILL_IDLE_TIMEOUT_MS = 60_000L
        /** Deferral before the first connect-time offload, so SET_CLOCK/GET_DATA_RANGE round-trip first. */
        private const val INITIAL_BACKFILL_DELAY_MS = 1_500L
        /** 5/MG fail-open gate: how long to wait for a GET_DATA_RANGE SUCCESS before requesting
         *  history anyway (real hardware sometimes swallows the first range query). */
        private const val DATA_RANGE_GATE_MS = 2_000L
        /** 5/MG zero-frame retry: pause before re-requesting history when a session timed out having
         *  produced nothing (the first request after connect can go entirely unanswered). */
        private const val WHOOP5_HISTORY_RETRY_DELAY_MS = 700L
        /** Debounce between a committed backfill chunk and the on-device scoring pass it schedules. */
        private const val POST_BACKFILL_ANALYZE_DELAY_MS = 1_500L
        /** Window after the last offload frame/HISTORY_COMPLETE during which a type-0x2F frame is
         *  treated as trailing-historical, not live (10s). */
        private const val DEEP_PACKET_LIVE_COOLDOWN_MS = 10_000L

        /** ATT MTU to request on connect. Default 23 caps every notification at 20 payload bytes, so
         *  offload fragments across many notifications. 247 (the common BLE max) lets a full type-47
         *  record ride one packet; benefits both families' offload. */
        private const val GATT_MTU = 247
        /** Proceed to service discovery even if onMtuChanged never fires (some stacks ignore
         *  requestMtu); keeps connect from stalling behind the MTU exchange. */
        private const val MTU_FALLBACK_MS = 1_500L
        /** BASE bonded-handshake watchdog window: bounce the link if no genuine bond lands within this
         *  of service discovery starting (a OnePlus Nord 2 wedged the post-discovery bond/CCCD phase
         *  with no timeout). 7s spans MTU exchange → discovery → CCCD drain → bond write; [bondWatchdogBackoff] escalates it per consecutive bounce. */
        private const val BOND_WATCHDOG_MS = 7_000L
        /** OnePlus-only settle delay before the FIRST CCCD write after service discovery — the Nord 2
         *  GATT stack needs a beat to settle, or the first descriptor write races it and returns BUSY.
         *  ~450ms is well within the 7s bond watchdog. */
        private const val ONEPLUS_CCCD_SETTLE_MS = 450L
        /** Dedup window for a spurious duplicate onMtuChanged: a second callback with the SAME mtu
         *  arriving within this of the first is the OnePlus double-MTU bug and is ignored. */
        private const val DUPLICATE_MTU_WINDOW_MS = 1_000L

        /** ATT error codes the GATT stack surfaces as `status` when a strap refuses the encrypted bond.
         *  Equal to BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION/_ENCRYPTION; pinned here as raw values
         *  because the underlying ATT codes are what some stacks pass through. */
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5    // BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15       // BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
        /** GATT disconnect `status` for a link-supervision/connection timeout — the stack's
         *  `GATT_CONN_TIMEOUT` (HCI 0x08). Pinned as a raw value (no public BluetoothGatt const). */
        private const val GATT_CONN_TIMEOUT = 0x08               // GATT_CONN_TIMEOUT (HCI link-supervision timeout)
        /** GATT disconnect `status` when the LOCAL host tears the link down — reported for our own
         *  `gatt.disconnect()`, including a bond-watchdog bounce. Distinct from [GATT_CONN_TIMEOUT]
         *  (0x08, the strap/link timing out): a bounce we initiated must not be mistaken for a remote timeout. */
        private const val GATT_CONN_TERMINATE_LOCAL_HOST = 0x16  // GATT_CONN_TERMINATE_LOCAL_HOST (local host ended it)

        /** Should an involuntary disconnect feed the bond-watchdog give-up counter? True only when the
         *  link reached STATE_CONNECTED but never bonded, the drop was involuntary, it wasn't the
         *  stale-direct-bond fallback, and it wasn't our own localTerminate bounce (already counted by
         *  [onBondWatchdog]) — catches the connect→subscribe→drop loop that neither give-up counter otherwise sees. */
        internal fun shouldCountNeverBondedSelfDrop(
            wasConnected: Boolean,
            didBond: Boolean,
            intentionalDisconnect: Boolean,
            staleDirectBond: Boolean,
            status: Int,
            alreadyPausedForBondLoop: Boolean,
        ): Boolean = wasConnected && !didBond && !intentionalDisconnect && !staleDirectBond &&
            status != GATT_CONN_TERMINATE_LOCAL_HOST && !alreadyPausedForBondLoop

        /** Consecutive bond refusals on the pinned strap before handing the pin off to a different,
         *  live-bonding strap. 3 (not 1): a single "insufficient" can be a transient just-works
         *  race; three in a row on the pin while ANOTHER strap bonds fine is an unrecoverable stale pin. */
        private const val PIN_BOND_REFUSAL_LIMIT = 3

        /** Encrypted-bond refusals before the pairing hint shows. 2 (not 1): a single "insufficient"
         *  can be a transient just-works race, but two in a row means the strap is genuinely still bonded
         *  to another app. */
        private const val BOND_REFUSAL_HINT_THRESHOLD = 2

        /** Concrete pairing-mode guidance for a WHOOP 5/MG that keeps refusing the encrypted bond because
         *  it's still bonded to the official WHOOP app. Plain, country-neutral wording; Android
         *  settings path. */
        private const val PAIRING_HINT_TEXT =
            "Your WHOOP won't pair because it's still bonded to the official WHOOP app. To fix it: " +
                "1. Close the official WHOOP app (or turn off Bluetooth on that phone). " +
                "2. Hold or tap the band until its LEDs flash blue (pairing mode). " +
                "3. Open Settings > Bluetooth, find your WHOOP, and choose Forget This Device. " +
                "Then come back and tap Connect."

        /** 5/MG raw-capture file (app filesDir; shared via Settings → "Share 5/MG capture"). */
        const val WHOOP5_CAPTURE_FILE = "whoop5-backfill-capture.jsonl"
        const val WHOOP5_EVENT_LOG_FILE = "whoop5-events.jsonl"
        // EVENT frames are ~40–120 B of hex each, a few KB per day of wear — 5 MB is years.
        private const val WHOOP5_EVENT_LOG_MAX_BYTES = 5L * 1024 * 1024

        /** High-rate R22 deep-buffer research log — the big type-0x2F buffers (1244/2140 B) carrying
         *  tens-of-Hz motion/optical, kept raw so they survive long enough to reverse. Bigger cap than
         *  the EVENT log (60 MB live, rotation bounds disk at ~120 MB) since bursts run ~4.3 KB of hex each. */
        const val WHOOP5_DEEPBUFFER_FILE = "whoop5-deepbuffers.jsonl"
        private const val WHOOP5_DEEPBUFFER_MAX_BYTES = 60L * 1024 * 1024

        /** WHOOP 5/MG inner-record type byte for EVENT frames (type 48). The inner record starts at
         *  offset 8 ([type][seq][cmd][data…]) — the SAME position [isOffloadFrame]/R22-telemetry index
         *  and the Interpreter reads the canonical type name from. */
        const val WHOOP5_EVENT_TYPE = 0x30
        private const val WHOOP5_INNER_RECORD_OFFSET = 8

        /** Is [frame] a WHOOP 5/MG EVENT (type 48 / 0x30)? The inner-record type byte sits at offset 8,
         *  so this needs `size > 8` before indexing. Pure, unit-testable without a strap. */
        fun isWhoop5EventFrame(frame: ByteArray): Boolean =
            frame.size > WHOOP5_INNER_RECORD_OFFSET &&
                (frame[WHOOP5_INNER_RECORD_OFFSET].toInt() and 0xFF) == WHOOP5_EVENT_TYPE
        /** Rotation threshold (~10 MB) and absolute per-file line cap (a full overnight offload is
         *  ~28k frames; 40k leaves headroom so a real overnight session isn't truncated). */
        private const val WHOOP5_CAPTURE_MAX_BYTES = 10L * 1024 * 1024
        private const val WHOOP5_CAPTURE_MAX_LINES = 40_000

        /** Live-gesture freshness window (seconds). A DOUBLE_TAP / WRIST_* event only updates live state
         *  if its event_timestamp is within this of wall-now, so a *replayed historical* gesture during a
         *  backfill offload is ignored. */
        private const val LIVE_GESTURE_WINDOW_SECONDS = 45L

        // Live-stream keep-alive: WHOOP firmware lets the realtime HR stream lapse if it isn't re-armed
        // (a stuck-on-stale HR that only a manual disconnect/reconnect fixes). Re-arm + poll battery
        // every 30s; bounce a truly silent link after 120s (the auto version of disconnect+reconnect).
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        /** Delay after the 5/MG connect handshake before the first battery read (0x2A19), so it does not
         *  race the clock writes on a slow stack while still populating the ring within a couple of seconds
         *  of connect. */
        private const val BATTERY_ON_CONNECT_DELAY_MS = 1_500L
        /** No inbound data for this long ⇒ the link/stream stalled; bounce it to resume streaming. */
        private const val KEEPALIVE_STALL_MS = 120_000L
        /** Longer stall fuse for a known history-empty 5/MG. Live HR over 0x2A37 keeps the link alive
         *  but can lull >120s (off-wrist / resting) while the empty offload leaves the data channel quiet,
         *  so the tight 120s rule bounced a healthy link every ~2 min. 10 min stops the thrash. */
        private const val KEEPALIVE_STALL_5MG_EMPTY_MS = 600_000L
        /** Stream gone quiet this long (but not yet stall) ⇒ re-subscribe in case a CCCD silently dropped. */
        private const val KEEPALIVE_QUIET_MS = 45_000L

        /** A CCCD write can transiently return BUSY if the stack slot hasn't freed yet; retry the same
         *  subscribe a few times (short backoff) before giving up, rather than dropping the stream. */
        private const val CCCD_RETRY_DELAY_MS = 60L
        private const val MAX_CCCD_RETRIES = 8

        /** A command write can transiently return BUSY on a stricter stack when the previous write
         *  hasn't physically completed. Retry the SAME frame a few times (short backoff) instead of
         *  dropping it — a dropped TOGGLE_REALTIME_HR/SET_CLOCK/offload-ack silently breaks live HR, the clock, or the backfill. */
        // Base backoff; per-frame delay ESCALATES (× attempt) so a sustained-BUSY stack (a Pixel 7 on
        // Android 16 logged ~56 busy retries + a few hard drops in 10 min) gets progressively more time
        // instead of burning the whole budget in ~70ms.
        private const val WRITE_RETRY_DELAY_MS = 12L
        private const val MAX_WRITE_RETRIES = 12
        /** Pacing gap before freeing the slot after a WITHOUT-response write. A bare post fires the next
         *  write on the same looper tick — before Android's GATT has accepted the previous one, which it
         *  then rejects. A small gap lets the stack settle and largely eliminates the rejections. */
        private const val WITHOUT_RESPONSE_PACE_MS = 8L
        /** Delay before reading link RSSI after connect — past the bond/MTU/discovery handshake so the
         *  read can't occupy the single GATT op slot the critical setup commands need. Diagnostic only. */
        private const val RSSI_READ_DELAY_MS = 3000L

        /** True when [frame] is part of the historical offload (HISTORICAL_DATA=47, EVENT=48,
         *  METADATA=49, CONSOLE_LOGS=50) rather than the live stream (REALTIME_DATA=40,
         *  REALTIME_RAW_DATA=43). The live type-43 raw flood streams continuously and unprompted, so the idle-watchdog must not re-arm on it. */
        fun isOffloadFrame(frame: ByteArray, family: DeviceFamily): Boolean {
            // WHOOP 5/MG's inner record starts at byte 8 (+4 envelope), and its HISTORY_END/COMPLETE is
            // PUFFIN_METADATA=56, not 49. Reading frame[4] with {47,48,49,50} drops every 5/MG
            // offload-closing frame as live-flood, so the strap never trims and offload never completes.
            val typeIndex = if (family == DeviceFamily.WHOOP5) 8 else 4
            if (frame.size <= typeIndex) return false
            return when (frame[typeIndex].toInt() and 0xFF) {
                47, 48, 49, 50, 56 -> true // HISTORICAL_DATA / EVENT / METADATA / CONSOLE_LOGS / PUFFIN_METADATA
                // HISTORICAL_IMU_DATA_STREAM — a genuine 5/MG history BODY type (observed in bulk on real
                // ACK-enabled hardware captures). 5/MG-only; never seen from a WHOOP 4.
                52 -> family == DeviceFamily.WHOOP5
                else -> false // 40 REALTIME_DATA, 43 REALTIME_RAW_DATA (live flood)
            }
        }

        /** The gate every offload kick passes through: a sync may start ONLY when the link is up
         *  ([connected]), the command channel is usable ([bonded]), and no offload is already running
         *  ([backfilling]). Shared so the auto-kick, periodic timer, and manual "Sync now" can't drift apart. */
        fun canRequestSync(connected: Boolean, bonded: Boolean, backfilling: Boolean): Boolean =
            connected && bonded && !backfilling

        /** Should a Throwable that escaped a raw GATT call trigger a full link teardown? Once the OS
         *  Bluetooth binder dies mid-link, GATT calls throw `DeadObjectException`, `IllegalStateException`
         *  (adapter off), or `SecurityException` (permission revoked) — all mean the link is unusable, so the answer is always `true`; there is no recoverable GATT throw. */
        fun shouldTeardownOnGattThrow(t: Throwable): Boolean = when (t) {
            is android.os.DeadObjectException,   // binder died
            is IllegalStateException,            // adapter/stack in a bad state
            is SecurityException,                // BLUETOOTH_CONNECT revoked mid-link
            -> true
            // Any other RuntimeException out of a GATT call is equally unrecoverable: there is no path
            // where continuing to drive a throwing GATT is correct, so tear down rather than crash.
            else -> true
        }

        /** When the write queue DROPS a frame after [MAX_WRITE_RETRIES] busy-retries, should the
         *  realtime stream re-arm? True ONLY for [CommandNumber.TOGGLE_REALTIME_HR] — that write enables
         *  live R-R, and a silent drop otherwise leaves R-R off with no re-send. Every other dropped frame has its own recovery and must not poke the realtime latch. */
        fun shouldReArmRealtimeAfterDrop(droppedCmd: CommandNumber?): Boolean =
            droppedCmd == CommandNumber.TOGGLE_REALTIME_HR

        /**
         * The LiveState the teardown path publishes after the link drops. Pure model of the
         * `connected = false` + biometrics-cleared transition so a test can assert the UI flips to
         * disconnected without a live instance.
         */
        fun disconnectedLiveState(previous: LiveState): LiveState =
            previous.clearedBiometrics().copy(
                connected = false, bonded = false, encryptedBond = false,
                backfilling = false, syncChunksThisSession = 0, charging = null,
                // Stale firmware/layout readouts must not outlive the dropped link.
                strapFirmware = null, historyLayoutVersion = null,
                // The 5/MG "history experimental" note is per-link — a fresh connect re-derives it
                // from the next offload, so it must not outlive the dropped link.
                historySyncExperimental = false,
            )

        /** Should a BATTERY_LEVEL event drive the LIVE charging pill? True unless it's a HISTORICAL
         *  BATTERY_LEVEL replayed mid-backfill (an offload frame) — the only case that must be excluded. */
        fun shouldApplyChargingFromBatteryEvent(replayedOffload: Boolean): Boolean = !replayedOffload

        /** The charge state a CHARGING_ON / CHARGING_OFF event states outright, or null for any other
         *  event. The strap raises these the moment a pack goes on or comes off, so they carry the live
         *  answer that BATTERY_LEVEL's own flag only refreshes on its slow cadence. Event strings are
         *  "NAME(rawValue)", so prefix-match. */
        fun chargingFromEvent(event: String): Boolean? = when {
            event.startsWith("CHARGING_ON") -> true
            event.startsWith("CHARGING_OFF") -> false
            else -> null
        }

        /** Is this EVENT string a PHYSICAL GESTURE (double-tap / wrist on/off)? Gestures take the
         *  freshness-gated branch; everything else (BLE_BONDED, BATTERY_LEVEL, STRAP_DRIVEN_ALARM_EXECUTED=57)
         *  takes the non-gesture branch. Event strings are "NAME(rawValue)", so prefix-match. */
        fun isGestureEvent(event: String): Boolean =
            event.startsWith("DOUBLE_TAP") ||
                event.startsWith("WRIST_ON") || event.startsWith("WRIST_OFF")

        /** Should this EVENT fire the smart-alarm re-arm? True ONLY for a LIVE STRAP_DRIVEN_ALARM_EXECUTED
         *  (event 57) — a HISTORICAL one replayed mid-backfill must not re-arm. Event 57 is not a
         *  gesture, so it must dispatch from the non-gesture branch or it never fires. */
        fun smartAlarmFiredForEvent(event: String, replayedOffload: Boolean): Boolean =
            event.startsWith("STRAP_DRIVEN_ALARM_EXECUTED") && !replayedOffload

        /** The LiveState the device-remove RELEASE publishes — link fully dropped and every stale live
         *  readout cleared, so a removed strap can't keep showing live HR, a bond, or a charging pill. */
        fun releasedLiveState(previous: LiveState): LiveState =
            previous.clearedBiometrics().copy(
                connected = false, bonded = false, encryptedBond = false,
                charging = null, strapFirmware = null, historyLayoutVersion = null,
                packSocPct = null, packSerial = null, packMillivolts = null,
                pairingHint = null, scanning = false,
                statusNote = null,
            )

        /** Classification of a COMPLETED (HISTORY_COMPLETE) offload: first = bankedSensorRecords (strap
         *  handed over real sensor records — decoded this pass OR rows persisted); second = bankedNothing
         *  (completed but banked none — console-only across >=3 diagnostic chunks, or a near-empty
         *  metadata-only completion with fewer than 3 console frames). EmptySyncTracker gates the banner. */
        fun classifyCompletedOffload(
            decodedChunks: Int,
            consoleChunks: Int,
            rowsPersisted: Int,
        ): Pair<Boolean, Boolean> {
            val bankedSensorRecords = decodedChunks > 0 || rowsPersisted > 0
            val bankedNothing = !bankedSensorRecords && (consoleChunks >= 3 || rowsPersisted == 0)
            return Pair(bankedSensorRecords, bankedNothing)
        }

        /** Newest plausible-unix marker in a GET_DATA_RANGE response = the strap's newest stored record.
         *  Scans u32 LE words in the response body (starts at frame[7], after [type,seq,cmd]), keeps
         *  those in the unix range, returns max. */
        // The scan (every-offset newest, aligned-from-7 oldest, future-skew preference) lives behind
        // RustCodec; this read gates auto-sync via isFutureDatedNewest → BackfillPolicy. Thin wrapper so
        // existing call sites stay unchanged.
        fun dataRangeNewestUnix(
            frame: ByteArray,
            wallNowUnix: Long = System.currentTimeMillis() / 1000L,
        ): Long? = com.noop.protocol.RustCodec.dataRangeNewest(frame, wallNowUnix, AUTO_CONTINUE_FUTURE_SKEW_SECONDS)

        /** OLDEST plausible record timestamp in a GET_DATA_RANGE frame — start of the strap's stored
         *  history. Aligned-from-7 grid (asymmetric with [dataRangeNewestUnix] by design), so one
         *  connect reports the full banked SPAN (oldest…newest) = backlog depth a deep drain must cover. */
        fun dataRangeOldestUnix(frame: ByteArray): Long? = com.noop.protocol.RustCodec.dataRangeOldest(frame)

        /** Pages the strap has banked but not yet sent, read off its ring cursors. Diagnostic only —
         *  it reports how far a sync has to go and never gates one. */
        fun dataRangePagesBehind(frame: ByteArray): Long? =
            com.noop.protocol.RustCodec.dataRangePagesBehind(frame)

        /** Auto-continue cap: consecutive immediate re-kicks per connection before falling back to the
         *  900s periodic timer. 6 x ~60s ~= 6 min of back-to-back draining without letting a misbehaving
         *  strap monopolise Bluetooth. */
        const val MAX_AUTO_CONTINUES = 6

        /** "More backlog remains" margin (seconds): how far ahead the strap must be of our persisted data
         *  frontier before it's treated as behind, not clock noise. Also used by StuckStrapDetector. */
        const val AUTO_CONTINUE_BEHIND_GAP_SECONDS = 300L

        /** How far past the WALL CLOCK the strap-reported "newest" may sit before it's implausible. A
         *  future-set strap clock makes [dataRangeNewestUnix] read ahead of every real frontier, so the
         *  "backlog remains" guard would report backlog forever. 48h absorbs timezone confusion + drift. */
        const val AUTO_CONTINUE_FUTURE_SKEW_SECONDS = 48L * 3600L

        /** Is the strap-reported "newest banked record" FUTURE-dated beyond the skew allowance — more
         *  than [futureSkewSeconds] past the wall clock? Then the strap's clock is set in the future, so
         *  its range answer and freshly-persisted rows are untrustworthy as backlog evidence. null =>
         *  false (an unanswered range is UNKNOWN, not future-dated; the stale-epoch rescue still applies). */
        fun isFutureDatedNewest(
            strapNewestTs: Long?,
            wallNowUnix: Long,
            futureSkewSeconds: Long = AUTO_CONTINUE_FUTURE_SKEW_SECONDS,
        ): Boolean = strapNewestTs != null && strapNewestTs > wallNowUnix + futureSkewSeconds

        /** Post-sync banner for a strap whose clock is set in the FUTURE. Unlike the "clock lost / not
         *  banking" case, this strap DOES bank records every pass, but its RTC relatched to a future
         *  base so every timestamp reads ahead of the wall clock and NOOP won't import them. Returns the
         *  banner text when the strap-reported newest is future-dated beyond the 48h skew, else null. */
        fun futureDatedStrapBanner(strapNewestTs: Long?, wallNowUnix: Long): String? =
            if (!isFutureDatedNewest(strapNewestTs, wallNowUnix)) null
            else "Synced, but your strap's clock is set in the future - its banked history is dated ahead of " +
                "today, so NOOP can't trust those timestamps and didn't import them (importing them would " +
                "misfile your data days or years ahead). Fully charge the strap to 100% and power-cycle it so " +
                "its clock re-syncs, then reconnect."

        /** Should a backfill session that ended on the 60s IDLE cap (not true HISTORY_COMPLETE)
         *  immediately re-kick instead of waiting the 900s periodic floor? The strap offloads OLDEST-first
         *  with no auto-continue, so a deep backlog can take many connections; this drains it in
         *  back-to-back passes. Requires: still connected+bonded; the strap's newest banked record ahead
         *  of our persisted frontier by more than [behindGapSeconds] (or, as a fallback, real rows
         *  persisted while the trim advanced); the trim cursor actually advanced last session; and under
         *  [maxAutoContinues]. A FUTURE-dated strap clock disables both the frontier check and the
         *  rows-persisted fallback, since it makes every banked timestamp untrustworthy as backlog evidence. */
        fun shouldAutoContinue(
            stillConnected: Boolean,
            strapNewestTs: Long?,
            ourFrontierTs: Long?,
            wallNowUnix: Long,
            lastTrimAdvanced: Boolean,
            consecutiveCount: Int,
            rowsPersistedThisSession: Int = 0,
            maxAutoContinues: Int = MAX_AUTO_CONTINUES,
            behindGapSeconds: Long = AUTO_CONTINUE_BEHIND_GAP_SECONDS,
            futureSkewSeconds: Long = AUTO_CONTINUE_FUTURE_SKEW_SECONDS,
        ): Boolean {
            if (!stillConnected) return false                          // 1
            if (consecutiveCount >= maxAutoContinues) return false      // 4 (cap)
            if (!lastTrimAdvanced) return false                        // 3 (don't spin on a frozen cursor)
            // A strap clock set in the FUTURE makes "newest" read ahead of any real frontier, so this
            // guard would report backlog forever and burn the whole cap in EMPTY offloads on every
            // connect. A newest more than [futureSkewSeconds] past [wallNowUnix] is implausible: exclude it here.
            val futureDated = isFutureDatedNewest(strapNewestTs, wallNowUnix, futureSkewSeconds)
            val newest = strapNewestTs?.takeIf { !futureDated }
            val frontier = ourFrontierTs
            // 2a: strap reports newer data than we hold — reliable WHEN its clock epoch is sane.
            if (newest != null && frontier != null && (newest - frontier) > behindGapSeconds) return true
            // A future-dated newest also gates the rows-persisted fallback below, not just the frontier
            // check above: a future-clock strap banks future-dated records, so rows persisted this
            // session are themselves future-timestamped and are not evidence of genuine backlog. Stop
            // after this single pass; the periodic floor keeps draining across connects.
            if (futureDated) return false
            // GET_DATA_RANGE's "newest" can read a STALE / wrong-epoch value — a strap that was fully
            // discharged (or carries a previous owner's history) can latch an old clock epoch, falsely
            // reading "already past it" and stopping the drain after one session. Since the trim cursor
            // did advance, real rows persisted this session are still evidence of genuine backlog.
            return rowsPersistedThisSession > 0
        }

    }

    // MARK: Published state — the single source of truth the UI observes. Seeded with the PERSISTED
    // last-sync time so a freshly-recreated client doesn't show "Never" when this install has already
    // synced; 0 (never) leaves it null, unchanged.
    private val _state = MutableStateFlow(
        LiveState(lastSyncAt = NoopPrefs.lastSyncAt(context).takeIf { it > 0L }),
    )
    val state: StateFlow<LiveState> = _state.asStateFlow()

    // MARK: Multi-WHOOP (additive — inert on the single-WHOOP path).

    /**
     * Pin connections to ONE specific strap by its [BluetoothDevice.address]. When non-null,
     * [onScanResult]'s connect path connects ONLY to the device whose address matches and ignores every
     * other discovered WHOOP; null (single-WHOOP default) connects to the FIRST WHOOP discovered.
     * Setting a genuinely NEW pin resets the bond-refusal streak (it belonged to the previous strap);
     * re-applying the SAME pin preserves an in-progress count.
     */
    @Volatile
    private var _preferredAddress: String? = null
    var preferredAddress: String?
        get() = _preferredAddress
        set(value) {
            if (!value.equals(_preferredAddress, ignoreCase = true)) pinnedBondRefusals = 0
            _preferredAddress = value
        }

    /** True when [dev] is the strap we're pinned to — or when no pin is set (single-WHOOP default, any
     *  WHOOP acceptable). The involuntary-reconnect fast paths consult this so they never re-attach to a
     *  non-pinned strap. */
    private fun isPreferred(dev: BluetoothDevice): Boolean {
        val p = preferredAddress ?: return true
        return dev.address.equals(p, ignoreCase = true)
    }

    /** A WHOOP strap surfaced by the Add-a-device wizard's present-scan ([scanForWhoops]) WITHOUT
     *  auto-connecting. [address] is the BLE MAC; [name] the advertised name (may be null); [rssi] the
     *  signal; [family] the WHOOP family read from the advertised service (tells 4.0 apart from 5/MG without a pre-pick), null when unresolved. */
    data class DiscoveredWhoop(val address: String, val name: String?, val rssi: Int, val family: WhoopModel? = null)

    private val _discoveredWhoops = MutableStateFlow<List<DiscoveredWhoop>>(emptyList())
    /** WHOOP straps seen while [scanningForList] is true (the Add-a-device wizard's present-scan), WITHOUT
     *  auto-connecting. Cleared at the start of each [scanForWhoops]. Empty/unused on the default path. */
    val discoveredWhoops: StateFlow<List<DiscoveredWhoop>> = _discoveredWhoops.asStateFlow()

    private val _connectedPeripheralAddress = MutableStateFlow<String?>(null)
    /** The BLE address of the strap currently connected, or null when disconnected. Drives
     *  SourceCoordinator's first-connect identity adoption. */
    val connectedPeripheralAddress: StateFlow<String?> = _connectedPeripheralAddress.asStateFlow()

    private val _connectedStrapSerial = MutableStateFlow<String?>(null)
    /** The connected strap's own serial (GATT 0x2A25), or null while unread/disconnected. Lands a few
     *  seconds after the address and drives SourceCoordinator's serial identity binding. */
    val connectedStrapSerial: StateFlow<String?> = _connectedStrapSerial.asStateFlow()

    private val _connectedStrapHardwareRev = MutableStateFlow<String?>(null)
    /** The connected strap's hardware revision (GATT 0x2A27), or null while unread/disconnected. Read
     *  on the same schedule as the serial and stored verbatim on the strap's registry row. */
    val connectedStrapHardwareRev: StateFlow<String?> = _connectedStrapHardwareRev.asStateFlow()

    /** Add-a-WHOOP wizard present-scan flag: while true, [onScanResult] ACCUMULATES every discovered
     *  strap into [discoveredWhoops] instead of auto-connecting. Set by [scanForWhoops], cleared by
     *  [stopWhoopScan]. @Volatile: written on the main looper, read on the GATT/scan callback thread. */
    @Volatile
    private var scanningForList = false

    /** One raw pack reply is logged per link, so the undecoded tail can be read off a strap log. */
    private var packReplyLogged = false

    /**
     * Multi-source seam: publish a live HR/R-R reading from a NON-WHOOP source into the SAME [state]
     * flow the UI observes, so a generic HR strap's live HR shows in the existing Live UI. Invoked ONLY
     * while WHOOP's own BLE is paused (a non-WHOOP strap is active), so it never races the WHOOP path.
     * HR is range-gated like [parseStandardHr]; R-R rides [LiveState.withRRIntervals]. Persistence stays
     * with the source's own `persist` closure.
     */
    fun publishExternalLiveHr(hr: Int, rr: List<Int>) {
        if (rr.isNotEmpty()) _state.update { it.withRRIntervals(rr) }
        if (hr in 30..220) {
            // A non-WHOOP source (Oura ring, FTMS machine, generic HR strap) is actively streaming live
            // HR. Set streamingLiveHR so the Live console reads it as trusted instead of "connecting /
            // not trusted". `bonded` stays false, so buzz/alarm/HRV gates keep keying off the WHOOP bond.
            _state.update { it.copy(heartRate = hr, connected = true, streamingLiveHR = true) }
        }
    }

    /**
     * Surface a non-WHOOP source's battery percent ([pct], 0-100) in the SAME live [state] the UI
     * reads. Twin of [publishExternalLiveHr]; called ONLY while WHOOP's own BLE is paused, so it never
     * races the WHOOP battery path. Out-of-range values are ignored.
     */
    fun publishExternalBattery(pct: Int) {
        if (pct in 0..100) _state.update { it.copy(batteryPct = pct.toDouble()) }
    }

    // MARK: Android Bluetooth handles.
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    /** Injectable indirection over [gatt]'s raw GATT calls (see [GattOps]). Rebuilt whenever [gatt] is
     *  (re)assigned in [connectToDevice], cleared in the teardown path alongside `gatt = null`. */
    private var gattOps: GattOps? = null

    /** Battery-% at/below which the periodic offload cadence stretches to
     *  [LOW_BATTERY_BACKFILL_INTERVAL_MS] while discharging; 0 = never (normal 15-min cadence). DEFAULT
     *  OFF (dormant); set by [setLowBatteryOffloadThrottle]. */
    @Volatile private var lowBatteryOffloadPct: Int = 0

    /** Opt into the low-battery offload-cadence stretch. Applies on the NEXT re-arm; a live sync
     *  in flight is never interrupted. */
    fun setLowBatteryOffloadThrottle(thresholdPct: Int) {
        lowBatteryOffloadPct = thresholdPct
    }

    /** The delay before the next periodic offload — normally [BACKFILL_INTERVAL_MS], stretched when low
     *  on battery. Reads the battery snapshot at re-arm time. */
    private fun nextBackfillDelayMs(): Long {
        if (lowBatteryOffloadPct <= 0) return BACKFILL_INTERVAL_MS   // dormant: no battery read, unchanged cadence
        val (batteryPct, charging) = batteryPctAndCharging()
        return offloadIntervalMsFor(
            baseMs = BACKFILL_INTERVAL_MS,
            lowBatteryMs = LOW_BATTERY_BACKFILL_INTERVAL_MS,
            batteryPct = batteryPct,
            charging = charging,
            thresholdPct = lowBatteryOffloadPct,
            powerSave = powerSaveActive(),
        )
    }

    /** True when the OS Battery Saver is on — the user's explicit "save power" signal. An extra
     *  trigger for an already-armed lever; has its own hysteresis + charging-awareness. */
    private fun powerSaveActive(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    /** Current (battery-%, isCharging) from the sticky ACTION_BATTERY_CHANGED intent — a cheap synchronous
     *  read, no persistent receiver. Unknown → (100, false) so the throttle fails SAFE (never engages). */
    private fun batteryPctAndCharging(): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    /** @Volatile: set on the GATT binder thread at service discovery, but read in send() on the main
     *  thread (user actions) - the barrier makes a main-thread send see the current characteristic. */
    @Volatile private var cmdCharacteristic: BluetoothGattCharacteristic? = null

    /** Frame reassembler for the fragmented custom notify chars. Reassigned per connection with the
     *  detected family — WHOOP5/MG frames use a different length encoding. */
    private var reassembler = Reassembler()

    /** Rolling command sequence byte; incremented before each send. ATOMIC because `send()` runs on
     *  BOTH the GATT binder thread (handshake, offload acks, bond frame) and the main thread (user
     *  actions) — a non-atomic `seq++` would let two sends emit the SAME byte. Wire value:
     *  `incrementAndGet() and 0xFF` (0..255, wraps). */
    private val seq = AtomicInteger(0)

    /** True once the confirmed-write bond ACK lands. @Volatile: written in a GATT callback (binder
     *  thread on API 26/27) and read in the onBondWatchdog / keepAlive main-looper timers, so it needs
     *  cross-thread visibility. */
    @Volatile
    private var didBond = false

    /** Runs the connect handshake EXACTLY ONCE per connection. @Volatile: written in a GATT callback
     *  (binder thread), read in beginBackfill (main-looper timer). */
    @Volatile
    private var connectHandshakeDone = false

    /** True when the user asked to disconnect; suppresses the auto-rescan. Written on the main looper
     *  (connect/disconnect/keep-alive bounce) and read on the GATT binder thread (handleDisconnect), so
     *  it must be @Volatile for cross-thread visibility. */
    @Volatile
    private var intentionalDisconnect = false
    /// The strap family the user chose to pair, remembered so an auto-reconnect after a
    /// dropout re-scans for the same model instead of falling back to WHOOP 4.0.
    private var selectedModel = WhoopModel.WHOOP4
    /// The last device connected to, kept so an auto-reconnect after a dropout can connect DIRECTLY to
    /// it (autoConnect=true) instead of scanning — a bonded strap the OS still holds (or that isn't
    /// advertising) won't appear in a scan.
    private var lastDevice: BluetoothDevice? = null

    /** Address of the strap we last connected to — for persisting it and auto-reconnecting on launch. */
    val lastDeviceAddress: String? get() = lastDevice?.address
    /// The family discovered on the connected peripheral. Drives family-aware frame parsing and gates
    /// the WHOOP4-only bond/handshake. Set in onServicesDiscovered. @Volatile: written on the binder
    /// thread at discovery, read in send() on main — a stale read would frame a command for the wrong generation.
    @Volatile private var connectedFamily = DeviceFamily.WHOOP4

    /** True while a scan is active, so we never start a second scan (Android scanner is stateful). */
    private var scanning = false

    /** All BLE work hops onto the main looper. */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Mirror the strap log to logcat (`Log.d`). Default OFF — the in-app ring buffer always records
     * regardless, so "Share strap log" works either way; this gate only controls the adb-visible
     * `Log.d` developers use (`adb logcat -s WhoopBleClient`). Driven by Settings → Strap → "Debug
     * logging", pushed down so this client never depends on the UI/prefs layer. @Volatile: [log] runs
     * on both the GATT binder thread and the main looper.
     */
    @Volatile
    var debugLogcat: Boolean = false

    /** Invoked (live only) when the strap reports it fired its firmware smart alarm
     *  (STRAP_DRIVEN_ALARM_EXECUTED, event 57) — a single absolute instant with NO recurrence, so on
     *  receipt the ViewModel re-arms the next day's instant. Fired from the NON-gesture EVENT branch:
     *  event 57 is not a gesture, so routing it through the gesture path would swallow it entirely. */
    var onSmartAlarmFired: (() -> Unit)? = null

    /** In-memory ring buffer of the strap log, exportable from the UI for bug reports. `log()` always
     *  writes here (under [logBuffer]'s monitor); logcat mirroring is opt-in via [debugLogcat] since
     *  Android's `Log.d` isn't reachable by a normal user. */
    private val logBuffer = ArrayDeque<String>()
    // PII scrubbers for the shareable strap log live at file scope as [redactStrapLogPii], unit-testable
    // without constructing this client.

    /** Fired if a scan finds nothing in [SCAN_TIMEOUT_MS]; stops scanning and explains why. */
    private val scanTimeoutRunnable = Runnable {
        if (scanning && !_state.value.connected) {
            stopScan()
            log("No WHOOP strap found within ${SCAN_TIMEOUT_MS / 1000}s")
            _state.update { it.copy(
                scanning = false,
                statusNote = "No strap found. Check it's charged and on your wrist, and that the " +
                    "official WHOOP app isn't connected to it (a strap will only pair with one app " +
                    "at a time). Then tap Connect again.",
            ) }
        }
    }

    /** Fired after [SCAN_FALLBACK_DELAY_MS] of a service-filtered scan with no discovery: rotate to the
     *  other WHOOP family in case the persisted preference is stale (after an update/restore). Cancelled
     *  on discovery/connect. */
    private val scanFallbackRunnable = Runnable {
        if (scanning && !_state.value.connected) {
            val fallback = selectedModel.fallbackScanModel
            log("No ${selectedModel.displayName} found yet — trying ${fallback.displayName}")
            stopScan()   // clears the scanning flag + the LE scan; startScan re-arms both
            startScan(fallback, allowFallback = true)
        }
    }

    // ====================================================================================
    // MARK: Persistence + historical offload
    // ====================================================================================

    /**
     * Background scope for all DB writes (insert is a suspend Room call). SupervisorJob so one
     * failed insert never cancels the others; IO dispatcher keeps DB work off the main looper.
     * Cancelled in [shutdown].
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Durable archive for undecodable history record frames. Written BEFORE the strap is
     * acked, so an unrecognised firmware layout can't cost the user their only copy: the ack frees
     * the strap's records, and this archive is the only remaining copy until the layout is mapped.
     */
    private val rawHistoryArchive = RawHistoryArchive(context)

    init {
        // Retro-decode: when the decoder gains a historical layout, re-run every archived undecodable
        // frame through it and insert whatever now decodes — the only path by which already-acked,
        // strap-freed history backfills after an update. Runs once per app version, idempotent (rows
        // dedupe by ts); a failed insert holds the gate so records retry next launch.
        ioScope.launch {
            val rows = rawHistoryArchive.replayIfNeeded(
                repository, deviceId, com.noop.ui.AppChangelog.CURRENT_VERSION,
            )
            if (rows > 0) {
                log("Backfill: retro-decoded $rows record(s) from the reject archive after an update.")
            }
        }
    }

    /** Per-connection GET_CLOCK state shared with historical decode. */
    private val clockReference = ClockReference()

    /** The offload state machine. Ack callback writes HISTORICAL_DATA_RESULT (with response). */
    private val backfiller = Backfiller(
        repository = repository,
        deviceId = deviceId,
        cursorStore = cursorStore,
        clockReference = clockReference,
        ackTrim = { trim, endData -> ackHistoricalChunk(trim, endData) },
        onChunkCommitted = { onBackfillChunkCommitted() },
        onConsoleChunk = { consoleChunksThisSession += 1 },
        // Same switch as the raw-frame capture, one level further in: this writes the DECODED record,
        // every field the codec produced rather than the columns the schema stores. Off by default.
        recordSink = { fields, frame ->
            if (puffinExperiment.isCaptureEnabled) {
                com.noop.ingest.HistoryRecordSink.append(context.filesDir, deviceId, fields, frame)
            }
        },
        // Archive undecodable frames before the ack. append() returns ok=true (written, or archive-full
        // -> still safe to ack) and THROWS only on a genuine write failure -> return false so finishChunk
        // holds the cursor/ack and the strap re-sends.
        rejectedSink = { frames, trim ->
            try {
                val r = rawHistoryArchive.append(frames, trim, connectedFamily)
                if (r.written) log("Backfill: ${frames.size} undecodable frame(s) archived before ack")
                else log("Backfill: ${frames.size} undecodable frame(s) NOT archived (archive full) — acking anyway")
                r.ok
            } catch (t: Throwable) {
                log("Backfill: reject-archive write FAILED (${t.message}) — holding ack so the strap re-sends")
                false
            }
        },
        log = { s -> log(s) },
        // Connection & Sync test mode (Test Centre): the cheap gate + tagged sink the Backfiller checks
        // before building any .connection diagnostic line. The gate is one SharedPreferences bool;
        // nothing is emitted (or built) when the mode is off.
        connectionActive = { testCentre.active(com.noop.testcentre.TestDomain.CONNECTION) },
        connectionLog = { s -> log(s, com.noop.testcentre.TestDomain.CONNECTION) },
        // Test Centre → Experimental algorithms: the opt-in v26 PPG-HR sub-lag interpolation variant, read
        // live each chunk so a mid-session toggle takes effect. Default OFF (byte-identical to today).
        ppgHrSubLagInterp = { puffinExperiment.ppgHrSubLagInterp },
        firmwareLayout = { v -> _state.update { it.copy(historyLayoutVersion = v) } },
    )

    /**
     * Fresh history just landed durably (a backfill chunk committed + acked) — schedule one debounced
     * on-device scoring pass so recovery/strain/sleep appear right away instead of waiting for the UI's
     * 15-min analysis tick, which doesn't run with the app UI closed and only the foreground service alive.
     */
    private fun onBackfillChunkCommitted() {
        decodedChunksThisSession += 1   // invoked once per non-empty decoded chunk
        if (!analyzeAfterBackfillScheduled.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                delay(POST_BACKFILL_ANALYZE_DELAY_MS) // let trailing chunks of the same session land
                val profileStore = ProfileStore.from(context)
                val profile = UserProfile(
                    weightKg = profileStore.weightKg,
                    heightCm = profileStore.heightCm,
                    age = profileStore.ageYears,
                    sex = profileStore.sex,
                    stepTicksPerStep = profileStore.stepTicksPerStep,
                )
                runCatching {
                    IntelligenceEngine.analyzeRecent(
                        repo = repository,
                        profile = profile,
                        importedDeviceId = deviceId,
                        maxHROverride = profileStore.hrMaxOverride.takeIf { it > 0 }?.toDouble(),
                        // Steps-estimate calibration: honor the user's manual override and persist the fit
                        // after a backfill too, so the Settings/Steps screen reflects the latest data.
                        manualStepCoefficient = profileStore.stepsManualOverride,
                        persistStepsCalibration = { cal ->
                            profileStore.stepsCalibrationCoefficient = cal.coefficient
                            profileStore.stepsCalibrationSampleDays = cal.sampleDays
                            profileStore.stepsCalibrationConfidence = cal.confidence
                            profileStore.stepsCalibrationManual = cal.manual
                        },
                        // Manual "Recalibrate baseline" anchor (noop.hrvBaselineEpoch, whole seconds). The
                        // analytics layer is Context-free, so read it here and thread it down so the
                        // post-backfill pass honours the recalibration too, not just the UI's 15-min loop.
                        // 0 = no recalibration.
                        baselineEpoch = repository.effectiveBaselineEpoch(
                            NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble(),
                        ),
                        recoveryEpoch = repository.effectiveBaselineEpoch(
                            NoopPrefs.of(context).getLong(Baselines.recoveryBaselineEpochKey, 0L).toDouble(),
                        ),
                        // Nightly HRV over deep-sleep windows only when the user picked WHOOP-style. Read
                        // here (the analytics layer is Context-free) and thread it down, like baselineEpoch
                        // above — otherwise this pass would recompute over the WHOLE night, overwriting the
                        // deep-window value the UI loop wrote.
                        // Route the engine's per-day diagnostics (incl. the RHR floor-vs-mean line) into
                        // THIS sync's strap log, so a report comparing NOOP's resting-HR floor against
                        // another app's night mean carries proof from the post-backfill pass too, not only
                        // the UI's 15-min loop. log() PII-scrubs.
                        diag = { s -> log(s) },
                        // Sleep & Rest test mode: when the SLEEP domain is on, route this pass's per-day
                        // sleep gate trace into the .sleep-tagged strap log too, not only the UI's 15-min
                        // loop. Zero-cost when off (the sink stays null -> analyzeDay's byte-identical
                        // untraced path). log() PII-scrubs.
                        sleepTraceSink =
                            if (testCentre.active(com.noop.testcentre.TestDomain.SLEEP))
                                { s -> log(s, com.noop.testcentre.TestDomain.SLEEP) }
                            else null,
                        // Recovery (Charge) test mode: when the RECOVERY domain is on, route this pass's
                        // per-night Charge term-breakdown into the .recovery-tagged strap log too. Zero-cost
                        // when off (sink stays null, so the Charge score path is byte-identical). log() PII-scrubs.
                        recoveryTraceSink =
                            if (testCentre.active(com.noop.testcentre.TestDomain.RECOVERY))
                                { s -> log(s, com.noop.testcentre.TestDomain.RECOVERY) }
                            else null,
                        // Steps test mode: when the STEPS domain is on, route this pass's per-day 5/MG
                        // raw-counter + WHOOP-4 calibration trace into the .steps-tagged strap log too.
                        // Zero-cost when off (sink stays null, so the steps total path is byte-identical). log() PII-scrubs.
                        stepsTraceSink =
                            if (testCentre.active(com.noop.testcentre.TestDomain.STEPS))
                                { s -> log(s, com.noop.testcentre.TestDomain.STEPS) }
                            else null,
                    )
                }.onSuccess {
                    log("Backfill: post-sync scoring pass done")
                    // Surface the day-key the dashboard treats as "today" against the newest banked row,
                    // so a UTC-bucket vs local-day split (rows persist but Today freezes) shows up in the
                    // shared strap log. Best-effort — a diagnostic read must never break scoring.
                    runCatching {
                        val merged = repository.daysMerged()
                        val newest = merged.maxByOrNull { it.day }?.day ?: "—"
                        val todayKey = com.noop.ui.logicalDayKeyNow()
                        val present = if (merged.any { it.day == todayKey }) "present" else "MISSING"
                        log("Backfill: ${merged.size} day(s) banked; newest=$newest, dashboard-today=$todayKey ($present)")
                    }
                }.onFailure {
                    // The scoring pass hops to Dispatchers.Default; shutdown() cancels it, which is not a
                    // scoring failure — rethrow so the cancellation isn't swallowed/mis-logged.
                    if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                    log("Backfill: post-sync scoring failed: ${it.message}")
                }
                // Keep the opt-in Health Connect writeback fresh in background-only operation too.
                if (NoopPrefs.hcWriteback(context)) {
                    runCatching { HealthConnectWriter.write(context, repository, deviceId) }
                        .onSuccess { log("HC writeback: $it record(s)") }
                }
            } finally {
                analyzeAfterBackfillScheduled.set(false)
            }
        }
    }

    /** True while a historical offload is in progress (offload frames route to the Backfiller). */
    @Volatile
    private var backfilling = false
    /** Chunks acked this offload session — feeds LiveState.syncChunksThisSession (throttled). Only
     *  touched on the serial backfill drain coroutine + the begin/exit lifecycle. */
    private var ackedChunksThisSession = 0
    /** Per-session chunk tallies to tell an EMPTY completed sync (strap handed over only
     *  console/diagnostic output, not banking to flash) from a clean one. Reset at session start. */
    private var decodedChunksThisSession = 0
    private var consoleChunksThisSession = 0
    /** False-alarm guard: CONSECUTIVE console-only completed syncs, so the "clock has lost sync"
     *  banner only fires on sustained emptiness, not a single transient empty cycle on a healthy strap. */
    private val emptySyncTracker = EmptySyncTracker()
    /** Bond-loop detector: tracks consecutive bond-then-quick-timeout cycles on a WHOOP 4. When it
     *  trips, the client surfaces the existing re-pair guide ([LiveState.reconnectGuide]) instead of
     *  looping silently. Reset on a user-initiated disconnect; otherwise broken by any healthy disconnect. */
    private val postBondLoop = PostBondTimeoutLoopDetector()
    /** Bond-handshake watchdog pacer: escalates the [BOND_WATCHDOG_MS] window per consecutive bounce (a
     *  slow-but-healthy WHOOP 4.0 bond gets more time), then hands off to the re-pair guide + reconnect
     *  pause. Distinct from [postBondLoop], which fires when a GENUINE bond drops shortly after landing. */
    private val bondWatchdogBackoff = BondWatchdogBackoff(baseWindowMs = BOND_WATCHDOG_MS)
    /** Monotonic per-connection token, bumped on every connect. The bond-loop stabilization check
     *  captures it and clears the re-pair guide only if it is UNCHANGED when the check fires — i.e. the
     *  SAME continuous connection survived (a reconnect/loop cycle bumps it). */
    @Volatile private var connectGeneration = 0
    /** Wall time the encrypted bond was established this connection, to measure how soon a drop
     *  follows the bond (the bond-loop tell). null until bonded; cleared on disconnect after the detector reads it. */
    private var bondedAtMs: Long? = null
    // Connection & Sync test mode (Test Centre) — diagnostic-only counters. They change NO connect /
    // bond / offload behaviour; every emit site that reads them is gated behind
    // testCentre.active(CONNECTION) BEFORE any string is built.
    /** Wall time (ms) the current connect attempt began, to measure connect latency at onConnectionStateChange
     *  CONNECTED. null between attempts; set when connect() kicks the radio. */
    private var connectAttemptStartedAtMs: Long? = null
    /** Count of INVOLUNTARY reconnects this run, surfaced as the reconnect-churn count. Reset by an
     *  intentional disconnect. */
    private var connReconnectCount = 0
    /** The last live frame TYPE name seen while the Connection test mode is ON, so it emits one frame-timing
     *  line per genuine type transition (never per frame — the raw flood repeats one type). Test-only:
     *  read and written exclusively inside the mode gate, so the live hot path is untouched when off. */
    private var connLastFrameType: String? = null
    /** Tracks CONSECUTIVE empty 5/MG offloads so a 5/MG whose firmware serves no history (but streams
     *  live HR fine) reads as "history sync experimental on 5.0" instead of a sync error, and the 120s
     *  bounce loop backs off while live HR is flowing. Reset on connect or a banking offload. */
    private val whoop5EmptyOffload = Whoop5EmptyOffloadTracker()
    /** Genuine offload frames seen this session — zero at timeout means the strap never answered
     *  the history request at all. Main-looper only. */
    private var offloadFramesThisSession = 0
    /** Deep-packet cooldown: wall time of the most recent offload frame OR HISTORY_COMPLETE (0 = none
     *  yet this session). A type-0x2F arriving just after a backfill ends (backfilling already flipped
     *  false) is a TRAILING historical frame, not the live R22 stream, so it must not count as "live". */
    private var lastOffloadFrameAtMs = 0L
    /** One-shot per session: SEND_HISTORICAL_DATA already fired (gate + fail-open can both call). */
    private var historicalKickSent = false
    /** 5/MG zero-frame retries used this CONNECTION (max 2 — then the 900s periodic timer owns it). */
    private var whoop5HistoryAttempts = 0
    /** One-shot debounce: a post-backfill scoring pass is already scheduled/running. */
    private val analyzeAfterBackfillScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Guards the once-per-connect initial offload kick. */
    private var backfillStarted = false

    /** Auto-continue: consecutive immediate re-kicks after a 60s idle-cap OR HISTORY_COMPLETE exit on
     *  THIS connection. Bounded by [MAX_AUTO_CONTINUES] so a pathological strap can't pin the radio.
     *  Reset once [shouldAutoContinue] proves we're caught up, and on disconnect — not on every
     *  HISTORY_COMPLETE, so a multi-completion offload can't reset the cap each slice. Main-looper only. */
    private var consecutiveAutoContinues = 0

    /** Spin-detector: the trim cursor as of the END of the PREVIOUS backfill session this connection.
     *  [exitBackfilling] compares Backfiller.lastAckedTrim against this to decide whether the just-ended
     *  session advanced the strap's trim (progress) or froze (stop re-kicking). null until the first
     *  session ends; reset on disconnect. */
    private var lastSessionEndTrim: Long? = null

    /** Newest unix the strap reports having (from GET_DATA_RANGE); refreshed each connect. */
    @Volatile
    private var strapNewestTs: Long? = null

    // --- Live-persistence buffer (custom realtime/event/battery frames) ---

    /**
     * Live-persistence buffers, guarded by [collectorLock] (a plain monitor, NOT a coroutine Mutex,
     * because frames are appended synchronously from the single-threaded GATT callback thread and
     * only the suspend DB insert hops to [ioScope]). [batchStartedAtMs] tracks the flush interval.
     */
    private val collectorLock = Any()

    /** Buffered complete custom-channel frames awaiting a batched decode+insert. */
    // Buffer the (raw frame, pre-parsed) pair. Raw bytes stay for the raw path; the parse is the one
    // the dispatcher already did, so flushLive doesn't re-decode the batch.
    private val liveBuffer = ArrayList<Pair<ByteArray, com.noop.protocol.ParsedFrame>>()
    private var batchStartedAtMs = System.currentTimeMillis()

    /** Standard 0x2A37 HR/RR buffer — the reliable, always-on stream. */
    private val stdHr = ArrayList<HrRow>()
    private val stdRr = ArrayList<RrRow>()

    // --- Offload frame drain (preserves START/data/END arrival order for routeBackfillFrame) ---

    /** Ordered queue of offload frames awaiting the serial Backfiller drain. */
    private val backfillFrameQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var backfillDraining = false

    /** Periodic re-offload + idle-watchdog tokens (handler-posted; cancelled on disconnect). */
    private val periodicBackfillRunnable = Runnable { triggerPeriodicBackfill() }

    /** Wall-clock of the last historical-offload KICK (a [beginBackfill] that actually started), or null
     *  before the first. Feeds [BackfillPolicy.shouldRun] so periodic/strap floors can space kicks. */
    @Volatile private var lastBackfillAtMs: Long? = null

    private val backfillTimeoutRunnable = Runnable { onBackfillTimeout() }

    /** Live-stream keep-alive: re-arms realtime, polls battery, and bounces a stalled link.
     *  Handler-posted on every connect handshake; cancelled in reset(). */
    private val keepAliveRunnable = Runnable { keepAliveFire() }
    private var keepAliveTick = 0
    /** True while a Live/Health screen is on-screen and wants the realtime HR stream (ref-counted in
     *  [com.noop.ui.AppViewModel]). One of the two inputs to [wantsRealtime]. */
    @Volatile private var screenWantsRealtime = false
    /** Derived want: the realtime stream should be armed while a screen wants it. The keep-alive
     *  re-arms it so it can't lapse, and the post-bond branch arms it on connect. */
    @Volatile private var wantsRealtime = false
    /** What we last told the strap (armed = TOGGLE_REALTIME_HR 1). Lets [reconcileRealtime] send the
     *  toggle only on the false↔true edge instead of on every input change. */
    @Volatile private var realtimeArmed = false
    /** Wall-clock of the last inbound notification — drives the keep-alive liveness watchdog. */
    @Volatile private var lastDataAtMs = 0L
    /** True once we've re-subscribed during the CURRENT quiet episode, so the keep-alive re-subscribes
     *  at most once between data arrivals instead of flooding descriptor writes every 30s tick.
     *  Reset to false in [onInbound] when fresh data lands. */
    @Volatile private var resubscribedSinceData = false

    /**
     * Pending outbound writes. Android's GATT stack allows ONE in-flight write at a time:
     * a second writeCharacteristic before onCharacteristicWrite silently fails, so we serialise
     * writes ourselves. Each queued item is the fully-framed byte array + its write type
     * (with/without response).
     */
    private data class PendingWrite(val frame: ByteArray, val withResponse: Boolean, val cmd: CommandNumber? = null)
    private val writeQueue = ConcurrentLinkedQueue<PendingWrite>()
    // @Volatile: read on the main looper in drainWriteQueue but CLEARED from the GATT binder thread in the
    // write-completion callbacks - the barrier guarantees the main-thread drain sees the flag flip promptly
    // (else a queued write could stall until the next drain trigger).
    @Volatile private var writeInFlight = false
    /** A frame being retried after a transient BUSY rejection. Held here rather than re-added to the
     *  queue so it keeps its place AHEAD of later commands — command order matters (e.g. SET_CLOCK
     *  before GET_CLOCK). Only ever touched on the main looper inside [drainWriteQueue]. */
    private var pendingRetry: PendingWrite? = null
    private var writeRetries = 0

    /** The BUSY-retry kick for [drainWriteQueue], held as a NAMED runnable (not an inline lambda) so the
     *  teardown path can cancel a still-pending retry — otherwise a queued retry fires after the link is
     *  dead and re-enters the now-dead write, re-throwing `DeadObjectException`. */
    private val drainWriteRetryRunnable = Runnable { drainWriteQueue() }

    /** Descriptor-write queue: enabling notifications is also a one-at-a-time GATT operation. */
    private val cccdQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    // @Volatile: the CCCD-write twin of [writeInFlight] - read on the main looper in drainCccdQueue but
    // CLEARED from the GATT binder thread in onDescriptorWrite, so the barrier stops a subscription write
    // from stalling on a stale flag (which would leave a notify channel un-enabled → no live data).
    @Volatile private var cccdInFlight = false
    /** Bounded retries for a transiently-BUSY CCCD write, so a single rejected subscribe doesn't
     *  permanently kill a stream (HR/battery/events). Reset per connection in [reset]. @Volatile: reset
     *  on the binder thread in onDescriptorWrite but read/incremented on main in drainCccdQueue. */
    @Volatile private var cccdRetries = 0
    /** The BUSY-retry kick for [drainCccdQueue], a NAMED runnable so teardown can cancel a pending
     *  subscribe-retry that would otherwise re-enter a dead descriptor write. It re-drains using
     *  the CURRENT [gatt]; if the link is already torn down ([gatt] is null) the drain is a no-op. */
    private val drainCccdRetryRunnable = Runnable { gatt?.let { drainCccdQueue(it) } }
    /** Set once startSession() has fired the first command, so it runs exactly once per connection. */
    private var sessionStarted = false

    // ====================================================================================
    // MARK: Public API — connect / disconnect / send + buzz helper
    // ====================================================================================

    /**
     * Begin scanning for the WHOOP custom service, then connect to the first match.
     */
    @SuppressLint("MissingPermission")
    fun connect(model: WhoopModel = WhoopModel.WHOOP4) {
        intentionalDisconnect = false
        // Connection test mode: stamp when this connect attempt began so onConnectionStateChange can report
        // the connect latency. A plain timestamp, no behaviour change; only read behind the CONNECTION gate.
        connectAttemptStartedAtMs = System.currentTimeMillis()
        // An explicit user-driven Connect is never an out-of-range retry — clear the involuntary
        // reconnect streak so this scan (and any reconnects it spawns) starts back at the snappy
        // LOW_LATENCY scan mode + the 3s backoff base, never inheriting a backed-off lower-power scan.
        resetReconnectBackoff()
        // An explicit user Connect supersedes any pending involuntary reconnect timer.
        cancelPendingReconnect()
        selectedModel = model
        val adp = adapter
        // No Bluetooth LE hardware at all (most often an emulator / virtual device).
        if (adp == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            log("No Bluetooth LE on this device")
            _state.update { it.copy(
                scanning = false,
                statusNote = "This device has no Bluetooth LE. NOOP has to run on a real phone with " +
                    "Bluetooth, near your strap. It can't connect from an emulator or virtual device.") }
            return
        }
        if (!adp.isEnabled) {
            log("Bluetooth is off")
            _state.update { it.copy(
                scanning = false, statusNote = "Bluetooth is off. Turn it on, then tap Connect.") }
            return
        }
        val sc = scanner
        if (sc == null) {
            log("No BLE scanner available")
            _state.update { it.copy(statusNote = "Bluetooth isn't ready yet. Try again in a moment.") }
            return
        }
        if (scanning) {
            log("Scan already in progress — ignoring")
            return
        }
        // Reach a WHOOP 5/MG without a scan. Prefer a band the OS already holds connected
        // (getConnectedDevices returns it even after it stops advertising), then a bonded band that is not
        // currently connected (a direct connect avoids the status=133 first-operation failure seen on
        // scan-based 5/MG reconnects). Both helpers match only a 5/MG strap, so a WHOOP 4 falls through to
        // the scan below. The family is resolved from the discovered services rather than the selected
        // model, so this runs for any selected model and pins selectedModel to 5/MG on a hit to keep the
        // pipeline consistent. A stale connection or bond falls back to a scan via handleDisconnect.
        val direct = getConnectedWhoopDevice() ?: bondedWhoopDevice()
        if (direct != null) {
            selectedModel = WhoopModel.WHOOP5_MG
            log("Easy-connect: attaching directly to ${direct.name ?: "WHOOP"} (no scan needed)")
            _state.update { it.copy(
                scanning = false, whoop5Detected = false,
                statusNote = "Connecting to your ${WhoopModel.WHOOP5_MG.displayName}…",
            ) }
            connectToDevice(direct)
            bondedDirectAttempt = true   // after connectToDevice: reset() must not clear it
            return
        }
        startScan(model, allowFallback = true)
    }

    /**
     * Start a service-filtered scan for [model]. When [allowFallback] is true, schedule a one-shot
     * rotation to the other WHOOP family after [SCAN_FALLBACK_DELAY_MS] of no discovery, recovering a
     * stale family preference. Discovery/connect cancels both the fallback and the not-found timeout.
     */
    @SuppressLint("MissingPermission")
    private fun startScan(model: WhoopModel, allowFallback: Boolean) {
        handler.removeCallbacks(scanFallbackRunnable)
        // Defensive: the normal auto-connect scan is NEVER a present-scan. Clearing the flag here means a
        // leaked wizard present-scan (e.g. the wizard was dismissed without stopWhoopScan) can't divert
        // this connect's onScanResult into accumulate-not-connect. No-op on the (default) single-WHOOP path.
        scanningForList = false
        selectedModel = model
        val sc = scanner ?: run {
            log("No BLE scanner available")
            _state.update { it.copy(scanning = false, statusNote = "Bluetooth isn't ready yet. Try again in a moment.") }
            return
        }
        // Filter to the strap we're targeting — a single service, so a WHOOP 4.0
        // scan never lingers on a WHOOP 5/MG wrist (or the reverse).
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(model.service)).build(),
        )
        // LOW_LATENCY for a snappy first connect; a SUSTAINED involuntary-reconnect streak (past the
        // threshold) drops to lower-power BALANCED so an out-of-range strap stops pinning the radio. A
        // user Connect resets the streak, so a manual reconnect always scans at LOW_LATENCY. Duplicates
        // are never allowed.
        val scanMode = scanModeForReconnectAttempts(failedReconnectAttempts)
        if (scanMode != ScanSettings.SCAN_MODE_LOW_LATENCY) {
            log("Scan: backing off to lower-power mode after $failedReconnectAttempts involuntary reconnects")
        }
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()
        log("Scanning for ${model.displayName}…")
        scanning = true
        _state.update { it.copy(scanning = true, whoop5Detected = false, statusNote = "Searching for your ${model.displayName}…") }
        try {
            sc.startScan(filters, settings, scanCallback)
        } catch (se: SecurityException) {
            // Android 12+: BLUETOOTH_SCAN/CONNECT not granted. This is the #1 reason connect fails.
            scanning = false
            log("Scan blocked (permission): ${se.message}")
            _state.update { it.copy(
                scanning = false,
                statusNote = "NOOP needs the Nearby devices / Bluetooth permission. Allow it in " +
                    "Settings → Apps → NOOP → Permissions, then tap Connect.") }
            return
        } catch (t: Throwable) {
            scanning = false
            log("Scan failed to start: ${t.message}")
            _state.update { it.copy(scanning = false, statusNote = "Couldn't start scanning: ${t.message}") }
            return
        }
        // Stop and explain if nothing turns up in time.
        handler.removeCallbacks(scanTimeoutRunnable)
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        // Before the hard timeout, try the other family once in case the family preference is stale.
        if (allowFallback) {
            handler.postDelayed(scanFallbackRunnable, SCAN_FALLBACK_DELAY_MS)
        }
    }

    /**
     * Intentionally tear down the link and stop scanning.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        intentionalDisconnect = true
        handler.removeCallbacks(scanTimeoutRunnable)
        // A user teardown supersedes any pending involuntary reconnect timer.
        cancelPendingReconnect()
        stopScan()
        // A user-initiated teardown is a clean slate: clear the bond-loop streak so the next (manual)
        // reconnect starts fresh rather than inheriting old suspicion.
        postBondLoop.reset()
        // A clean teardown clears the bond-refusal give-up + un-pauses auto-reconnect.
        bondGiveUp.reset()
        // A clean teardown also clears the bond-watchdog bounce streak (fresh escalation next time).
        bondWatchdogBackoff.reset()
        autoReconnectPausedForBondLoop = false
        bondLoopPausedAtMs = null
        // A user-initiated teardown resolves the re-pair guide (no longer looping).
        _state.update { it.copy(scanning = false, statusNote = null, reconnectGuide = null) }
        // disconnect() can throw on a dead binder (radio off). If it does, the OS won't deliver
        // onConnectionStateChange(DISCONNECTED), so tear down directly instead of crashing.
        try {
            gatt?.disconnect()   // onConnectionStateChange(DISCONNECTED) does the teardown + close.
        } catch (t: Throwable) {
            log("gatt.disconnect() threw ${t.javaClass.simpleName}; tearing down directly")
            teardownAfterGattFailure()
        }
    }

    /**
     * The OS Bluetooth radio turned OFF. Turning it off does NOT deliver onConnectionStateChange
     * (DISCONNECTED), so the orphaned link would otherwise linger (gatt non-null, connected=true, fake
     * live data). Runs the full teardown now; safe to suppress auto-reconnect since [connect] would
     * reject one anyway while the radio is off, and [onBluetoothRadioOn] re-arms it. Idempotent.
     */
    fun onBluetoothRadioOff() {
        handler.post {
            if (gatt == null && !_state.value.connected) {
                log("Bluetooth radio off — already disconnected")
                return@post
            }
            log("Bluetooth radio turned off — tearing down the orphaned link")
            teardownAfterGattFailure()
            // teardownAfterGattFailure → handleDisconnect already publishes connected=false; make the
            // "off" reason explicit for the UI so it reads "Bluetooth is off" rather than "Reconnecting…".
            _state.update { it.copy(
                connected = false, scanning = false, streamingLiveHR = false,   // keep streamingLiveHR ⟹ connected
                statusNote = "Bluetooth is off. Turn it on to reconnect.",
            ) }
        }
    }

    /**
     * The OS Bluetooth radio came back ON. Resume the connection the user last had: reconnect directly
     * to the remembered strap if we have one, else re-scan for the selected family. The connect path's
     * own adapter.isEnabled gate is now satisfied. Called from the ACTION_STATE_CHANGED receiver.
     */
    fun onBluetoothRadioOn() {
        handler.post {
            if (gatt != null || _state.value.connected) return@post   // already (re)connected
            // This radio-on reconnect supersedes any pending backoff timer (both branches below (re)connect).
            cancelPendingReconnect()
            val dev = lastDevice
            // Multi-WHOOP: only fast-path reconnect to [lastDevice] when it's still the pinned strap; an
            // un-pinned (or differently-pinned) last device falls through to the pin-aware rescan.
            // Single-WHOOP: preferredAddress null → always preferred → unchanged.
            if (dev != null && isPreferred(dev)) {
                log("Bluetooth radio back on — reconnecting directly to the last strap")
                intentionalDisconnect = false
                connectToDevice(dev, autoConnect = true)
            } else {
                log("Bluetooth radio back on — rescanning for your ${selectedModel.displayName}")
                connect(selectedModel)
            }
        }
    }

    /**
     * ONE bounded salvage attempt while the bond-loop pause is latched, fired on app-foreground. A
     * genuine bond fully resets the pause via [clearPairingHint]; a still-refusing strap costs one
     * attempt per [BOND_LOOP_SALVAGE_FLOOR_MS] and never re-enters the hammer loop.
     */
    fun salvageProbeIfBondLoopPaused() {
        handler.post {
            val since = bondLoopPausedAtMs?.let { System.currentTimeMillis() - it }
            if (!shouldSalvageProbe(autoReconnectPausedForBondLoop, _state.value.connected,
                                    intentionalDisconnect, since)) return@post
            if (gatt != null || scanning) return@post   // an attempt is already in flight - never stack one
            bondLoopPausedAtMs = System.currentTimeMillis()   // re-floor: max one probe per foreground AND per window
            log("Bond-loop pause: one salvage probe (the strap may have been freed since the give-up) - the give-up stays latched")
            intentionalDisconnect = false
            val dev = lastDevice
            if (dev != null && isPreferred(dev)) connectToDevice(dev, autoConnect = true)
            else connect(selectedModel)
        }
    }

    /**
     * Switch which strap we'll connect to next: drop the current strap and clear the **sticky** bond
     * state so a newly-picked model bonds fresh. Without this, `bonded` stayed true from the first strap,
     * hiding the strap picker and keeping the scan pointed at the old family's service.
     */
    fun prepareForModelSwitch() {
        disconnect()
        lastDevice = null   // don't auto-reconnect to the old strap; the next connect scans for the new model
        _state.update { it.copy(connected = false, bonded = false, encryptedBond = false,
                                streamingLiveHR = false,   // a device switch drops any external stream too
                                r22FlagsAccepted = 0, deepPacketsThisSession = 0) }   // reset per session
    }

    /**
     * Idle the engine before presenting an Add-a-WHOOP scan, but ONLY when not already connected to a
     * strap of this same model — dropping a live bonded connection here left it disconnected for good (a
     * 5/MG re-bond after teardown refuses insufficient-auth and loops). The keep-path only borrows the LE
     * scanner; a genuine model switch still idles via [prepareForModelSwitch].
     */
    fun prepareForPresentScan(model: WhoopModel) {
        if (shouldKeepLiveConnectionForPresentScan(_state.value.connected, selectedModel, model)) {
            log("Add-a-WHOOP scan: keeping the live ${selectedModel.displayName} connection - presenting nearby straps without dropping it")
            return
        }
        prepareForModelSwitch()
    }

    /**
     * Fully RELEASE the strap when the user REMOVES it from the Devices screen, so the band can enter
     * pairing mode. Archiving the registry row alone left NOOP still holding it — the reconnect timer,
     * the targeted-connect pin, and the persisted last-device address all still pointed at it, so a
     * connected WHOOP could never show its pairing LEDs. Clears every reference; runs on the main looper.
     */
    fun releaseStrap() {
        handler.post {
            intentionalDisconnect = true     // defuse the disconnect→3s-reconnect loop's guard
            handler.removeCallbacks(scanTimeoutRunnable)
            handler.removeCallbacks(scanFallbackRunnable)
            stopScan()
            // Clear the targeting that could re-grab this strap: the multi-WHOOP pin and the remembered last device.
            preferredAddress = null          // back to "connect to the first WHOOP found" (single-WHOOP default)
            lastDevice = null                // don't fast-path reconnect to it (onBluetoothRadioOn / auto-reconnect)
            pinnedBondRefusals = 0
            // Releasing a strap fully resets the give-up + pause (like disconnect()) so a paused state
            // can never outlive the strap it belonged to and wedge a later re-add.
            bondRefusalStreak = 0
            bondGiveUp.reset()
            autoReconnectPausedForBondLoop = false
            bondLoopPausedAtMs = null
            // Drop the persisted last-device pin so a relaunch / radio-on doesn't auto-reconnect to it.
            NoopPrefs.clearLastDevice(context)
            // Drop the live BLE link so the strap is free to enter pairing mode. disconnect() can throw on a
            // dead binder; tear down directly if so.
            try {
                gatt?.disconnect()           // onConnectionStateChange(DISCONNECTED) does the teardown + close
            } catch (t: Throwable) {
                log("releaseStrap: gatt.disconnect() threw ${t.javaClass.simpleName}; tearing down directly")
                teardownAfterGattFailure()
            }
            _state.update { releasedLiveState(it) }
            log("Device removed — released the strap: stopped auto-reconnect, dropped the link, cleared " +
                "targeting. Put it in pairing mode (blue LEDs) to re-pair if you want it back.")
        }
    }

    /**
     * Re-point which device id live WHOOP samples store under, when the active WHOOP changes (a
     * WHOOP↔WHOOP switch via the registry). Only [SourceCoordinator] calls this, and only when a
     * DIFFERENT registered WHOOP becomes active — the single-WHOOP path leaves the seeded "my-whoop" id
     * in place, so that path is byte-for-byte unchanged. Sets this client's [deviceId] AND re-points the
     * in-flight [Backfiller] (which captured its own copy at construction) so the very next live flush /
     * standard-HR persist / historical finishChunk attributes new samples to the new id without waiting
     * for a relaunch. Empty id is ignored.
     */
    fun setActiveDeviceId(id: String) {
        if (id.isEmpty()) return
        deviceId = id
        backfiller.deviceId = id
    }

    /**
     * Add-a-device wizard present-scan: scan the given WHOOP family's service and surface every nearby
     * strap in [discoveredWhoops] WITHOUT auto-connecting. Turns on [scanningForList] so [onScanResult]
     * accumulates rather than connecting. Does NOT disturb an existing connection (never touches
     * [gatt]/bond state), but does take over the single LE scanner, so the wizard MUST call
     * [stopWhoopScan] before any normal connect resumes.
     */
    @SuppressLint("MissingPermission")
    fun scanForWhoops(model: WhoopModel) {
        val adp = adapter
        if (adp == null || !adp.isEnabled) {
            log("Add-a-WHOOP scan: Bluetooth not ready")
            return
        }
        val sc = scanner
        if (sc == null) {
            log("Add-a-WHOOP scan: no BLE scanner available")
            return
        }
        // Cancel the auto-connect scan's not-found/fallback timers — neither should fire during a
        // present-scan — and stop whatever LE scan is running before re-arming our own.
        handler.removeCallbacks(scanTimeoutRunnable)
        handler.removeCallbacks(scanFallbackRunnable)
        stopScan()
        selectedModel = model
        scanningForList = true
        _discoveredWhoops.value = emptyList()   // fresh list each time the wizard opens the scan
        // Also list a WHOOP 5/MG the OS already holds connected, so the wizard can add a band that is not
        // advertising (rssi 0 marks a connected, non-advertised entry). The scan below still adds any
        // advertising straps.
        getConnectedWhoopDevice()?.let { d ->
            val n = try { d.name } catch (se: SecurityException) { null }
            // getConnectedWhoopDevice only ever returns a 5/MG-named strap (its name filter excludes 4.0),
            // so its family is known even without an advert.
            _discoveredWhoops.value = listOf(DiscoveredWhoop(address = d.address, name = n, rssi = 0, family = WhoopModel.WHOOP5_MG))
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(model.service)).build(),
        )
        // LOW_LATENCY for a snappy wizard; the in-callback accumulation refreshes RSSI as straps move.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        try {
            sc.startScan(filters, settings, scanCallback)
            log("Add-a-WHOOP scan: presenting nearby ${model.displayName} straps")
        } catch (se: SecurityException) {
            scanning = false
            scanningForList = false
            log("Add-a-WHOOP scan blocked (permission): ${se.message}")
        } catch (t: Throwable) {
            scanning = false
            scanningForList = false
            log("Add-a-WHOOP scan failed to start: ${t.message}")
        }
    }

    /**
     * First-run onboarding present-scan: list nearby WHOOP straps of BOTH families at once WITHOUT
     * auto-connecting, so the user never has to pick 4.0 vs 5/MG up front. Same accumulate-don't-connect
     * behaviour as [scanForWhoops] (it flips [scanningForList] and clears the list), but builds a
     * ScanFilter LIST over both services — a filter list is OR'd, so a 4.0 and a 5/MG show up together —
     * and does NOT force [selectedModel] to one family (the picked strap's family is recorded via
     * [noteSelectedModel] when the user taps it). Leaves any live link alone (it never touches gatt/bond).
     */
    @SuppressLint("MissingPermission")
    fun scanForWhoopsAll() {
        val adp = adapter
        if (adp == null || !adp.isEnabled) {
            log("Add-a-WHOOP scan: Bluetooth not ready")
            return
        }
        val sc = scanner
        if (sc == null) {
            log("Add-a-WHOOP scan: no BLE scanner available")
            return
        }
        handler.removeCallbacks(scanTimeoutRunnable)
        handler.removeCallbacks(scanFallbackRunnable)
        stopScan()
        scanningForList = true
        _discoveredWhoops.value = emptyList()   // fresh list each time the onboarding scan opens
        // Also list a 5/MG the OS already holds connected (rssi 0 = connected, not advertising); its family
        // is known (the getConnected filter is 5/MG-named). The scan below still adds advertising straps.
        getConnectedWhoopDevice()?.let { d ->
            val n = try { d.name } catch (se: SecurityException) { null }
            _discoveredWhoops.value = listOf(DiscoveredWhoop(address = d.address, name = n, rssi = 0, family = WhoopModel.WHOOP5_MG))
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(WhoopModel.WHOOP4.service)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(WhoopModel.WHOOP5_MG.service)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        try {
            sc.startScan(filters, settings, scanCallback)
            log("Add-a-WHOOP scan: presenting nearby WHOOP straps (all families)")
        } catch (se: SecurityException) {
            scanning = false
            scanningForList = false
            log("Add-a-WHOOP scan blocked (permission): ${se.message}")
        } catch (t: Throwable) {
            scanning = false
            scanningForList = false
            log("Add-a-WHOOP scan failed to start: ${t.message}")
        }
    }

    /**
     * Record the WHOOP family the user picked from the merged onboarding scan WITHOUT the model-switch
     * teardown that clears the saved device + drops the live bond.
     * Points this client's [selectedModel] at the detected family and persists it so a later launch /
     * [SourceCoordinator] reconnect targets the right service. Idempotent.
     */
    fun noteSelectedModel(model: WhoopModel) {
        selectedModel = model
        persistSelectedModel(model)
    }

    /**
     * End the Add-a-device present-scan: stop scanning and clear [scanningForList] so [onScanResult]
     * returns to its normal auto-connect behaviour. Idempotent — safe to call when not presenting.
     */
    @SuppressLint("MissingPermission")
    fun stopWhoopScan() {
        if (!scanningForList) return
        scanningForList = false
        stopScan()
        log("Add-a-WHOOP scan: stopped")
    }

    /**
     * Reconnect DIRECTLY to a previously-bonded strap by its address — no scan — for auto-reconnect on
     * app launch. No-op if already connecting/connected, the address can't be resolved, or the runtime
     * Bluetooth permission isn't granted yet (the user will connect manually / next launch).
     * Uses connectGatt(autoConnect=true) so the OS connects as soon as the strap is reachable.
     */
    @SuppressLint("MissingPermission")
    fun reconnectToAddress(address: String, model: WhoopModel) {
        if (gatt != null || _state.value.connected) return
        val adp = adapter ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val device = runCatching { adp.getRemoteDevice(address) }.getOrNull() ?: return
        selectedModel = model
        intentionalDisconnect = false
        log("Auto-reconnecting to your saved ${model.displayName}…")
        // A targeted reconnect supersedes any pending involuntary backoff timer.
        cancelPendingReconnect()
        connectToDevice(device, autoConnect = true)
    }

    /**
     * Send a command to the strap. The frame bytes come from the whoop-rs FFI (via [sendCommand]); this
     * keeps only the send policy (seq, 5/MG allow-list, gates). Writes to the command characteristic.
     *
     * Default write type is WITHOUT response, so existing call sites (toggleRealtimeHR, getBatteryLevel,
     * runHapticsPattern) are link-cheap. The bond write and any acked command use WITH response.
     */
    fun send(cmd: CommandNumber, payload: ByteArray = byteArrayOf(0), withResponse: Boolean = false) {
        val gen = connectedFamily.gen
        val gen5 = gen == Gen.GEN5
        // WHOOP 5/MG haptics differ from 4.0 on opcode AND payload: cmd 0x13 (not 79, which a real MG
        // rejects) + the maverick "notify" preset body. Everything else builds via the generic FFI
        // command builder; the frame bytes are byte-identical to the former Kotlin envelope (test-locked).
        val isHaptics = cmd == CommandNumber.RUN_HAPTICS_PATTERN
        val sent = sendCommand(cmd, withResponse) { s ->
            when {
                gen5 && isHaptics -> RustCodec.buzzFrame(s)
                else -> RustCodec.commandFrame(gen, seq = s, cmd = cmd.rawValue, payload = payload)
            }
        }
        if (sent) {
            val note = if (gen5) (if (isHaptics) " (puffin cmd=0x13)" else " (puffin)") else ""
            log("→ ${cmd.name} payload=${payload.toHex()}$note")
        }
    }

    /**
     * The single outbound choke: not-connected guard, the 5/MG send allow-list gate, one seq off the
     * shared counter, then the whoop-rs FFI [build] for the frame bytes, then enqueue. Returns true when
     * a frame was enqueued. All send POLICY stays here in Kotlin; whoop-rs owns only the bytes.
     */
    private fun sendCommand(cmd: CommandNumber, withResponse: Boolean, build: (seq: Int) -> ByteArray?): Boolean {
        val ch = cmdCharacteristic
        if (gatt == null || ch == null) {
            log("send(${cmd.name}) ignored — not connected")
            return false
        }
        if (connectedFamily == DeviceFamily.WHOOP5 && !whoop5SendAllowed(cmd)) {
            log("send(${cmd.name}) skipped — no WHOOP 5/MG framing for this command yet")
            return false
        }
        val s = seq.incrementAndGet() and 0xFF
        val frame = build(s)
        if (frame == null) {
            log("send(${cmd.name}) refused — destructive opcode blocked by the codec")
            return false
        }
        enqueueWrite(PendingWrite(frame, withResponse, cmd))
        return true
    }

    /**
     * The WHOOP 5/MG send allow-list. 5/MG uses puffin (CRC16) framing; the realtime-HR toggle is
     * hardware-confirmed, the offload pair rides the same proven COMMAND frame, SET_CONFIG /
     * SET_DEVICE_CONFIG are reversible deep-data / broadcast-HR opt-ins, and REBOOT_STRAP and
     * SELECT_WRIST are user-initiated from the device menu. Everything else stays dropped on 5/MG.
     * WHOOP 4.0 sends all.
     */
    private fun whoop5SendAllowed(cmd: CommandNumber): Boolean = when (cmd) {
        CommandNumber.TOGGLE_REALTIME_HR, CommandNumber.RUN_HAPTICS_PATTERN,
        CommandNumber.SEND_HISTORICAL_DATA, CommandNumber.HISTORICAL_DATA_RESULT,
        CommandNumber.SET_CLOCK, CommandNumber.GET_CLOCK, CommandNumber.GET_DATA_RANGE,
        CommandNumber.SET_ALARM_TIME, CommandNumber.DISABLE_ALARM, CommandNumber.GET_ALARM_TIME,
        CommandNumber.REBOOT_STRAP, CommandNumber.SELECT_WRIST,
        CommandNumber.GET_BATTERY_PACK_INFO -> true
        CommandNumber.SET_CONFIG -> puffinExperiment.isDeepDataEnabled
        CommandNumber.SET_DEVICE_CONFIG -> false
        else -> false
    }

    /**
     * Fire a preset haptic buzz on the strap: RUN_HAPTICS_PATTERN(79) with payload
     * `[patternId=2, loops, 0, 0, 0]`. patternId=2 is the graduated alarm buzz the official WHOOP app
     * uses. Used by scheduled cues (intervals, Breathe, notification mirrors); for a user-facing
     * "buzz the strap now" action use [buzzStrapOnce] instead.
     */
    fun buzz(loops: Int = 2) {
        val n = loops.coerceIn(0, 255)
        send(CommandNumber.RUN_HAPTICS_PATTERN, byteArrayOf(2, n.toByte(), 0, 0, 0))
        log("Buzz: patternId=2 loops=$n")
    }

    /**
     * One-shot user buzz: the on-device-confirmed "vibrate the strap now" sequence. RUN_HAPTICS_PATTERN
     * (79) `[patternId=2, loops=3, 0, 0, 0]` followed by RUN_ALARM(68) `[0x01]` as a belt-and-suspenders
     * (a bare pattern write alone was reported ignored on some 4.0 straps). Both writes are ACKNOWLEDGED
     * (withResponse=true): a busy link can silently drop an unacked write with no vibration. 5/MG: [send]
     * remaps cmd 79 to the maverick 0x13 notify buzz; RUN_ALARM isn't allow-listed there, so the
     * follow-up is WHOOP 4.0 only — the maverick buzz alone is the confirmed 5/MG one-shot.
     */
    fun buzzStrapOnce() {
        send(CommandNumber.RUN_HAPTICS_PATTERN, byteArrayOf(2, 3, 0, 0, 0), withResponse = true)
        if (connectedFamily == DeviceFamily.WHOOP5) {
            log("Buzz: one-shot fired (5/MG maverick buzz, acked)")
            return
        }
        send(CommandNumber.RUN_ALARM, byteArrayOf(0x01), withResponse = true)
        log("Buzz: one-shot fired (patternId=2 loops=3 + RUN_ALARM, acked)")
    }

    /**
     * Tell the strap to STOP an in-progress haptic pattern. The Breathe biofeedback loop schedules a
     * stream of buzzes; ending the session can't recall one already mid-way through, so a dropped link
     * mid-pattern can leave the strap's haptic manager wedged. STOP_HAPTICS (cmd 122, `[0x00]`) is the
     * documented, reversible clear for WHOOP 4.0.
     *
     * 5/MG CAVEAT: the 5/MG buzz rides the maverick 0x13 one-shot path; cmd 122 there is unconfirmed and
     * not allow-listed, so this is a no-op on 5/MG (logged "skipped"), not a guessed write. Best-effort:
     * reliably clears a wedged 4.0; the 5/MG one-shot already limits the wedge. Safe to call always.
     */
    fun stopHaptics() {
        send(CommandNumber.STOP_HAPTICS, byteArrayOf(0))
        log("Stop haptics (cmd 122)")
    }

    /**
     * Haptic Clock: buzz the current wall-clock time out on the strap so the user can read it off their
     * wrist without a screen. The pure, unit-tested [HapticClock] encoder turns now into an ordered pulse
     * list (long = "ten", short = "unit", HH-tens/HH-units/MM-tens/MM-units order); each pulse is
     * scheduled via [handler].postDelayed, firing the existing maverick buzz ([buzz], remapped to
     * cmd-0x13 on 5/MG). Only the schedule is new.
     *
     * [is24h] controls 12h/24h reading (default 12h). A fixed-length motor pulse can't vary on-time per
     * pulse, so a LONG pulse fires two stacked loops and a SHORT pulse one — the only way to distinguish
     * long vs short on the wrist. Pulse-feel timing can only be confirmed on a real strap motor.
     * Long-press/double-tap strap input is not wired (no tap event is parsed yet).
     */
    fun buzzTimeNow(is24h: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val pulses = HapticClock.pulses(hour, minute, is24h)
        if (pulses.isEmpty()) {
            log("Haptic Clock: nothing to buzz (00:00 in 24h form).")
            return
        }
        log("Haptic Clock: buzzing ${pulses.size} pulses for the current time (${if (is24h) "24h" else "12h"}).")
        // Walk the encoder's pulse list, converting each (durationMs,gapMs) into a scheduled buzz.
        // A long pulse is felt as a heavier buzz (2 stacked loops); a short pulse as a light one (1).
        var offsetMs = 0L
        for (pulse in pulses) {
            val loops = if (pulse.isLong) 2 else 1
            handler.postDelayed({ buzz(loops) }, offsetMs)
            offsetMs += (pulse.durationMs + pulse.gapMs).toLong()
        }
    }

    /**
     * Inactivity reminder: on each natural offload completion, run the shipped, unit-tested
     * [SedentaryDetector] over the freshly-arrived gravity window and buzz the wrist if the user has
     * been seated too long. NO offload-timer change — a read-only hook on an event that already happens,
     * so the nudge lags the stillness by the offload cadence (~7-15 min). Best-effort.
     *
     * All gating + de-dup lives in the engine: we only supply honest inputs (recent gravity, the live
     * worn flag, the prefs→[SedentaryConfig]/[SedentaryState]) and persist the engine's `nextState`. The
     * engine acts only when this offload advanced the newest gravity ts (a replayed / no-new-rows sync
     * can't re-buzz), only for a bout whose end is still current, only through its mayBuzz gate (master /
     * quiet hours / worn / active-hours-by-bout-end-time), and either re-nudges a continuing bout on the
     * user's cadence or alerts a distinct new bout separated by movement.
     */
    private fun maybeBuzzInactivity() {
        if (!InactivityPrefs.enabled(context)) return
        ioScope.launch {
            try {
                val nowSec = System.currentTimeMillis() / 1000L
                val from = nowSec - INACTIVITY_LOOKBACK_S
                val grav = repository.gravitySamples(deviceId, from, nowSec)
                if (grav.isEmpty()) return@launch

                val decision = SedentaryDetector.evaluate(
                    gravity = grav,
                    state = InactivityPrefs.state(context),
                    config = InactivityPrefs.config(context),
                    worn = _state.value.worn,
                    nowSec = nowSec,
                    tzOffsetSec = InactivityPrefs.tzOffsetSec(nowSec),
                )
                // Persist the advanced de-dup state every run (the engine always advances
                // lastProcessedGravityTs when a window arrived), so a replayed window can't re-buzz.
                InactivityPrefs.saveState(context, decision.nextState)

                if (decision.shouldBuzz) {
                    handler.post { buzz(decision.buzzLoops) }
                    val mins = ((decision.bout?.durationS ?: 0.0) / 60).toInt()
                    log("Inactivity: nudged after a $mins-min sedentary stretch.")
                    // Also surface the wrist buzz as a local notification (a pocketed phone can't show
                    // it on screen). Self-gated on the wrist-alerts master.
                    InactivityNotifier.onNudged(context, mins)
                }
            } catch (t: Throwable) {
                log("Inactivity: check failed (${t.message})")
            }
        }
    }

    /**
     * L3 closed-loop stress check-in. On the same natural offload completion that drives
     * [maybeBuzzInactivity], run the shipped, unit-tested [StressOnsetDetector] over the live R-R buffer:
     * a FRESH, non-metabolic HRV dip fires a single confirming buzz + a passive in-app card via
     * [StressNudgeCenter.present]. NEVER a push, NEVER a diagnosis — "stress" is an autonomic proxy vs
     * the user's OWN baseline. All gating + de-dup is in the engine; we only supply honest inputs and
     * persist its [nextState] so a replayed window can't re-fire. Toggles + quiet hours come from
     * [BiofeedbackPrefs].
     */
    private fun maybeNudgeStress() {
        val config = BiofeedbackPrefs.stressConfig(context)
        // Cheap master gate before any DB work — inert when the feature/auto-nudge is off.
        if (!config.enabled || !config.autoNudge) return
        ioScope.launch {
            try {
                val nowSec = System.currentTimeMillis() / 1000L
                // Recent wrist-motion (g): the smoothed activity intensity over the freshly-arrived
                // gravity window, the same primitive SedentaryDetector reuses. Null when there's no
                // recent gravity — the engine then leans on the resting-HR band gate (spec Q3).
                val from = nowSec - INACTIVITY_LOOKBACK_S
                val grav = runCatching { repository.gravitySamples(deviceId, from, nowSec) }.getOrDefault(emptyList())
                val recentMotionG = WorkoutDetector.activitySeries(grav).lastOrNull()?.intensity

                val live = _state.value
                val decision = StressOnsetDetector.evaluate(
                    rrBuffer = live.rrRecent,
                    currentHR = live.heartRate?.toDouble(),
                    recentMotionG = recentMotionG,
                    // We never offer the cue over a manual Breathe/L1/L2 session; the BLE layer doesn't
                    // track that, so leave it false — the in-app card is also suppressed by its own UI.
                    sessionActive = false,
                    state = BiofeedbackPrefs.loadStressState(context),
                    config = config,
                    nowSec = nowSec,
                    tzOffsetSec = InactivityPrefs.tzOffsetSec(nowSec),
                )
                // Persist the advanced de-dup/EMA state every run so a replayed window can't re-fire.
                BiofeedbackPrefs.saveStressState(context, decision.nextState)

                if (decision.shouldNudge) {
                    handler.post { buzz(decision.buzzLoops) }
                    StressNudgeCenter.present(
                        fastRMSSD = decision.fastRMSSD,
                        baselineRMSSD = decision.baselineRMSSD,
                    )
                    log("Stress check-in: nudged on a fresh non-metabolic HRV dip.")
                }
            } catch (t: Throwable) {
                log("Stress check-in: check failed (${t.message})")
            }
        }
    }

    /**
     * On-device SHORT-NAP detection. Read-only hook on the natural offload completion — the SAME
     * instant [maybeNudgeStress] / [maybeBuzzInactivity] run, so it adds NO cadence of its own. Over the
     * freshly-offloaded daytime window it runs the pure, unit-tested [NapDetector] (dense-gravity
     * eligibility gate → tri-state NAP / NONE / INCONCLUSIVE) and, ONLY on a confident NAP, queues the
     * candidate for review via [NapStore]. It NEVER auto-writes a sleep session: a confirmed nap goes
     * through the user's review card → `addManualNap`, the same overlap-guarded path a hand-corrected
     * nap uses. Honest by construction: an INCONCLUSIVE window queues nothing. Self-gates on the
     * NapPrefs toggle (default OFF, opt-in).
     */
    private fun maybeDetectNaps() {
        if (!NapPrefs.enabled(context)) return   // cheap master gate before any DB work
        ioScope.launch {
            try {
                val nowSec = System.currentTimeMillis() / 1000L
                // Look back over the freshly-offloaded daytime window (the same lookback the inactivity /
                // stress hooks read), so a brief afternoon nap that just landed gets judged.
                val from = nowSec - INACTIVITY_LOOKBACK_S
                val grav = runCatching { repository.gravitySamples(deviceId, from, nowSec) }.getOrDefault(emptyList())
                if (grav.isEmpty()) return@launch
                val hr = runCatching { repository.hrSamples(deviceId, from, nowSec) }.getOrDefault(emptyList())
                // Honest resting band: the newest daily metric's resting HR, or null (the engine then
                // leans on motion alone at lower confidence — it never fabricates a band).
                val restingHr = runCatching {
                    repository.days(deviceId).mapNotNull { it.restingHr }.lastOrNull()
                }.getOrNull()

                // High-water mark: never surface a nap whose window ended before nap detection first ran
                // (a deep first-offload backlog would otherwise dredge up days of old naps). Seeded to
                // "now" on the first read.
                val highWater = NapPrefs.highWaterOrSeed(context, nowSec)

                val decision = NapDetector.evaluate(
                    gravity = grav,
                    hr = hr.map { HrRow(it.ts, it.bpm) },
                    restingHr = restingHr,
                    config = NapPrefs.config(context),
                )
                if (decision.verdict == NapVerdict.NAP && decision.candidate != null &&
                    decision.candidate.end > highWater
                ) {
                    val queued = NapStore.enqueue(context, decision.candidate, nowSec)
                    // Advance the mark past this nap's window so the same window isn't re-judged on the next
                    // overlapping offload — whether or not it newly queued (a dup the user already saw or
                    // dismissed is still "past"). NapStore's own dedup is the belt to this braces.
                    NapPrefs.setHighWaterTs(context, decision.candidate.end)
                    if (queued) {
                        val mins = decision.candidate.durationS / 60
                        log("Nap detection: queued a ~$mins-min nap for review.")
                    }
                }
            } catch (t: Throwable) {
                log("Nap detection: check failed (${t.message})")
            }
        }
    }

    /**
     * Rename the WHOOP 4.0's BLE advertising name (the name the OS shows in Bluetooth) via
     * SET_ADVERTISING_NAME (cmd 77). Payload `[0x00,0x00] + UTF-8 name + [0x00]`, clamped to 24 UTF-8
     * bytes so it can't overflow the advertising packet; the strap reboots to apply, so the new name
     * appears on the next connect (the OS re-reads it). WHOOP 4.0 only — a 5/MG uses puffin framing and
     * a different device-config path. Requires a bonded link. Reversible: rename again any time.
     * Returns what it did so the caller can say so; [LiveState.renameStatus] carries the same wording.
     */
    fun renameStrap(rawName: String): StrapRename {
        val name = rawName.trim()
        if (connectedFamily != DeviceFamily.WHOOP4) {
            _state.update { it.copy(renameStatus = StrapRename.NotWhoop4.message) }
            log("Strap rename: WHOOP 4.0 only — ignored.")
            return StrapRename.NotWhoop4
        }
        if (!_state.value.connected || !_state.value.bonded) {
            _state.update { it.copy(renameStatus = StrapRename.NotConnected.message) }
            return StrapRename.NotConnected
        }
        if (name.isEmpty()) {
            _state.update { it.copy(renameStatus = StrapRename.EmptyName.message) }
            return StrapRename.EmptyName
        }
        // The whoop-rs builder clamps to 24 UTF-8 bytes on a char boundary; clamp a local copy for the log.
        var clamped = name
        while (clamped.toByteArray(Charsets.UTF_8).size > 24) clamped = clamped.dropLast(1)
        val queued = sendCommand(CommandNumber.SET_ADVERTISING_NAME, withResponse = true) { s ->
            RustCodec.advertisingNameFrame(s, name)
        }
        val outcome = renameOutcome(queued)
        log("Strap rename: advertising name=$clamped ${if (queued) "written" else "NOT written, link refused it"}")
        _state.update { it.copy(renameStatus = outcome.message) }
        return outcome
    }

    // Reboot (user-initiated, confirmation-gated).

    /** elapsedRealtime (ms) of the last user reboot, or null. Set by [rebootStrap]; consumed by the
     *  disconnect handler (link-up duration = the strap acting on the reboot) and the connect handshake
     *  (the reconnect round-trip). Cleared on reconnect or the no-disconnect watchdog. */
    private var rebootRequestedAtMs: Long? = null
    private var rebootWatchdog: Runnable? = null
    private var rebootSettle: Runnable? = null

    /** Clear all reboot-in-flight state: the pending timestamp, both timers, and the `rebootInProgress`
     *  flag that drives the Devices "Reconnecting…" pill. Called from every terminal path (reconnect,
     *  no-disconnect, settle backstop) so the pill can never wedge. */
    private fun clearRebootState() {
        rebootRequestedAtMs = null
        rebootWatchdog?.let { handler.removeCallbacks(it) }; rebootWatchdog = null
        rebootSettle?.let { handler.removeCallbacks(it) }; rebootSettle = null
        if (_state.value.rebootInProgress) _state.update { it.copy(rebootInProgress = false) }
    }

    /**
     * Tell the strap which wrist it is worn on (SELECT_WRIST / opcode 123, payload
     * `[revision, 0 left / 1 right]`). A persistent device-config write, so it is user-initiated from the
     * device menu and never automatic. The payload shape is inferred from the firmware's revision and
     * wrist-value checks: a strap that refuses it answers with a non-SUCCESS result, which the
     * COMMAND_RESPONSE handler logs, so a rejection is visible instead of silent.
     */
    fun selectWrist(right: Boolean) {
        send(CommandNumber.SELECT_WRIST, byteArrayOf(0x01, if (right) 1 else 0), withResponse = true)
    }

    /**
     * Restart the connected strap (REBOOT_STRAP / opcode 29, empty body). Non-destructive: the strap keeps
     * its stored data and re-advertises after boot; the BLE link drops and NOOP auto-reconnects. Gated to a
     * connected + bonded strap; user-initiated and confirmation-gated at the call site (DevicesScreen).
     * Emits the full reboot trail (request / sent / ack / link dropped / reconnected) so a "restart did
     * nothing" report, especially on the unverified 5/MG puffin framing, is triageable from a strap log.
     */
    fun rebootStrap() {
        // Production Restart: opcode 29 REBOOT_STRAP, empty body per the official app's builder.
        // Confirmed on WHOOP 5.0; ignored on 4.0 (see rebootProbe).
        sendRebootFrame(CommandNumber.REBOOT_STRAP, byteArrayOf(), null)
    }

    /** Send one candidate reboot frame from the WHOOP 4.0 reboot probe (Test Centre → Connection).
     *  WHOOP 4.0 only — a 5.0 already reboots on the production frame, so there is nothing to probe
     *  there. Reuses the full reboot watchdog/trail so the strap log shows whether THIS candidate
     *  dropped the link (`reboot: link dropped …`) or was ignored (`reboot: no disconnect within 12s …`).
     *  Confirmation-gated at the call site (DevicesScreen). */
    fun rebootProbe(variant: RebootProbeVariant) {
        if (connectedFamily != DeviceFamily.WHOOP4) {
            log("reboot: probe is WHOOP 4.0 only — ignored (family=$connectedFamily)")
            return
        }
        sendRebootFrame(variant.command, variant.payload, variant)
    }

    /** Shared reboot send + debug trail + watchdog, used by both the production [rebootStrap] and the
     *  4.0 [rebootProbe]. `probe == null` is the normal restart; a non-null variant is a probe attempt
     *  (its `logTag` is stamped first so the strap log correlates the attempt with what the strap did). */
    private fun sendRebootFrame(command: CommandNumber, payload: ByteArray, probe: RebootProbeVariant?) {
        val family = connectedFamily
        if (!_state.value.connected || !_state.value.bonded || gatt == null) {
            log("reboot: connect + bond first — ignored (connected=${_state.value.connected} bonded=${_state.value.bonded})")
            return
        }
        // Supersede any still-pending reboot (cancels its timers + resets the flag) so a repeat tap can't
        // leave a stale watchdog/settle timer that fires during this new reboot's window.
        clearRebootState()
        // The logged opcode is always the command's on-wire value — never a separate field that could
        // disagree with the bytes actually sent.
        val opcode = command.rawValue
        val framing = if (family == DeviceFamily.WHOOP5) "puffin-crc16 (verified on 5.0 fw 50.40.1.0)" else "harvard-crc8 (UNVERIFIED on 4.0)"
        val fw = _state.value.strapFirmware ?: "unknown"
        val payloadDesc = if (payload.isEmpty()) "empty" else payload.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        if (probe != null) log("reboot: PROBE ${probe.logTag} — trying an unconfirmed WHOOP 4.0 reboot frame")
        log("reboot: request family=$family fw=$fw connected=true bonded=true")
        log("reboot: sent opcode=$opcode framing=$framing payload=$payloadDesc writeType=withResponse")
        // withResponse so the ATT write is acked before the strap drops the link.
        send(command, payload, withResponse = true)
        rebootRequestedAtMs = SystemClock.elapsedRealtime()
        // Drive the Devices "Reconnecting…" pill: true until the strap reconnects (or a terminal path
        // clears it). The pill only shows it once the link actually drops (it gates on !connected).
        _state.update { it.copy(rebootInProgress = true) }
        rebootWatchdog?.let { handler.removeCallbacks(it) }
        // No-disconnect watchdog: still connected after 12s ⇒ the strap didn't act on the command (the key
        // signal that a 5/MG puffin reboot frame was silently rejected). A real reboot drops within ~1-2s
        // when idle; a strap mid-offload finishes the transfer first (observed ~9s on 5.0 fw 50.40.1.0), so
        // 12s is the cutoff, not the expected latency.
        val work = Runnable {
            if (rebootRequestedAtMs != null && _state.value.connected) {
                log("reboot: no disconnect within 12s — strap may have ignored the command" +
                    if (connectedFamily == DeviceFamily.WHOOP5) " (5/MG reboot is verified on 5.0 fw 50.40.1.0; your firmware may differ)" else " (the WHOOP 4.0 reboot frame is NOT confirmed yet)")
                clearRebootState()
            }
        }
        rebootWatchdog = work
        handler.postDelayed(work, 12_000)
        // Absolute settle backstop: if the reboot never resolves (link dropped but the strap never comes
        // back), clear the pill after 60s so it can't wedge on "Reconnecting…". A normal reboot+reconnect
        // clears it earlier via noteRebootReconnectIfNeeded.
        val settle = Runnable {
            if (_state.value.rebootInProgress) {
                log("reboot: not settled within 60s — clearing the reconnecting state")
                clearRebootState()
            }
        }
        rebootSettle = settle
        handler.postDelayed(settle, 60_000)
    }

    /** Closes the reboot trail: when the connect handshake completes and a reboot was in flight, log the
     *  full round-trip (send → reboot → reconnect) and clear the pending state. No-op otherwise. */
    private fun noteRebootReconnectIfNeeded() {
        val t = rebootRequestedAtMs ?: return
        val s = (SystemClock.elapsedRealtime() - t) / 1000.0
        log("reboot: reconnected %.1fs after send — round trip complete".format(s))
        clearRebootState()   // clears the "Reconnecting…" pill → back to "Active · Live"
    }

    /**
     * Refresh the battery reading on demand ("Refresh battery", screen entry).
     *
     * Source is FAMILY-SPECIFIC: on a WHOOP 4.0 the standard 0x2A19 characteristic is a STUB that reports
     * a constant 100, while the real charge only comes from the proprietary GET_BATTERY_LEVEL command
     * (COMMAND_RESPONSE, u16/10) — reading both flashed 100% before the true value corrected it. WHOOP 4
     * uses ONLY the command; WHOOP 5/MG uses ONLY 0x2A19 (its proprietary command isn't framed).
     */
    /**
     * Read the strap's Device Information on [attempt] — its serial (GATT 0x2A25) first, then its
     * hardware revision (0x2A27) — retrying on the [DEVICE_INFO_READ_DELAYS_MS] schedule while either is
     * still unknown and the link is up. ONE read per tick, since the stack carries one operation at a
     * time. Reads only — the BLE safety contract forbids writes to hardware, and this adds none. A
     * strap that never answers keeps its address-derived identity and a null revision, never a guess.
     */
    @SuppressLint("MissingPermission")
    private fun scheduleDeviceInfoReads(attempt: Int) {
        if (attempt >= DEVICE_INFO_READ_DELAYS_MS.size) return
        handler.postDelayed({
            val g = gatt ?: return@postDelayed
            val wantSerial = _connectedStrapSerial.value == null
            if (!wantSerial && _connectedStrapHardwareRev.value != null) return@postDelayed
            val ops = gattOps
            val info = g.getService(DEVICE_INFO_SERVICE)
            val label = if (wantSerial) "serial" else "hardware revision"
            val ch = info?.getCharacteristic(if (wantSerial) SERIAL_NUMBER_CHAR else HARDWARE_REVISION_CHAR)
            if (ops == null || ch == null) {
                if (attempt == 0) log("Device Information not offered by this strap — keeping its address identity")
                return@postDelayed
            }
            safeGatt("readCharacteristic($label)") { ops.readCharacteristicCompat(ch) }
            scheduleDeviceInfoReads(attempt + 1)
        }, DEVICE_INFO_READ_DELAYS_MS[attempt])
    }

    /**
     * Ask the strap for its battery pack's fuel gauge (GET_BATTERY_PACK_INFO). The pack reaches the
     * strap over NFC and never advertises to the phone, so polling the strap is the only way to read it.
     * A read; 5/MG only, since a 4.0 has no pack command. The reply lands in the COMMAND_RESPONSE
     * handler, and a strap with no pack attached simply answers without pack fields.
     */
    fun refreshBatteryPack() {
        if (connectedFamily != DeviceFamily.WHOOP5) return
        send(CommandNumber.GET_BATTERY_PACK_INFO, byteArrayOf(0x01))
    }

    fun refreshBattery() {
        val g = gatt
        if (g == null) {
            log("refreshBattery ignored — not connected")
            return
        }
        if (connectedFamily == DeviceFamily.WHOOP4) {
            send(CommandNumber.GET_BATTERY_LEVEL)
            return
        }
        val ops = gattOps ?: return
        val batt = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_CHAR)
        if (batt != null && (batt.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            // safeGatt: a dead binder here (radio off mid-link) tears down instead of crashing.
            safeGatt("readCharacteristic(battery)") { ops.readCharacteristicCompat(batt) }
            log("Reading standard Battery Level (0x2A19)")
        } else {
            log("Battery Level read unavailable; relying on notifications")
        }
    }

    /**
     * Arm the strap's **firmware** alarm to buzz at [epochSec] (absolute UTC seconds). The strap fires
     * at that instant even if the phone is asleep or NOOP is closed. SET_CLOCK is sent first so the
     * strap's RTC is UTC-correct (a wrong RTC fires the alarm at the wrong wall-clock time). The 4.0
     * frame is the whoop-rs 9-byte SET_ALARM_TIME body `[0x01] + u32 LE epoch + [4 zero]` (the trailing
     * pair is the haptic-mode field the official app sends). WHOOP 4.0; on 5/MG the separate REVISION_4
     * path is used.
     */
    fun armStrapAlarm(epochSec: Long) {
        if (connectedFamily == DeviceFamily.WHOOP5) {
            // 5/MG SET_ALARM_TIME is REVISION_4 (the strap arms its own RTC alarm + fires the wake
            // haptic itself). EXPERIMENTAL/UNCONFIRMED on our side — gated behind the Experimental
            // probes opt-in so a normal user can't rely on an alarm that might silently not fire.
            // The strap maintains its RTC from the connect handshake / history sync, so no SET_CLOCK
            // here.
            if (!PuffinExperiment.from(context).isEnabled) {
                log("Alarm: 5/MG firmware alarm needs the Experimental toggle (unconfirmed) — not armed")
                return
            }
            sendCommand(CommandNumber.SET_ALARM_TIME, withResponse = false) { s ->
                RustCodec.alarmSetFrame(s, epochSec * 1000L)
            }
            recordAlarmArm(epochSec)
            log(if (_state.value.connected) "Alarm: armed 5/MG rev4 EXPERIMENTAL (epoch $epochSec)"
                else "Alarm: queued 5/MG rev4 EXPERIMENTAL (epoch $epochSec) — strap not connected")
            // Arm READBACK (noop-tan diagnostic): mirror the 4.0 GET_ALARM_TIME probe onto 5/MG so a
            // "didn't buzz" export can decide H2 (arm sent, strap kept nothing) vs H3 (strap NAKed the
            // rev-4 arm) instead of guessing. GET_ALARM_TIME is a READ (non-destructive); the 5/MG
            // response layout is unverified, so handleFrame logs the RAW readback frame (never a decode)
            // and nothing gates on it. Needs GET_ALARM_TIME on the 5/MG send allow-list (see send()).
            send(CommandNumber.GET_ALARM_TIME, byteArrayOf(0x01))
            return
        }
        sendSetClockBothForms()
        sendCommand(CommandNumber.SET_ALARM_TIME, withResponse = false) { s ->
            RustCodec.alarmSetFrameGen4(s, epochSec)
        }
        recordAlarmArm(epochSec)
        // Only claim "armed" when the strap is connected (the send actually went out); otherwise it's
        // queued and re-sent on the next connect.
        if (_state.value.connected) log("Alarm: armed (epoch $epochSec)")
        else log("Alarm: queued (epoch $epochSec) — strap not connected; will send on next connect")
        // Arm READBACK: ask the strap what it now has armed (GET_ALARM_TIME, cmd 67) so the strap log
        // carries armed + strap-reports + fired as one decidable sequence. WHOOP 4.0 ONLY (this branch):
        // the 5/MG puffin readback semantics are unverified. Log-only: handleFrame parses the cmd-67
        // COMMAND_RESPONSE defensively ([whoop4ArmedAlarmEpoch]) and NEVER gates behaviour on it (the 4.0
        // response layout is undocumented; unparseable replies log raw hex).
        send(CommandNumber.GET_ALARM_TIME, byteArrayOf(0x01))
    }

    /** Persist the last alarm arm for the debug export's Alarm block (sent epoch + when + whether the
     *  strap was connected when we sent it), so a "didn't buzz" report shows sent-vs-strap-reports. */
    private fun recordAlarmArm(sentEpoch: Long) {
        runCatching {
            val editor = NoopPrefs.of(context).edit()
                .putLong("alarm.lastArmSentEpoch", sentEpoch)
                .putLong("alarm.lastArmAt", System.currentTimeMillis())
                .putBoolean("alarm.lastArmConnected", _state.value.connected)
            // Live HR at arm, a free read logged only to test whether the strap's OWN sleep/rest state
            // (not anything sent) gates the physical haptic. Absent key = no HR had streamed yet at arm.
            val hr = _state.value.heartRate
            if (hr != null) editor.putInt("alarm.lastArmHeartRate", hr) else editor.remove("alarm.lastArmHeartRate")
            editor.apply()
        }
    }

    /** Clear the strap's firmware alarm. */
    fun disableStrapAlarm() {
        if (connectedFamily == DeviceFamily.WHOOP5) {
            // 5/MG DISABLE_ALARM is REVISION_2 [0x02, 0xFF]. Sent unconditionally (clearing is safe
            // even if arming was gated off — a no-op on a strap with no alarm set).
            sendCommand(CommandNumber.DISABLE_ALARM, withResponse = false) { s ->
                RustCodec.alarmDisableFrame(s)
            }
            log("Alarm: disarmed (5/MG rev2)")
            return
        }
        send(CommandNumber.DISABLE_ALARM, byteArrayOf(0x01))
        log("Alarm: disarmed")
    }

    // ====================================================================================
    // MARK: Scanning
    // ====================================================================================

    /** Persist the WHOOP family that actually advertised so a later launch/scan starts on the right
     *  service — what makes a one-time fallback rotation stick. Self-contained in the shared noop_prefs
     *  store; failures are non-fatal (the rotation still worked this session). */
    private fun persistSelectedModel(model: WhoopModel) {
        try {
            context.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE)
                .edit().putString("noop.selectedWhoopModel", model.name).apply()
        } catch (t: Throwable) {
            log("Couldn't persist selected model: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanFallbackRunnable)
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            // Adapter may have been turned off underneath us; nothing to clean up.
            log("stopScan threw: ${t.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "unknown"
            // Multi-WHOOP present-scan (Add-a-device wizard, MW-4): accumulate the strap, do NOT
            // auto-connect, and return before touching the connect flow. Only reachable when the wizard
            // turned on [scanningForList] via scanForWhoops(); on the default path this branch is skipped
            // entirely and the auto-connect code below runs exactly as before.
            if (scanningForList) {
                val addr = device.address ?: return
                // Read the family from the advertised service so the merged all-families scan can tell a
                // 4.0 apart from a 5/MG; a single-family scanForWhoops() only ever matches one service, so
                // this resolves the same family it was filtered on.
                val advertised = WhoopModel.fromServiceUuids(result.scanRecord?.serviceUuids?.map { it.uuid })
                val list = _discoveredWhoops.value.toMutableList()
                val i = list.indexOfFirst { it.address == addr }
                // A scan-response-only callback can arrive with no service UUID after the family was already
                // resolved from the advert; don't let it wipe a known family — keep the earlier one.
                val family = advertised ?: (if (i >= 0) list[i].family else null)
                val item = DiscoveredWhoop(address = addr, name = name.takeIf { it != "unknown" }, rssi = result.rssi, family = family)
                if (i >= 0) list[i] = item else list.add(item)   // refresh RSSI / append
                _discoveredWhoops.value = list
                return
            }
            // Multi-WHOOP preferred-peripheral filter (MW-2): when the app has pinned a specific strap,
            // ignore any OTHER discovered WHOOP and keep scanning. When [preferredAddress] is null (the
            // single-WHOOP default) this guard is skipped and the original "connect to the first
            // discovered" path below is byte-for-byte unchanged.
            val preferred = preferredAddress
            if (preferred != null && !device.address.equals(preferred, ignoreCase = true)) {
                log("Discovered $name (${device.address}) — not the preferred strap; ignoring")
                return
            }
            log("Discovered $name (rssi ${result.rssi}) — connecting")
            // Found it: cancel the not-found timeout AND the family-rotation fallback, then reflect
            // progress in the UI.
            handler.removeCallbacks(scanTimeoutRunnable)
            handler.removeCallbacks(scanFallbackRunnable)
            // Persist the family that actually advertised so the next scan starts on the right service —
            // this is what makes a one-time rotation stick after a stale-preference reconnect.
            persistSelectedModel(selectedModel)
            _state.update { it.copy(statusNote = "Found $name, connecting…") }
            // Stop scanning, then connect to this peripheral.
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan failed: $errorCode")
        }
    }

    /** Does the OS still hold [address]'s ACL? getConnectedDevices returns a band the OS keeps
     *  GATT-connected — co-resident with the official WHOOP app — even after it stops advertising, so this
     *  is the "contended, not out of range" signal. Model-agnostic (4.0 + 5.0), matched by exact address.
     *  Fails SAFE to false (→ normal attempt-count escalation) on any lookup issue, so a detection gap can
     *  never be worse than before. */
    @SuppressLint("MissingPermission")
    private fun isStrapAclHeld(address: String): Boolean = try {
        bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
            ?.any { it.address.equals(address, ignoreCase = true) } == true
    } catch (se: SecurityException) {
        false
    }

    /** The OS-bonded 5/MG-family strap, if any (name "WHOOP …" but not "WHOOP 4…" — MG-named units
     *  match too). Fails open to a scan on any lookup problem.
     *
     * A WHOOP 5/MG the OS already holds GATT-connected. Android multiplexes one ACL across GATT clients, so
     * a band connected to another app is still returned by getConnectedDevices even after it stops
     * advertising, and a client can attach to it without a scan. Uses the same 5/MG name filter and
     * multi-strap pin selection as [bondedWhoopDevice]; a WHOOP 4 is excluded and left to the scan. */
    @SuppressLint("MissingPermission")
    private fun getConnectedWhoopDevice(): BluetoothDevice? = try {
        val connected = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.filter { d ->
            val n = try { d.name } catch (se: SecurityException) { null } ?: return@filter false
            n.startsWith("WHOOP", ignoreCase = true) && !n.startsWith("WHOOP 4", ignoreCase = true)
        }.orEmpty()
        val preferred = preferredAddress
        if (preferred != null) connected.firstOrNull { it.address.equals(preferred, ignoreCase = true) }
        else connected.firstOrNull()
    } catch (se: SecurityException) {
        null
    }

    private fun bondedWhoopDevice(): BluetoothDevice? = try {
        val bonded = adapter?.bondedDevices?.filter { d ->
            val n = try { d.name } catch (se: SecurityException) { null } ?: return@filter false
            n.startsWith("WHOOP", ignoreCase = true) && !n.startsWith("WHOOP 4", ignoreCase = true)
        }.orEmpty()
        // With a multi-WHOOP pin set, take ONLY the pinned strap — never just "the first bonded 5/MG".
        // Grabbing the first ignored the active-device selection and kept the link on the wrong strap.
        // No pin (single-WHOOP) → first, unchanged.
        val preferred = preferredAddress
        if (preferred != null) bonded.firstOrNull { it.address.equals(preferred, ignoreCase = true) }
        else bonded.firstOrNull()
    } catch (se: SecurityException) {
        null
    }

    /** True while a bonded-device fast-path connect is in flight and no session has been reached —
     *  deliberately NOT in reset() (it must survive into handleDisconnect's stale-bond fallback). */
    private var bondedDirectAttempt = false

    /** Consecutive OS-bonded direct-connect attempts that died before reaching a real bond. Two in a
     *  row = the strap genuinely wiped its pairing (firmware reset / official WHOOP app re-bond), not a
     *  one-off transient drop — gates the in-app reconnect guide so a single flaky disconnect doesn't
     *  nag the user. Reset to 0 on any genuine bond. */
    private var staleDirectFailures = 0

    /** Consecutive involuntary reconnect attempts, feeding the capped-exponential [ReconnectBackoff]
     *  so a strap that's genuinely out of range stops hammering BLE. Bumped per scheduled reconnect;
     *  reset to 0 on STATE_CONNECTED and on an explicit user Connect. @Volatile because the GATT
     *  callbacks (where it's read/reset) land on binder-pool threads on API 26/27. */
    @Volatile
    private var failedReconnectAttempts = 0

    /** Bump the attempt counter and return the next backoff delay. Called from the disconnect path. */
    private fun nextReconnectDelayMs(): Long {
        failedReconnectAttempts++
        return ReconnectBackoff.nextDelayMs(failedReconnectAttempts)
    }

    /** Clear the backoff so the next reconnect starts back at the 3s base — fired on a successful
     *  connect and on an explicit user-driven Connect (which must not inherit an accumulated delay). */
    fun resetReconnectBackoff() {
        failedReconnectAttempts = 0
    }

    // Make the involuntary-reconnect timer CANCELLABLE so a stale backoff reconnect can't fire after the
    // link is already back and tear down the live connection.
    /** The pending involuntary-reconnect timer, if one is scheduled. Held as a field (NOT an inline
     *  lambda) so a real (re)connect or an explicit user Connect can CANCEL it, and so its body can
     *  no-op if we're already back — otherwise a stale backoff reconnect fires AFTER the link returns
     *  and tears the live connection down (reset+close) or starts a redundant scan. */
    @Volatile
    private var pendingReconnectRunnable: Runnable? = null

    /** Schedule an involuntary reconnect [action] after [delayMs], replacing any already-pending timer.
     *  When it fires the action is skipped if we've been told to stop (intentional teardown / bond-loop
     *  pause) OR we're already connected-or-connecting — a stale timer must never tear down a live link. */
    private fun scheduleReconnect(delayMs: Long, action: () -> Unit) {
        cancelPendingReconnect()
        val r = Runnable {
            pendingReconnectRunnable = null
            // A timer in flight when the give-up trips must not fire an extra attempt.
            if (intentionalDisconnect || autoReconnectPausedForBondLoop) return@Runnable
            // A reconnect that fires AFTER we've re-linked (user Connect / radio-on beat the timer) must
            // not reset+close the live connection or start a redundant scan. handleDisconnect nulls `gatt`
            // and sets connected=false BEFORE scheduling, so a genuinely-disconnected state still proceeds.
            if (gatt != null || _state.value.connected) return@Runnable
            action()
        }
        pendingReconnectRunnable = r
        handler.postDelayed(r, delayMs)
    }

    /** Cancel any pending involuntary reconnect — a real (re)connect superseded it. */
    private fun cancelPendingReconnect() {
        pendingReconnectRunnable?.let { handler.removeCallbacks(it) }
        pendingReconnectRunnable = null
    }

    /** Run [action] after [delayMs], but ONLY if the SAME continuous connection is still up when it fires.
     *  A reconnect (or bond-loop cycle) bumps [connectGeneration], so a transient cycle-connect can't
     *  satisfy the guard even though the device address is identical across cycles. Used by the
     *  STATE_CONNECTED re-pair-guide clear — clear the guide only once the link proves it survived the
     *  bond-loop's quick-timeout window. */
    private fun runIfConnectionSurvives(delayMs: Long, action: () -> Unit) {
        val gen = connectGeneration
        handler.postDelayed({
            if (_state.value.connected && connectGeneration == gen) action()
        }, delayMs)
    }

    /** Clear the pairing-hint streak + any published hint for a FRESH user-initiated Connect. Kept off
     *  the involuntary-reconnect path on purpose: the streak must SURVIVE automatic reconnects (like the
     *  [pinnedBondRefusals] counter) so it can accumulate to the threshold across the strap dropping and
     *  re-bonding. Only an explicit user tap starts it over; a thin wrapper over [clearPairingHint]. */
    fun clearPairingHintForUserConnect() = clearPairingHint()

    /** Bonded-handshake watchdog: every other connect phase has a timeout (scan; MTU fallback;
     *  keep-alive) but the post-discovery bond/CCCD handshake had none — so a WHOOP 4.0 that wedges
     *  in "finishing secure handshake" (OnePlus Nord 2) never bounced, and keep-alive recovery bails
     *  before [didBond]. This bonded-INDEPENDENT watchdog bounces the link if no genuine bond lands
     *  within its window. The window ESCALATES per consecutive bounce ([bondWatchdogBackoff]) so a
     *  slow-but-healthy bond gets more time, and after a capped number of bounces we stop bouncing (see
     *  [onBondWatchdog]). Armed when service discovery starts; cancelled on bond and in reset/teardown. */
    private val bondWatchdogRunnable = Runnable { onBondWatchdog() }

    @SuppressLint("MissingPermission")
    private fun onBondWatchdog() {
        // Already bonded (or torn down) — nothing wedged; the cancel sites normally beat us here, but
        // a late post on a binder-pool thread could still fire, so re-check before bouncing.
        if (didBond || gatt == null) return
        // Count this bounce. If it crosses the cap, the handshake is genuinely stuck (a slow-but-healthy
        // bond would have landed inside one of the escalating windows by now), so STOP bouncing: a WHOOP
        // 4.0 that connects but never finishes the bond would otherwise loop forever (bond → 7s →
        // disconnect status 0x16 → reconnect → bond → 7s…, and STATE_CONNECTED zeroes the reconnect
        // backoff every cycle so nothing ever backs off). Instead pause auto-reconnect and surface the
        // same re-pair guide the stale-bond paths show ([handleDisconnect] already honours the pause), so
        // the battery stops draining. A user Connect or a genuine bond re-arms it via
        // [bondWatchdogBackoff].reset().
        val gaveUp = bondWatchdogBackoff.recordBounce()
        intentionalDisconnect = false
        if (gaveUp) {
            log("Bond handshake never completed after ${bondWatchdogBackoff.consecutiveBounces} escalating tries — pausing auto-reconnect and surfacing the re-pair guide")
            autoReconnectPausedForBondLoop = true
            bondLoopPausedAtMs = System.currentTimeMillis()   // the salvage probe covers this pause too
            if (_state.value.reconnectGuide == null) {
                _state.update { it.copy(
                    reconnectGuide = """
                    Your strap connects but never finishes pairing with NOOP. This is almost always a stale Bluetooth pairing, usually after a WHOOP firmware update, or the official WHOOP app holding the strap. NOOP works fine once it's re-paired:

                    1. Quit the official WHOOP app (or turn off Bluetooth on that phone).
                    2. Open Settings → Bluetooth, find your WHOOP, and Forget / Unpair it.
                    3. Tap the band repeatedly until its LEDs flash blue (pairing mode).
                    4. Come back here and tap Connect.
                    """.trimIndent()
                ) }
            }
        } else {
            log("Bond handshake stuck for ${bondWatchdogBackoff.currentWindowMs() / 1000}s — bouncing link to retry (attempt ${bondWatchdogBackoff.consecutiveBounces})")
        }
        // Drop the link either way: even on give-up we tear down the wedged GATT so it stops holding the
        // radio. gatt.disconnect() throwing on a dead binder must not crash from a timer — fall through
        // to a clean teardown if it does (mirrors the keep-alive bounce). When we gave up above,
        // [handleDisconnect] takes its paused branch and schedules NO reconnect; otherwise it backoff-
        // reconnects and [armBondWatchdog] arms the NEXT (wider) window from [bondWatchdogBackoff].
        try {
            gatt?.disconnect()   // → handleDisconnect → reset() (cancels this) → (paused | backoff reconnect)
        } catch (t: Throwable) {
            log("bond watchdog bounce: gatt.disconnect() threw ${t.javaClass.simpleName}; tearing down")
            teardownAfterGattFailure()
        }
    }

    private fun armBondWatchdog() {
        handler.removeCallbacks(bondWatchdogRunnable)
        // Escalating window — base 7s on the first handshake, wider on each subsequent bounce, so a
        // slow-but-healthy WHOOP 4.0 bond isn't bounced forever at a too-tight window. The 0-bounce
        // window equals BOND_WATCHDOG_MS, so the common first connect is unchanged.
        handler.postDelayed(bondWatchdogRunnable, bondWatchdogBackoff.currentWindowMs())
    }

    private fun cancelBondWatchdog() {
        handler.removeCallbacks(bondWatchdogRunnable)
    }

    // MARK: Multi-WHOOP stale-pin recovery. When a pinned strap keeps refusing the encrypted bond but a
    // DIFFERENT WHOOP bonded fine this run, hand the pin to the working strap rather than looping forever
    // on the dead pin (which would also leave buzz/haptics dead, since they gate on encryptedBond).

    /** Address of the last strap that reached a GENUINE encrypted bond this run — the live working strap
     *  the registry pin should point at if the pinned one keeps refusing. Null until anything bonds.
     *  @Volatile: written from the GATT bond callback (binder-pool thread on API 26/27). */
    @Volatile
    private var lastBondedAddress: String? = null

    /** Consecutive INSUFFICIENT_AUTH/ENCRYPTION bond refusals on the CURRENTLY PINNED strap. A stale pin
     *  (pointing at a strap bonded elsewhere / not really here) makes [connect] drop the strap that DOES
     *  bond and loop on the dead pin. Counted here; cleared by any genuine bond. @Volatile — same thread
     *  rationale as above. */
    @Volatile
    private var pinnedBondRefusals = 0

    /** Consecutive WHOOP 5/MG encrypted-bond refusals this session, with NO genuine bond reached yet.
     *  Distinct from [pinnedBondRefusals] (which is about a stale multi-WHOOP registry pin): this one
     *  drives the user-facing pairing hint. A 5/MG that's still bonded to the official WHOOP app
     *  keeps refusing the just-works bond, so after two refusals we surface concrete pairing-mode
     *  guidance. Reset to 0 on a genuine bond and on a fresh user-initiated connect. @Volatile — written
     *  from the GATT bond callback (binder-pool thread on API 26/27). */
    @Volatile
    private var bondRefusalStreak = 0

    /** After the bond is refused persistently, pause auto-reconnect (stop hammering) and write a one-line
     *  epitaph. Fed by the SAME refusal events as [bondRefusalStreak]; its higher give-up threshold fires
     *  once the pairing hint has had several cycles to be acted on. Reset on a genuine bond or an
     *  explicit user reconnect. */
    private val bondGiveUp = BondRefusalGiveUp()

    /** True while auto-reconnect is PAUSED by [bondGiveUp]. handleDisconnect consults this and skips
     *  scheduling a reconnect; a manual connect()/disconnect() clears it via [bondGiveUp].reset(). @Volatile
     *  because it's written from the GATT bond callback and read on the reconnect path. */
    @Volatile
    private var autoReconnectPausedForBondLoop = false

    /** When the bond-loop pause last tripped (or last salvage-probed), epoch ms. Drives the salvage
     *  probe's floor ([BOND_LOOP_SALVAGE_FLOOR_MS] via [shouldSalvageProbe]): a paused strap the user has
     *  since FREED self-heals on the next app-foreground instead of staying disconnected until a manual
     *  Connect, while a still-held strap gets at most one bounded attempt per foreground per floor
     *  window. Set wherever the pause trips; null whenever the pause clears. Never persisted. */
    @Volatile
    private var bondLoopPausedAtMs: Long? = null

    /** A genuine bond this run: [address] is a live working strap (re-adopt target), and a bond proves no
     *  stale pin is wedging us — so clear the refusal streak. */
    private fun noteGenuineBond(address: String?) {
        if (address != null) lastBondedAddress = address
        pinnedBondRefusals = 0
    }

    /** Count an encrypted-bond refusal IF it happened on the pinned strap, and once the streak reaches
     *  [PIN_BOND_REFUSAL_LIMIT] hand the pin to a different strap that bonded fine this run. [status] must
     *  be an insufficient-auth/encryption GATT code; other failures (BUSY, etc.) don't implicate the pin.
     *  No-op on the single-WHOOP path ([preferredAddress] null). */
    @SuppressLint("MissingPermission")
    private fun noteBondRefusalIfPinned(failedAddress: String?, status: Int) {
        if (!isInsufficientAuthStatus(status)) return
        if (didBond) return   // a refusal AFTER we already bonded this run isn't a stale-pin signal
        val pinned = preferredAddress ?: return                 // single-WHOOP: nothing to re-adopt
        if (failedAddress == null || !failedAddress.equals(pinned, ignoreCase = true)) return
        pinnedBondRefusals++
        log("Multi-WHOOP: pinned strap $pinned refused the encrypted bond (status=$status, refusal $pinnedBondRefusals/$PIN_BOND_REFUSAL_LIMIT)")
        val working = lastBondedAddress
        if (pinnedBondRefusals >= PIN_BOND_REFUSAL_LIMIT && working != null && !working.equals(pinned, ignoreCase = true)) {
            readoptWorkingStrap(working = working, awayFrom = pinned)
        }
    }

    /** Break out of the dead-pin loop and re-adopt the live-bonding [working] strap, away from the pinned
     *  [awayFrom] one that keeps refusing the encrypted bond. Clears [preferredAddress] so the scan stops
     *  filtering to the dead strap — [working] is then eligible — and drops the dead-pin link so the
     *  auto-rescan reconnects. On reconnect, STATE_CONNECTED republishes the strap's address on the
     *  [connectedPeripheralAddress] seam the SourceCoordinator observes, so the registry's identity
     *  adoption runs through its normal first-connect path. */
    @SuppressLint("MissingPermission")
    private fun readoptWorkingStrap(working: String, awayFrom: String) {
        log("Multi-WHOOP: pinned strap $awayFrom unreachable after $pinnedBondRefusals bond refusals — re-adopting the live strap $working")
        pinnedBondRefusals = 0
        // Drop the dead pin so onScanResult no longer ignores every OTHER WHOOP. The app re-asserts a pin
        // from the registry on the next active-device change; until then any bonded WHOOP is acceptable
        // (the single-WHOOP default), which is exactly the recovery we want — [working] can now connect.
        preferredAddress = null
        lastDevice = null   // don't fast-path reconnect to the dead-pin handle; rescan picks the working strap
        // Bonding the dead-pin link is still in teardown here, so route through the normal scan-based
        // connect — onScanResult (pin now null) connects to the working strap when it advertises.
        resetReconnectBackoff()   // a deliberate re-adopt, not an out-of-range retry — start fresh
        intentionalDisconnect = false
        try {
            gatt?.disconnect()   // drop the dead-pin link → handleDisconnect → rescan (pin cleared)
        } catch (t: Throwable) {
            log("re-adopt: gatt.disconnect() threw ${t.javaClass.simpleName}; tearing down")
            teardownAfterGattFailure()
        }
    }

    /** True for the GATT statuses that mean the strap refused the encrypted bond:
     *  INSUFFICIENT_AUTHENTICATION (5) and INSUFFICIENT_ENCRYPTION (15). */
    private fun isInsufficientAuthStatus(status: Int): Boolean =
        status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION

    /** Count a WHOOP 5/MG encrypted-bond refusal toward the pairing-hint streak and, once it reaches
     *  [BOND_REFUSAL_HINT_THRESHOLD] with no genuine bond yet this session, publish concrete pairing-mode
     *  guidance. WHOOP 4 always reaches a genuine bond, so this is 5/MG-only. Independent of the
     *  multi-WHOOP pin recovery in [noteBondRefusalIfPinned], which is left untouched. The guidance is
     *  mirrored into [statusNote] (already rendered on the Live screen) so it surfaces with no UI change. */
    private fun noteBondRefusalForPairingHint(status: Int, failedAddress: String?) {
        if (!isInsufficientAuthStatus(status)) return
        if (didBond) return                                       // already bonded — not a pairing problem
        if (connectedFamily != DeviceFamily.WHOOP5) return        // WHOOP 4 bonds cleanly; hint is 5/MG-only
        bondRefusalStreak++
        if (bondGiveUp.gaveUp) {
            // A refusal during a paused-state salvage probe must not stomp the paused hint back to the
            // pairing hint (or flap the banner per probe). The streak keeps counting silently;
            // recordRefusal() below stays false (latched), so no epitaph spam either.
            log("WHOOP 5/MG: bond still refused during a paused-state probe (streak $bondRefusalStreak) - the give-up stays latched")
        } else if (bondRefusalStreak >= BOND_REFUSAL_HINT_THRESHOLD) {
            // Re-assert BOTH the canonical hint and the statusNote mirror on every over-threshold refusal.
            // STATE_CONNECTED clears statusNote on each reconnect, so a once-only set would leave the Live
            // status blank after a reconnect — re-asserting keeps the already-rendered surface in sync.
            if (_state.value.pairingHint == null) {
                log("WHOOP 5/MG: encrypted bond refused $bondRefusalStreak times — surfacing pairing guidance")
            }
            _state.update { it.copy(pairingHint = PAIRING_HINT_TEXT, statusNote = PAIRING_HINT_TEXT) }
        }
        // Feed the same refusal into the give-up tracker. Once it crosses the higher threshold (the
        // pairing hint has had several cycles to be acted on), pause auto-reconnect so we stop hammering
        // a strap that can't bond, write the one-line epitaph (opaque hashed id only, no PII), and
        // surface the honest paused hint. A genuine bond or a manual reconnect re-arms it.
        if (bondGiveUp.recordRefusal()) {
            autoReconnectPausedForBondLoop = true
            bondLoopPausedAtMs = System.currentTimeMillis()   // starts the salvage-probe floor
            val opaque = BondRefusalGiveUp.opaqueId(failedAddress ?: "device")
            log(BondRefusalGiveUp.epitaphLine(bondGiveUp.refusals, opaque))
            _state.update { it.copy(pairingHint = BondRefusalGiveUp.pausedHint()) }
            if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
                log("bond gaveUp refusals=${bondGiveUp.refusals} id=$opaque (auto-reconnect paused)",
                    com.noop.testcentre.TestDomain.CONNECTION)
            }
        }
    }

    /** Clear the pairing-hint streak + published hint after a genuine bond or a fresh connect. Also clears
     *  the mirrored [statusNote] only when it still carries the hint, so we never wipe an unrelated note. */
    private fun clearPairingHint() {
        bondRefusalStreak = 0
        // A genuine bond or a fresh user connect re-arms auto-reconnect and clears the give-up.
        bondGiveUp.reset()
        // A genuine bond or a fresh user connect also clears the bond-watchdog bounce streak, so the
        // next slow handshake starts back at the tight base window and can escalate afresh.
        bondWatchdogBackoff.reset()
        autoReconnectPausedForBondLoop = false
        bondLoopPausedAtMs = null
        if (_state.value.pairingHint != null) {
            _state.update {
                val clearedNote = if (it.statusNote == PAIRING_HINT_TEXT) null else it.statusNote
                it.copy(pairingHint = null, statusNote = clearedNote)
            }
        }
    }

    /** Guards the once-per-connect service-discovery kick: discovery is deferred behind an MTU request
     *  + fallback timeout, so this makes it fire EXACTLY once whichever path wins. AtomicBoolean (not
     *  @Volatile) because onMtuChanged and the fallback can race on binder-pool threads (API 26/27). */
    private val serviceDiscoveryKicked = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Last MTU value + when, to dedupe a spurious double callback: some BT stacks (OnePlus Nord 2)
     *  fire onMtuChanged TWICE with the same mtu/status; the second corrupts GATT state so every CCCD
     *  write returns BUSY forever and the bond never completes. Applied to all devices (always spurious). */
    private var lastMtuValue = -1
    private var lastMtuAtMs = 0L

    /** Start service discovery exactly once per connection, whichever path (onMtuChanged or the
     *  fallback timeout) reaches here first. Idempotent via [serviceDiscoveryKicked]. */
    @SuppressLint("MissingPermission")
    private fun kickServiceDiscovery(reason: String) {
        if (!serviceDiscoveryKicked.compareAndSet(false, true)) return
        val ops = gattOps ?: return
        log("Discovering services ($reason)")
        // Arm the bond-handshake watchdog: from here the post-discovery bond/CCCD phase runs. If
        // [didBond] is still false after BOND_WATCHDOG_MS, [onBondWatchdog] bounces the link. Cancelled
        // on bond and in reset/teardown; once-per-connection because kickServiceDiscovery is idempotent.
        armBondWatchdog()
        // safeGatt: discovery on a dead binder (radio off) tears down rather than crashing.
        safeGatt("discoverServices") { ops.discoverServicesCompat() }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, autoConnect: Boolean = false) {
        // Reset per-connection state.
        reset()
        // Remember the device so a later dropout can reconnect straight to it.
        lastDevice = device
        // A never-bonded strap has no link key, so the encrypted handshake write is refused
        // (status 5/15) and the bond watchdog bounces the link every 7s. Ask the OS to pair first —
        // this is what raises the system pairing prompt. An already-bonded strap is untouched.
        if (shouldRequestOsBond(device.bondState)) {
            log("Strap is not bonded — requesting OS pairing before the handshake")
            safeGatt("createBond") { device.createBond() }
        }
        // Close any prior/pending GATT so a direct-reconnect attempt doesn't leak the old client.
        // close() can throw on a dead binder; swallow it — we're replacing the handle anyway.
        try { gatt?.close() } catch (t: Throwable) { log("prior gatt.close() threw ${t.javaClass.simpleName} (ignored)") }
        // autoConnect=false → a fast, direct connect, used for the scan-discovered first connect.
        // autoConnect=true → the OS reconnects whenever the bonded strap is reachable WITHOUT needing
        // an advertisement (used by the dropout auto-reconnect). TRANSPORT_LE pins to BLE on dual-mode devices.
        gatt = when {
            // Pin EVERY GATT callback to the main looper (API 28+, where thread affinity is reliable) —
            // otherwise callbacks race across binder threads, the bond write can beat the notify
            // subscriptions, and every writeDescriptor wedges BUSY forever, leaving HR/battery/events dead.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                device.connectGatt(
                    context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK, handler,
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else ->
                device.connectGatt(context, autoConnect, gattCallback)
        }
        gattOps = gatt?.let { gattOpsFactory(it) }
    }

    // ====================================================================================
    // MARK: GATT callback
    // ====================================================================================

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Mark connected, negotiate a larger ATT MTU, THEN discover.
                    handler.removeCallbacks(scanTimeoutRunnable)
                    // A real link is up — cancel any pending involuntary reconnect so a stale backoff
                    // timer can't fire and reset+close this connection.
                    cancelPendingReconnect()
                    // Clears the reconnect backoff immediately (not behind a survival dwell): a band the
                    // OS still holds bonded/ACL-connected can ONLY be recovered via the fast DIRECT
                    // reconnect — a dwell-gated PASSIVE autoConnect stalls on it, freezing the whole link.
                    resetReconnectBackoff()
                    // Clear the stale-bond re-pair guide on connect, UNLESS in a known bond-loop (where the
                    // strap "connects" every ~3s then times out, so clearing here flash-vanishes the guide).
                    // Keep it until THIS connection survives the loop's quick-timeout window, or a clean teardown.
                    val keepGuide = postBondLoop.tripped
                    _state.update { it.copy(
                        connected = true, advertisingName = g.device.name, scanning = false,
                        statusNote = null, encryptedBond = false,
                        reconnectGuide = if (keepGuide) it.reconnectGuide else null,
                    ) }
                    connectGeneration += 1
                    if (keepGuide) {
                        // Clear the guide only if the SAME continuous connection survives the window (a
                        // reconnect/loop cycle bumps connectGeneration, so a transient cycle-connect can't
                        // satisfy the guard even though the device address is identical across cycles).
                        runIfConnectionSurvives(postBondLoop.quickTimeoutWindowMs + 1_000L) {
                            postBondLoop.reset()        // survived the window → the bond-loop is resolved
                            _state.update { it.copy(reconnectGuide = null) }
                        }
                    }
                    // Multi-WHOOP: publish the connected strap's stable BLE address so SourceCoordinator can
                    // adopt it onto the active registry device's peripheralId on first connect. Decoupled
                    // from the registry — the coordinator observes this; the connect flow below is unchanged.
                    _connectedPeripheralAddress.value = g.device.address
                    // Connection test mode: report the connect latency + the uptime-start marker the readout
                    // reads. Gated zero-cost (the CONNECTION bool is read before any string is built).
                    // Behaviour-neutral diagnostics only - the connect flow below is unchanged.
                    if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
                        val nowUnix = System.currentTimeMillis() / 1000L
                        val latencyMs = connectAttemptStartedAtMs?.let { System.currentTimeMillis() - it }
                        log("connect up gen=$connectGeneration latencyMs=${latencyMs ?: "?"} uptimeStart=$nowUnix",
                            com.noop.testcentre.TestDomain.CONNECTION)
                    }
                    serviceDiscoveryKicked.set(false)
                    // Capture link signal strength — the scan "Discovered … (rssi …)" line never fires on
                    // a direct/auto-reconnect, so a weak-link sync is otherwise undiagnosable. Deferred
                    // past requestMtu so a stray early read can't make requestMtu return false (MTU skipped).
                    // safeGatt: a late RSSI read can land just after the radio went off — guard it.
                    handler.postDelayed({
                        gattOps?.let { safeGatt("readRemoteRssi") { it.readRemoteRssiCompat() } }
                    }, RSSI_READ_DELAY_MS)
                    // Request the larger MTU BEFORE discovery/subscribe so the offload isn't capped at
                    // 20-byte notifications. Discovery is gated on the result with a fallback timeout, so
                    // a stack that ignores requestMtu can't stall the connect.
                    val mtuOps = gattOps
                    val mtuOk = mtuOps != null &&
                        safeGatt("requestMtu") { mtuOps.requestMtuCompat(GATT_MTU) }
                    if (mtuOk) {
                        log("Connected — requesting MTU $GATT_MTU before discovery")
                        handler.postDelayed({ kickServiceDiscovery("mtu timeout") }, MTU_FALLBACK_MS)
                    } else if (gatt != null) {
                        // requestMtu returned false (stack ignored it) but the link is still alive —
                        // discover directly. If safeGatt tore down (dead binder), gatt is null: skip.
                        kickServiceDiscovery("requestMtu rejected")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Tear down, then auto-rescan unless intentional.
                    handleDisconnect(status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // Dedupe the OnePlus Nord 2 double-MTU bug: it fires onMtuChanged TWICE with the SAME
            // mtu/status, and the spurious second call re-enters discovery and wedges every CCCD write
            // BUSY forever. Applied to every device — a same-value re-callback in the window is always spurious.
            val now = System.currentTimeMillis()
            if (now - lastMtuAtMs < DUPLICATE_MTU_WINDOW_MS && mtu == lastMtuValue) {
                log("Ignoring duplicate MTU callback (mtu=$mtu) — OnePlus/spurious")
                return
            }
            lastMtuValue = mtu
            lastMtuAtMs = now
            // Whatever the strap granted (≤ requested). Log it, then discover. kickServiceDiscovery is
            // idempotent, so a late callback after the fallback timeout already fired is a no-op.
            log("MTU negotiated: $mtu (status=$status)")
            kickServiceDiscovery("mtu=$mtu")
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            // Signal strength at connect — diagnoses weak-link syncs (drops/busy storms/timeouts) that
            // otherwise look mysterious in the log. Only on a clean read; a failure just stays silent.
            if (status == BluetoothGatt.GATT_SUCCESS) log("Signal: RSSI $rssi dBm")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }
            // Android delivers ALL services+characteristics in one callback, so we walk them
            // directly rather than in two passes.

            // 1. Custom service: capture the cmd-write char, FIRE THE BOND, queue the notify subs.
            val whoop4 = g.getService(WHOOP4_SERVICE)
            val whoop5 = g.getService(WHOOP5_SERVICE)
            if (whoop4 != null) {
                // Verified WHOOP 4.0 path: capture the cmd-write char + queue the notify subscriptions.
                // Do NOT fire the bond write here — Android allows only ONE outstanding GATT op, so writing
                // it now would race the CCCD writes below and bond while leaving notifications unsubscribed.
                connectedFamily = DeviceFamily.WHOOP4
                cmdCharacteristic = whoop4.getCharacteristic(CMD_WRITE_CHAR)
                whoop4.getCharacteristic(CMD_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
                whoop4.getCharacteristic(EVENT_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
                whoop4.getCharacteristic(DATA_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
            } else if (whoop5 != null) {
                // EXPERIMENTAL WHOOP 5.0/MG: opens with CLIENT_HELLO (sent in startSession, after the
                // standard HR/battery notifications are enabled), not the WHOOP4 confirmed-write bond.
                connectedFamily = DeviceFamily.WHOOP5
                log("WHOOP 5/MG detected — will send CLIENT_HELLO after subscribing (experimental).")
                _state.update { it.copy(
                    whoop5Detected = true,
                    statusNote = "WHOOP 5/MG connected - experimental. After bonding, NOOP brings up live " +
                        "heart rate from the strap's realtime stream. Deeper metrics (recovery, strain, " +
                        "sleep) for 5/MG are still being figured out. WHOOP 4.0 is fully supported today.",
                ) }
                cmdCharacteristic = whoop5.getCharacteristic(WHOOP5_CMD_WRITE_CHAR)
            } else {
                log("Custom WHOOP service not found on this peripheral")
            }
            // The reassembler frames per family — 5/MG uses a different length encoding (declLen @[2..4],
            // total +8) than WHOOP4 (length @[1..3], total +4), so it must match the connected strap.
            reassembler = Reassembler(connectedFamily)

            // 2. Standard HR profile (works unbonded — the reliable HR + R-R source).
            g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_CHAR)?.let { cccdQueue.add(it) }

            // 3. Standard battery profile (plain %).
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_CHAR)?.let { cccdQueue.add(it) }

            // 4. The strap's own serial (0x2A25) and hardware revision (0x2A27), read — never written
            // — on a delay so they cannot take the single in-flight GATT slot from the CCCD writes or
            // the bond.
            scheduleDeviceInfoReads(0)

            // Enable notifications one at a time. When the queue is fully drained, startSession() fires
            // the first command (bond / CLIENT_HELLO) — never racing the descriptor writes.
            //
            // OnePlus Nord 2 settle: the stack is still unsettled right after service discovery, so the
            // first CCCD write races it and comes back BUSY, wedging every subscribe and the bond. Give
            // it a short beat (~450ms, inside the bond watchdog) before the first write.
            if (Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)) {
                log("OnePlus detected — settling ${ONEPLUS_CCCD_SETTLE_MS}ms before first CCCD write")
                handler.postDelayed({ gatt?.let { drainCccdQueue(it) } }, ONEPLUS_CCCD_SETTLE_MS)
            } else {
                drainCccdQueue(g)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // A CONFIRMED-write completion (no error) == bonding succeeded.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Confirmed write failed: status=$status")
                // Multi-WHOOP stale-pin recovery: INSUFFICIENT_AUTHENTICATION (5) / INSUFFICIENT_ENCRYPTION
                // (15) on the bond write means the strap refused the encrypted bond. A STALE pin on a
                // refusing strap while a DIFFERENT strap bonds fine would loop forever otherwise — hand it over.
                noteBondRefusalIfPinned(g.device.address, status)
                // Separately, count the refusal toward the user-facing pairing hint. A 5/MG still bonded
                // to the official WHOOP app keeps refusing the just-works bond; after two refusals we
                // surface concrete pairing-mode guidance. Independent of the pin recovery above.
                noteBondRefusalForPairingHint(status, g.device.address)
                // Connection test mode: surface the failed-encrypt / "held by another central" hint
                // upfront. INSUFFICIENT_AUTHENTICATION (5) / INSUFFICIENT_ENCRYPTION (15) == the strap is
                // still bonded to the official WHOOP app or a stale OS pairing. Gated zero-cost.
                if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
                    val insufficient = status == 5 || status == 15
                    log(
                        if (insufficient)
                            "otherCentral bondWrite refused=insufficient (strap likely held by the WHOOP app or a stale pairing; cannot start a fresh encrypted bond)"
                        else "otherCentral bondWrite failed=status$status",
                        com.noop.testcentre.TestDomain.CONNECTION,
                    )
                }
            } else if (!didBond && connectedFamily == DeviceFamily.WHOOP5) {
                // EXPERIMENTAL: the CLIENT_HELLO is now a confirmed write, so this ACK means just-works
                // bonding completed. Subscribe the puffin notify chars (realtime HR rides these as
                // REALTIME_DATA — rejected on the unauthenticated link), then arm realtime HR.
                didBond = true
                cancelBondWatchdog()          // genuine bond reached — the handshake watchdog stands down
                noteGenuineBond(g.device.address)   // this strap bonds fine; clears any pin-refusal streak
                clearPairingHint()            // a genuine bond means the pairing guidance no longer applies
                bondedDirectAttempt = false   // fast-path connect reached a real session
                staleDirectFailures = 0       // genuine bond — clear the wiped-bond counter
                _state.update { it.copy(bonded = true, encryptedBond = true) }   // genuine bond
                bondedAtMs = System.currentTimeMillis()   // stamp the bond so handleDisconnect can spot a bond-then-quick-timeout loop
                emitConnectionBondState("encryptedBond family=whoop5 (CLIENT_HELLO acked)")
                log("WHOOP 5/MG: CLIENT_HELLO acked — link established; subscribing notify chars (experimental).")
                g.getService(WHOOP5_SERVICE)?.let { svc ->
                    for (u in WHOOP5_NOTIFY_CHARS) svc.getCharacteristic(u)?.let { cccdQueue.add(it) }
                }
                // The 5/MG handshake tail (SET_CLOCK/GET_CLOCK + offload kick) runs when THIS CCCD drain
                // completes. Clock-before-history is mandatory: an un-clocked WHOOP 5 doesn't save sensor
                // data to flash at all, so offloads "succeed" with zero body frames.
                drainCccdQueue(g)
                // RE-DERIVE the want at arm time, never the precomputed [wantsRealtime]: that value can be
                // up to a keep-alive tick (30s) stale, and a reconnect just OUTSIDE the overnight window
                // would re-arm the stream from it and stay armed until the next tick.
                val realtimeWantNow = screenWantsRealtime
                wantsRealtime = realtimeWantNow
                if (realtimeWantNow) { realtimeArmed = true; send(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(1)) }
            } else if (!didBond && connectedFamily == DeviceFamily.WHOOP4) {
                didBond = true
                cancelBondWatchdog()          // secure handshake completed — stand the watchdog down
                noteGenuineBond(g.device.address)   // this strap bonds fine; clears any pin-refusal streak
                clearPairingHint()            // a genuine bond means the pairing guidance no longer applies
                _state.update { it.copy(bonded = true, encryptedBond = true) }   // WHOOP4 bond is genuine
                bondedAtMs = System.currentTimeMillis()   // stamp the bond so handleDisconnect can spot a bond-then-quick-timeout loop
                emitConnectionBondState("encryptedBond family=whoop4 (confirmed write acked)")
                log("BONDED (confirmed write acknowledged) — custom channels should now flow")
            }

            // Run the connect handshake EXACTLY ONCE per connection. onCharacteristicWrite re-fires on
            // EVERY with-response write (the bond write, etc.); the guard prevents re-blasting the
            // handshake at the strap mid-session. WHOOP 5.0/MG uses CLIENT_HELLO instead, so it's skipped.
            if (!connectHandshakeDone && connectedFamily == DeviceFamily.WHOOP4) {
                connectHandshakeDone = true
                noteRebootReconnectIfNeeded()
                runConnectHandshake()
            }

            // This with-response write is done; release the in-flight slot and send the next.
            writeInFlight = false
            drainWriteQueue()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Notify enable failed for ${descriptor.characteristic?.uuid}: status=$status")
            } else {
                log("Subscribed ${descriptor.characteristic?.uuid}")
                // A subscribe landed — replenish the shared BUSY-retry budget so a transient stall on
                // one characteristic can't starve the others' retries (the counter is global).
                cccdRetries = 0
            }
            // This CCCD write is done; enable the next characteristic's notifications.
            cccdInFlight = false
            drainCccdQueue(g)
        }

        // Android 13+ delivers the value as a parameter; older APIs read it off the characteristic.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== this@WhoopBleClient.gatt) return
            onInbound(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33; retained for API 26..32 where the value-bearing overload isn't called")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (g !== this@WhoopBleClient.gatt) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onInbound(characteristic.uuid, value)
        }

        // Result of an explicit readCharacteristic (refreshBattery's 0x2A19 read) — route it like a
        // notification so the existing battery handler in onInbound runs. Android 13+ passes the value.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) onInbound(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33; retained for API 26..32 where the value-bearing overload isn't called")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value?.let { onInbound(characteristic.uuid, it) }
        }
    }

    // ====================================================================================
    // MARK: Inbound routing
    // ====================================================================================

    private fun onInbound(uuid: UUID, bytes: ByteArray) {
        lastDataAtMs = System.currentTimeMillis()   // feeds the keep-alive liveness watchdog
        resubscribedSinceData = false               // data is flowing again — re-arm the one-shot resubscribe
        when {
            uuid == HEART_RATE_CHAR -> parseStandardHr(bytes)       // 0x2A37
            // 0x2A19 = percent — 5/MG ONLY. On a WHOOP 4.0 this characteristic is a stub constant 100
            // (the real value is the GET_BATTERY_LEVEL COMMAND_RESPONSE, u16/10), and it's also
            // SUBSCRIBED, so an unsolicited stub notification could flip the display back to 100.
            uuid == BATTERY_CHAR -> if (connectedFamily != DeviceFamily.WHOOP4) {
                bytes.firstOrNull()?.let { setBattery((it.toInt() and 0xFF).toDouble()) }
            } else Unit
            // 0x2A25 = the strap's own serial, a UTF-8 string. Published for the registry's identity
            // binding; a blank or unreadable value stays null so nothing is invented.
            uuid == SERIAL_NUMBER_CHAR -> {
                val serial = bytes.toString(Charsets.UTF_8).trim { it <= ' ' }
                if (serial.isNotEmpty() && _connectedStrapSerial.value != serial) {
                    _connectedStrapSerial.value = serial
                    log("Strap serial (0x2A25): $serial")
                }
            }
            // 0x2A27 = the strap's hardware revision, a UTF-8 string. Stored verbatim on the registry
            // row; a blank or unreadable value stays null so no board is inferred that was never read.
            uuid == HARDWARE_REVISION_CHAR -> {
                val rev = bytes.toString(Charsets.UTF_8).trim { it <= ' ' }
                if (rev.isNotEmpty() && _connectedStrapHardwareRev.value != rev) {
                    _connectedStrapHardwareRev.value = rev
                    log("Strap hardware revision (0x2A27): $rev")
                }
            }
            // WHOOP4 custom notify chars, OR the WHOOP 5/MG puffin notify chars (fd4b0003/4/5/7) once
            // bonded — both carry framed records (REALTIME_DATA etc.) through the family-aware reassembler.
            uuid == CMD_NOTIFY_CHAR || uuid == EVENT_NOTIFY_CHAR || uuid == DATA_NOTIFY_CHAR ||
                uuid in WHOOP5_NOTIFY_CHARS -> {
                // Reassemble (no-op for already-complete frames) then route each complete frame.
                for (frame in reassembler.feed(bytes)) {
                  // Defense-in-depth: this loop runs on the GATT binder thread; an uncaught throw from
                  // ANY frame op (handleFrame, a decoder, the inline date-format, log) would crash the
                  // whole app. Wrap the whole body so a bad frame drops ONE frame and the link stays up.
                  try {
                    // Compute the offload-frame flag ONCE — it feeds both the R22 telemetry note and
                    // handleFrame's replayedOffload gate, so evaluating it twice would bounds-check and
                    // index every offloaded frame for nothing.
                    val offloadFrame = backfilling && isOffloadFrame(frame, connectedFamily)
                    noteWhoop5R22Telemetry(frame, offloadFrame)
                    // Decode this frame ONCE and thread it to both consumers (the router below and the
                    // live collector) instead of each re-parsing it — steady-state drops 2→1 parse per
                    // live frame. Decode is whoop-rs's (the sole bytes→values decoder).
                    val parsed = RustAdapter.parseFrame(frame, connectedFamily)
                    // A frame replayed as part of the historical offload (type 47/48/… during a backfill)
                    // must not drive LIVE-only state (the charging pill), so the offload path skips the
                    // live router entirely.
                    handleFrame(frame, parsed, replayedOffload = offloadFrame)

                    // Capture the strap's newest stored record from a GET_DATA_RANGE reply, feeding the
                    // liveness watchdog. Response command byte is family-dependent: @6 on WHOOP4, @10 on
                    // 5/MG (+4 puffin envelope) — dataRangeNewestUnix's scan-from-7 stays word-aligned.
                    val cmdOff = if (connectedFamily == DeviceFamily.WHOOP5) 10 else 6
                    if (frame.size > cmdOff && (frame[cmdOff].toInt() and 0xFF) == CommandNumber.GET_DATA_RANGE.rawValue) {
                        // Dump raw GET_DATA_RANGE response bytes unconditionally (even if decode returns
                        // null) so a stale/wrong-epoch "newest" can be told apart from a frame-alignment
                        // bug in dataRangeNewestUnix, straight from a normal strap-log export.
                        val hex = frame.joinToString("") { "%02x".format(it) }
                        log("Get Data Range raw frame (for offset analysis): $hex")
                        dataRangeNewestUnix(frame)?.let {
                            strapNewestTs = it
                            // Persist the strap's newest banked record so the debug export can flag a reset clock.
                            runCatching { NoopPrefs.of(context).edit().putLong("strap.newestRecordTs", it).apply() }
                            // Flag an implausibly FUTURE "newest" (strap clock set ahead) right where it
                            // lands, so a Test Centre export shows WHY auto-continue refused the range.
                            val wallNowForSkew = System.currentTimeMillis() / 1000L
                            if (it > wallNowForSkew + AUTO_CONTINUE_FUTURE_SKEW_SECONDS) {
                                log("Strap newest banked record reads ${(it - wallNowForSkew) / 3600L}h AHEAD of the wall clock (implausible; strap clock set in the future). Auto-continue will not trust this range.")
                            }
                            // Publish the strap's banked-record window to the Backfiller so the historical
                            // ingest gate can reject a record dated months outside THIS strap's own
                            // [oldest, newest] (wandering-clock pollution past the absolute 2023-11 floor).
                            backfiller.sessionNewestUnix = it
                            // Observability for "last night didn't sync": log the NEWEST record the strap
                            // holds. With the persisted-N line, one connect distinguishes a banked-but-
                            // not-yet-reached backlog (cursor grinding) from a genuinely un-banked night.
                            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            log("Strap newest banked record: ${fmt.format(java.util.Date(it * 1000L))} (from data range)")
                            // Also surface the OLDEST banked record → the full backlog SPAN, i.e. the depth
                            // a deep oldest-first drain must cover before recent nights land.
                            val oldestUnix = dataRangeOldestUnix(frame)
                            if (oldestUnix != null && oldestUnix < it) {
                                backfiller.sessionOldestUnix = oldestUnix   // closes the session window
                                val spanDays = (it - oldestUnix) / 86_400L
                                log("Strap banked history span: ${fmt.format(java.util.Date(oldestUnix * 1000L))} → newest " +
                                    "(~$spanDays day${if (spanDays == 1L) "" else "s"} of backlog, drained oldest-first)")
                            }
                            // The strap's own count of what it has banked but not sent, from its ring
                            // cursors — the backlog measured in pages rather than inferred from the span.
                            dataRangePagesBehind(frame)?.let { pages ->
                                log("Strap pages behind: $pages")
                            }
                            // One upfront CLOCK-DRIFT line in the UNIVERSAL block: the strap-reported
                            // [oldest, newest] window vs wall clock with a FUTURE-DATE flag — a wandering /
                            // un-clocked strap is the single most common "history lands months off" cause.
                            if (testCentre.active(com.noop.testcentre.TestDomain.UNIVERSAL)) {
                                val line = com.noop.analytics.ConnectionTrace.clockDriftLine(
                                    oldestUnix = if (oldestUnix != null && oldestUnix < it) oldestUnix else null,
                                    newestUnix = it,
                                    wallNowUnix = System.currentTimeMillis() / 1000L,
                                )
                                log(line, com.noop.testcentre.TestDomain.UNIVERSAL)
                            }
                        }
                    }

                    // PERSISTENCE / OFFLOAD ROUTING. Durable EVENT-frame log for research, BEFORE the
                    // offload branch so it sees both live events and their history replays (either path
                    // may be the only one that delivers a given record). No-op unless capture is on.
                    if (connectedFamily == DeviceFamily.WHOOP5) {
                        writeWhoop5EventLogIfEvent(uuid.toString(), frame)
                        // Durable log of the big high-rate R22 deep buffers (type-0x2F ≥ 1 KB) — its own
                        // file the bulk-capture eviction never churns. BEFORE the offload branch so it
                        // catches the burst; no-op unless capture is on.
                        writeWhoop5DeepBufferIfBig(uuid.toString(), frame, isOffloadFrame(frame, connectedFamily))
                    }
                    if (backfilling) {
                        // Opt-in raw capture: record EVERY frame of the session (offload AND live
                        // flood — the offload flag lets analysis filter), BEFORE routing so frames are
                        // retained before the trim ack deletes the strap's copy. No-op when off.
                        if (captureWriter != null) {
                            writeBackfillCapture(uuid.toString(), frame, parsed)
                        }
                        // Historical offload: route ONLY genuine offload frames (47/48/49/50) through the
                        // serial drain (preserves chunk order) + re-arm the idle watchdog. The live
                        // type-40/43 flood is dropped here — feeding it would stall the strap's trim-ack.
                        if (isOffloadFrame(frame, connectedFamily)) {
                            offloadFramesThisSession++
                            armBackfillTimeout()
                            routeBackfillFrame(frame)
                        }
                    } else {
                        // Live path: buffer the frame + its parse for a batched insert. Thread the
                        // single parse so flushLive doesn't re-decode the batch.
                        ingestLiveFrame(frame, parsed)
                    }
                  } catch (t: Throwable) {
                    log("inbound frame handling threw ${t.javaClass.simpleName} — dropping this frame, link stays up")
                  }
                }
            }
            else -> { /* ignore */ }
        }
    }

    /**
     * WHOOP 5/MG R22 telemetry: type 0x24 answering SET_CONFIG (0x78) acks one enable_r22 flag. A
     * type-0x2F OUTSIDE our own offload is another client's historical pull, not a live stream —
     * packet_type @byte 8, responded cmd @byte 10; [lastOffloadFrameAtMs] cooldown skips its trailing echo.
     */
    private fun noteWhoop5R22Telemetry(frame: ByteArray, duringOffload: Boolean) {
        // R22 deep-data is a WHOOP 5/MG concept only. On a WHOOP 4 a type-0x2F frame is something else
        // entirely, so counting it as a "deep packet" would give 4.0 owners a bogus deep-data counter.
        if (connectedFamily != DeviceFamily.WHOOP5) return
        if (frame.size <= 10) return
        val type = frame[8].toInt() and 0xFF
        if (type == 0x24 && (frame[10].toInt() and 0xFF) == CommandNumber.SET_CONFIG.rawValue) {
            val n = _state.value.r22FlagsAccepted + 1
            _state.update { it.copy(r22FlagsAccepted = n) }
            val total = Whoop5Config.enableR22Sequence.size
            if (n == total) log("Deep-data: strap ACCEPTED all $n/$total R22 flags ✓ — keep it on; watching for deep packets.")
        }
        if (type == 0x2F) {
            if (duringOffload) {
                // Trailing-history reference point: a 0x2F during the offload is banked history. Remember
                // when it landed so the cooldown below can discount the few that dribble in after the end.
                lastOffloadFrameAtMs = System.currentTimeMillis()
                return
            }
            // Cooldown guard: a 0x2F within DEEP_PACKET_LIVE_COOLDOWN_MS of our own last offload
            // frame/HISTORY_COMPLETE is a trailing historical record from that session.
            if (lastOffloadFrameAtMs != 0L &&
                System.currentTimeMillis() - lastOffloadFrameAtMs < DEEP_PACKET_LIVE_COOLDOWN_MS
            ) {
                return
            }
            // A 0x2F outside our offload is historical-offload data, not a live R22 stream — typically
            // another BLE client pulling the strap's backlog over the shared notify channel. Surface it
            // as a diagnostic, but don't claim a live-stream "unlock".
            val n = _state.value.deepPacketsThisSession + 1
            _state.update { it.copy(deepPacketsThisSession = n) }
            if (n == 1) log("Deep-data: type-0x2F received outside our offload — this is historical-offload data (another BLE client pulling the strap's history, or a trailing flush), not a live R22 stream.")
            else if (n % 50 == 0) log("Deep-data: $n type-0x2F historical-offload frames seen outside our session.")
        }
    }

    /** Parse-then-route shim. Kept for any caller/test that passes raw bytes; the live dispatcher
     *  parses ONCE and calls the overload below with the result. */
    private fun handleFrame(frame: ByteArray, replayedOffload: Boolean = false) =
        handleFrame(frame, RustAdapter.parseFrame(frame, connectedFamily), replayedOffload)

    /** Pure decode-to-state router for one complete frame. The dispatcher decodes each frame ONCE and
     *  threads it here, so a live frame is parsed once rather than twice. `frame` is still passed for
     *  the byte-level sub-decoders. */
    private fun handleFrame(frame: ByteArray, parsed: com.noop.protocol.ParsedFrame, replayedOffload: Boolean = false) {
        if (!parsed.ok) return
        // Reject frames that failed their checksum — never let bad bytes drive state.
        if (parsed.crcOk == false) return

        // Connection test mode: one tagged line per genuine frame-TYPE transition (not per frame — the
        // raw flood repeats one type, so the transition guard throttles it). Gated FIRST so it's zero-work
        // on the live hot path when off: the type compare and connLastFrameType write only run inside the gate.
        if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
            if (parsed.typeName != connLastFrameType) {
                log("frameTiming type=${parsed.typeName} t=${System.currentTimeMillis() / 1000L}s",
                    com.noop.testcentre.TestDomain.CONNECTION)
                connLastFrameType = parsed.typeName
            }
        }

        when (parsed.typeName) {
            "REALTIME_DATA" -> {
                // Reject 0 / out-of-range spikes; only accept physiologically plausible HR.
                (parsed.parsed["heart_rate"] as? Int)?.let { hr ->
                    if (hr in 30..220) _state.update { it.copy(heartRate = hr) }
                }
                // The realtime stream usually reports rr_count=0; only update R-R when this frame
                // actually carries intervals, so we don't wipe R-R sourced from the 0x2A37 profile.
                // withRRIntervals also feeds the Live console's rolling rrRecent buffer.
                intArrayValue(parsed.parsed["rr_intervals"])?.let { rr ->
                    if (rr.isNotEmpty()) _state.update { it.withRRIntervals(rr) }
                }
            }

            "COMMAND_RESPONSE" -> {
                doubleValue(parsed.parsed["battery_pct"])?.let { setBattery(it) }
                // WHOOP 4 serves GET_CLOCK and needs a device↔wall pair for stale-clock fallback.
                // WHOOP 5/MG timestamps are already unix and intentionally keep an identity reference.
                if (connectedFamily == DeviceFamily.WHOOP4) {
                    clockReference.accept(parsed)?.let { ref ->
                        log("Clock correlated: device=${ref.device} wall=${ref.wall}")
                    }
                }
                // Firmware version from the handshake: 4.0 reports fw_harvard (REPORT_VERSION_INFO),
                // 5/MG reports fw_version (GET_HELLO). Keyed on whichever field decoded, so one branch
                // covers both families; only republish state when it actually changes.
                (parsed.parsed["fw_version"] as? String ?: parsed.parsed["fw_harvard"] as? String)?.let { fw ->
                    if (_state.value.strapFirmware != fw) {
                        _state.update { it.copy(strapFirmware = fw) }
                        // Persist so the debug export can name the firmware offline (state clears on disconnect).
                        runCatching { NoopPrefs.setLastFirmware(context, fw) }
                    }
                }
                // The battery pack, as the strap reports it. Only republished when a field actually
                // moved, and only ever from a reply that carried it: a strap with no pack attached
                // leaves the last reading alone rather than zeroing it.
                doubleValue(parsed.parsed["pack_soc_pct"])?.let { soc ->
                    val serial = parsed.parsed["pack_serial"] as? String
                    val mv = (parsed.parsed["pack_millivolts"] as? Int)
                    if (_state.value.packSocPct != soc || _state.value.packSerial != serial) {
                        log("pack: soc=$soc% mv=${mv ?: "?"} serial=${serial ?: "?"} id=${parsed.parsed["pack_id"]}")
                    }
                    // The reply carries more than whoop-rs names today, so dump it ONCE per link: the
                    // pack's own firmware is in there and has no offset yet.
                    if (!packReplyLogged) {
                        packReplyLogged = true
                        log("pack raw: ${frame.toHex()}")
                    }
                    _state.update { it.copy(packSocPct = soc, packSerial = serial ?: it.packSerial,
                                            packMillivolts = mv ?: it.packMillivolts) }
                }
                val respCmd = parsed.parsed["resp_cmd"] as? String
                val result = parsed.parsed["result"] as? String
                // Reboot ack: log the COMMAND_RESPONSE result for a user reboot on both families (also
                // matches POWER_CYCLE_STRAP, the 4.0 reboot probe's candidate B) so the result byte tells
                // "opcode rejected" apart from "ignored". Log-only.
                if (respCmd?.startsWith("REBOOT_STRAP") == true || respCmd?.startsWith("POWER_CYCLE_STRAP") == true) {
                    val verdict = when {
                        result == null -> "no result"
                        result.startsWith("SUCCESS") -> "accepted"
                        else -> "REJECTED"
                    }
                    log("reboot: strap acked result=${result ?: "none"} ($verdict)")
                }
                // Wrist ack: the payload shape for SELECT_WRIST is inferred, so log the SUCCESS case too —
                // the non-success path below would otherwise be the only evidence either way. Log-only.
                if (respCmd?.startsWith("SELECT_WRIST") == true) {
                    log("wrist: strap acked result=${result ?: "none"}")
                }
                // 5/MG range-query gate: a GET_DATA_RANGE SUCCESS releases the history request
                // (PENDING precedes it; a 2s fail-open fallback covers a swallowed reply).
                if (connectedFamily == DeviceFamily.WHOOP5 && backfilling && !historicalKickSent &&
                    respCmd?.startsWith("GET_DATA_RANGE") == true
                ) {
                    when {
                        result?.startsWith("SUCCESS") == true -> {
                            log("Backfill: GET_DATA_RANGE SUCCESS — requesting history")
                            sendHistoricalKick()
                        }
                        result != null -> log("Backfill: GET_DATA_RANGE → $result (waiting)")
                    }
                }
                // Surface non-success command results in the strap log — a result=UNSUPPORTED line
                // here is how an MG haptics rejection would show itself in-app.
                if (result != null && !result.startsWith("SUCCESS")) {
                    log("Command response: ${respCmd ?: "?"} → $result")
                }
                // Arm-readback diagnostic: armStrapAlarm follows every WHOOP 4.0 arm with GET_ALARM_TIME
                // (67) so the log proves what the STRAP believes is armed, not just what we sent. LOG-ONLY:
                // the 4.0 layout is undocumented, so [whoop4ArmedAlarmEpoch] is defensive and logs raw hex on a miss.
                if (connectedFamily == DeviceFamily.WHOOP4 && respCmd?.startsWith("GET_ALARM_TIME") == true) {
                    val epoch = whoop4ArmedAlarmEpoch(frame)
                    if (epoch != null) {
                        // Log the RAW response bytes alongside the decoded epoch: a successful-but-mismatched
                        // decode (a plausible epoch that never matches what we armed) needs the raw frame to
                        // tell a genuinely-stale alarm from a misdecoded fixed field. Log-only.
                        val raw = whoop4AlarmReadbackPayloadHex(frame) ?: "empty"
                        log("Alarm: strap reports armed for ${alarmReadbackLocalTime(epoch)} (epoch $epoch) [raw $raw]")
                        // Persist what the strap reports so the debug export can show sent-vs-reported.
                        runCatching {
                            NoopPrefs.of(context).edit()
                                .putLong("alarm.lastReportedEpoch", epoch)
                                .putLong("alarm.lastReportedAt", System.currentTimeMillis())
                                .apply()
                        }
                    } else if (whoop4ReadbackReportsNoAlarm(frame)) {
                        // The strap's "nothing armed" sentinel — epoch decodes to 0. NOT an undocumented
                        // layout: it's the strap saying no alarm is stored, so an arm we just sent did NOT
                        // persist. Named plainly so a "didn't buzz" report shows SET went out, strap kept nothing.
                        val raw = whoop4AlarmReadbackPayloadHex(frame) ?: "empty"
                        log("Alarm: strap reports NO alarm currently stored (epoch 0) — the arm did not persist on the strap (raw $raw)")
                    } else {
                        val raw = whoop4AlarmReadbackPayloadHex(frame) ?: "empty"
                        log("Alarm: strap answered the alarm readback with an unrecognised payload (raw $raw) - layout undocumented, log-only")
                    }
                }
                // 5/MG arm-readback: the puffin GET_ALARM_TIME response layout is UNVERIFIED, so do NOT
                // reuse the 4.0 epoch decode — log the raw frame so a future capture can decode what the
                // 5/MG strap reports as armed. Log-only; nothing gates on this.
                if (connectedFamily == DeviceFamily.WHOOP5 && respCmd?.startsWith("GET_ALARM_TIME") == true) {
                    val raw = frame.joinToString(" ") { "%02x".format(it) }
                    log("Alarm: 5/MG strap answered the alarm readback (raw $raw) — layout unverified, log-only")
                }
                // The strap's OWN answer to the arm just sent: a dropped SET reads back epoch 0 via
                // GET_ALARM_TIME. WHOOP 4.0's result byte is at frame[8] and its meaning is UNVERIFIED
                // (5/MG puffin table: 0=FAILURE 1=SUCCESS 2=PENDING 3=UNSUPPORTED) — log the raw byte only.
                if (connectedFamily == DeviceFamily.WHOOP4 && respCmd?.startsWith("SET_ALARM_TIME") == true) {
                    val r = frame.getOrNull(8)?.toInt()?.and(0xFF)
                    val rhex = if (r != null) "0x%02x".format(r) else "none"
                    log("Alarm: strap answered the arm (SET_ALARM_TIME) with result=$rhex — log-only, 4.0 result-code meaning unverified")
                }
                // 5/MG arm-response: the result sits at a different offset than 4.0's frame[8]; it's
                // already decoded into `result` above. Surfaced here even on SUCCESS (which the line above
                // skips) so an export shows the rev-4 arm outcome. Log-only; unconfirmed on real hardware.
                if (connectedFamily == DeviceFamily.WHOOP5 && respCmd?.startsWith("SET_ALARM_TIME") == true) {
                    log("Alarm: 5/MG strap answered the arm (SET_ALARM_TIME) with result=${result ?: "none"} — log-only, rev-4 alarm unconfirmed")
                }
            }

            "CONSOLE_LOGS" -> {
                // The 5/MG strap narrates its own sync engine here ("BLE: PullStats: Data: N…",
                // "RTC timestamp … is invalid") — gold for protocol research, so mirror it into the
                // strap log (capped; the ring buffer holds 2k lines).
                (parsed.parsed["console"] as? String)?.let { txt ->
                    log("strap: ${txt.take(300)}")
                }
            }

            "EVENT" -> {
                (parsed.parsed["event"] as? String)?.let { ev ->
                    // Event strings are "NAME(rawValue)", e.g. "WRIST_ON(9)" (see Schema.enumName).
                    // Pure [isGestureEvent] so the gesture-vs-non-gesture routing is unit-testable.
                    val isGesture = isGestureEvent(ev)

                    // A BLE_BONDED event confirms a GENUINE encrypted bond (belt-and-suspenders; the
                    // confirmed-write ACK also sets this).
                    if (ev.startsWith("BLE_BONDED")) {
                        _state.update { it.copy(bonded = true, encryptedBond = true) }
                    }

                    if (!isGesture) {
                        // Non-gesture events (BLE_BONDED, BATTERY_LEVEL, …) surface in "Last Event" —
                        // except the live-HR stream toggle (BLE_REALTIME_HR_ON/OFF), which is internal
                        // plumbing that fires on every connect and just confuses users.
                        if (!ev.startsWith("BLE_REALTIME_HR")) {
                            _state.update { it.copy(lastEvent = ev) }
                        }
                        // Charging flag — wire observation: BATTERY_LEVEL u8 bit0 (4.0 @26 / 5.0 @30).
                        // Excludes only a HISTORICAL BATTERY_LEVEL replayed mid-backfill ([replayedOffload]);
                        // a genuine live event lights the pill immediately regardless of event_timestamp.
                        if (ev.startsWith("BATTERY_LEVEL") && shouldApplyChargingFromBatteryEvent(replayedOffload)) {
                            (parsed.parsed["battery_charging"] as? Int)?.let {
                                _state.update { s -> s.copy(charging = it != 0) }
                            }
                        }
                        // The strap raises CHARGING_ON/OFF as a pack goes on or comes off, so the pill
                        // turns over immediately instead of waiting for the next BATTERY_LEVEL. Same
                        // historical-replay exclusion as the flag above.
                        if (shouldApplyChargingFromBatteryEvent(replayedOffload)) {
                            chargingFromEvent(ev)?.let { on -> _state.update { s -> s.copy(charging = on) } }
                        }
                        // Strap fired its firmware smart alarm (STRAP_DRIVEN_ALARM_EXECUTED, event 57) →
                        // re-arm the next day's instant (single absolute time). Dispatches from here (NOT a
                        // gesture); gated on [replayedOffload] so a HISTORICAL replay can't spuriously re-arm.
                        if (smartAlarmFiredForEvent(ev, replayedOffload)) {
                            log("Strap fired its smart alarm (event 57) — re-arming the next day's instant")
                            // Persist the fire so the debug export's Alarm block shows "last fired".
                            runCatching { NoopPrefs.of(context).edit().putLong("alarm.lastFiredAt", System.currentTimeMillis()).apply() }
                            onSmartAlarmFired?.invoke()
                        }
                    } else {
                        // Physical inputs — LIVE ONLY. Gate ONLY while backfilling (a historical replay,
                        // old ts, is ignored during a sync); a real-time gesture always fires ungated,
                        // since a stale strap RTC would otherwise make it look "old" and drop every event.
                        val ts = (parsed.parsed["event_timestamp"] as? Int)?.toLong()
                        val nowSec = System.currentTimeMillis() / 1000L
                        val fresh = !backfilling || (ts != null && ts > 0 &&
                            kotlin.math.abs(nowSec - ts) <= LIVE_GESTURE_WINDOW_SECONDS)
                        if (fresh) {
                            _state.update { it.copy(lastEvent = ev) }
                            when {
                                ev.startsWith("DOUBLE_TAP") -> {
                                    // Surfaced via lastEvent only — the decode is unchanged. AppViewModel's
                                    // LiveState collector (dispatchDoubleTap) debounces on the event identity
                                    // and runs the user's chosen DoubleTapAction.
                                }
                                ev.startsWith("WRIST_ON") -> {
                                    if (!_state.value.worn) _state.update { it.copy(worn = true) }
                                }
                                ev.startsWith("WRIST_OFF") -> {
                                    if (_state.value.worn) _state.update { it.copy(worn = false) }
                                }
                            }
                        }
                    }
                }
            }

            else -> { /* ignore other packet types here (handled by the data layer in the full app) */ }
        }
    }

    /**
     * Parse a standard BLE Heart Rate Measurement (0x2A37). byte 0 = flags: bit0 = HR is u16 (else u8),
     * bit4 = R-R intervals present (each u16 LE, 1/1024 s). The standard profile is the RELIABLE source
     * for both HR and R-R.
     */
    private fun parseStandardHr(data: ByteArray) {
        if (data.isEmpty()) return
        val flags = data[0].toInt() and 0xFF
        val hr16 = (flags and 0x01) != 0
        val rrPresent = (flags and 0x10) != 0

        var idx = 1
        val hr: Int
        if (hr16) {
            if (data.size < idx + 2) return
            hr = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
            idx += 2
        } else {
            if (data.size < idx + 1) return
            hr = data[idx].toInt() and 0xFF
            idx += 1
        }

        // Energy-expended field (bit3) precedes R-R if present — skip its 2 bytes.
        if ((flags and 0x08) != 0) idx += 2

        val rr = mutableListOf<Int>()
        if (rrPresent) {
            while (idx + 1 < data.size) {
                val raw = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
                idx += 2
                // Convert 1/1024 s units to milliseconds (matches the WHOOP store's R-R in ms). ROUNDED,
                // not truncated: plain integer division diverges up to ~0.5 ms per interval into RMSSD/HRV.
                rr.add(Math.round(raw / 1024.0 * 1000.0).toInt())
            }
        }

        // R-R: the standard profile is the reliable source — surface whenever present. withRRIntervals
        // also feeds the Live console's rolling rrRecent buffer.
        if (rr.isNotEmpty()) _state.update { it.withRRIntervals(rr) }
        // HR: accept only physiologically plausible values; reject 0/garbage (off-wrist).
        if (hr in 30..220) {
            _state.update { it.copy(heartRate = hr) }
            // EXPERIMENTAL WHOOP 5.0/MG: there is no confirmed-write bond for a 5/MG strap, so once
            // live HR actually streams over the standard profile we treat the link as established —
            // otherwise the UI sits on "Connecting…" forever even though data is flowing.
            if (connectedFamily != DeviceFamily.WHOOP4 && !_state.value.bonded) {
                // atomic update: LiveState is written from multiple threads (binder/main/IO).
                _state.update { it.copy(bonded = true) }
                log("WHOOP 5/MG: live HR streaming — marking the link established (experimental).")
                // 5/MG has no WHOOP4 confirmed-write handshake, so the keep-alive (re-subscribe +
                // 120s liveness bounce) is started here, on the bonded transition, instead of in
                // runConnectHandshake. Handler.postDelayed is thread-safe to call from this callback.
                startKeepAlive()
            }
        }

        // Record it continuously — independent of the realtime stream or which screen is open.
        ingestStandardHr(hr, rr, (System.currentTimeMillis() / 1000L))
    }

    /** The Test Centre gate, bound once to the app's single "noop_testcentre" prefs file. Lazily built so
     *  the zero-cost gate below is one SharedPreferences.getBoolean read, never a fresh prefs open per
     *  reading. */
    private val testCentre by lazy { com.noop.testcentre.TestCentre.from(context) }

    /** Single funnel for battery readings. */
    private fun setBattery(pct: Double) {
        _state.update { it.copy(batteryPct = pct) }
        // Battery test mode: one tagged (t, soc) line per reading, gated zero-cost when off (the gate is a
        // single SharedPreferences bool read; the formatter below only runs when the mode is on). Rides the
        // redacting log() sink; the Room battery series is the readout + trace source.
        if (testCentre.active(com.noop.testcentre.TestDomain.BATTERY)) {
            log(BatterySocLine.format(pct, System.currentTimeMillis() / 1000L),
                com.noop.testcentre.TestDomain.BATTERY)
        }
    }

    // ====================================================================================
    // MARK: Connect handshake
    // ====================================================================================

    /**
     * Connect lifecycle, run EXACTLY ONCE per connection after the bond ACK: hello → set RTC → stop the
     * type-43 realtime flood → refresh data range. The type-43 stop (SEND_R10_R11_REALTIME [0x00]) matters
     * because the unprompted flood otherwise eats BLE airtime.
     */
    private fun runConnectHandshake() {
        send(CommandNumber.GET_HELLO_HARVARD)
        // One-shot firmware-version read for the Devices card (a documented READ command, not a
        // firmware-load opcode). Pick the family-appropriate one — a strap silently ignores the command
        // meant for the other generation; the response decodes to fw_harvard (4.0) / fw_version (5/MG).
        when (connectedFamily) {
            DeviceFamily.WHOOP4 -> send(CommandNumber.REPORT_VERSION_INFO)
            DeviceFamily.WHOOP5 -> send(CommandNumber.GET_HELLO)
        }
        sendSetClockBothForms()
        // GET_CLOCK's payload length is firmware-specific, exactly like SET_CLOCK's: newer firmware
        // answers the EMPTY form and ignores [0x00], while fw 41.17.x answers [0x00] and ignores the
        // empty form. Send both — the strap answers whichever its firmware accepts.
        send(CommandNumber.GET_CLOCK, byteArrayOf())               // empty form (newer firmware)
        send(CommandNumber.GET_CLOCK, byteArrayOf(0))              // [0x00] form (fw 41.17.x)
        send(CommandNumber.SEND_R10_R11_REALTIME, byteArrayOf(0))  // stop the type-43 realtime flood
        send(CommandNumber.GET_DATA_RANGE)                          // refresh stored range
        log("Connect handshake sent (hello/set-clock/get-clock/stop-raw/get-range)")

        // Historical offload: the type-47 store is the PRIMARY metric source. Kick it once on connect
        // (deferred so SET_CLOCK/GET_DATA_RANGE round-trip first, on a settled link), then re-offload
        // every BACKFILL_INTERVAL_MS.
        backfillStarted = true
        handler.postDelayed({ requestSync(BackfillTrigger.CONNECT) }, INITIAL_BACKFILL_DELAY_MS)
        startBackfillTimer()
        startKeepAlive()
        // Arm realtime HR now if a screen already wants it, or the continuous-capture preference does —
        // otherwise the stream would only start at the next keep-alive tick. Mark it armed so
        // reconcileRealtime() tracks the edge correctly (reset() cleared realtimeArmed on disconnect).
        // RE-DERIVE the want at arm time (same reasoning as the 5/MG post-bond arm): a reconnect outside
        // the overnight window must not arm the stream from a stale precomputed [wantsRealtime].
        val realtimeWantNow = screenWantsRealtime
        wantsRealtime = realtimeWantNow
        if (realtimeWantNow) { realtimeArmed = true; send(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(1)) }
    }

    // ====================================================================================
    // MARK: Live-stream keep-alive
    // ====================================================================================

    /** (Re)start the 30s keep-alive. Called from the connect handshake; cancelled in [reset]. */
    private fun startKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
        keepAliveTick = 0
        lastDataAtMs = System.currentTimeMillis()   // arm the watchdog from "now", not 1970
        handler.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
    }

    /**
     * Keep the live stream alive. WHOOP firmware lets realtime HR lapse if not periodically re-armed,
     * and a CCCD can silently drop — both freeze HR while GATT still says "connected". Every 30s: bounce
     * the link if nothing arrived for >120s, else re-subscribe if quiet, re-arm HR, and poll battery.
     */
    @SuppressLint("MissingPermission")
    private fun keepAliveFire() {
        val s = _state.value
        if (!s.connected || !s.bonded) return   // disconnected: stop the cadence (restarts on reconnect)

        val silentMs = System.currentTimeMillis() - lastDataAtMs
        // Everything below is the LIVE-path keep-alive. During a historical offload the strap owns the
        // link via its own 60s idle watchdog (backfillTimeoutRunnable), so we stay out of the way — in
        // particular we must NOT bounce, which would abandon the offload and break the safe-trim cursor.
        if (!backfilling) {
            // A known history-empty 5/MG (firmware serves no offload) gets a far longer fuse: live HR
            // over 0x2A37 keeps the link genuinely alive, but packets can lull >120s off-wrist/resting,
            // so the tight fuse would bounce a healthy link. A WHOOP 4 keeps the tight 120s fuse.
            val bounceFuse = if (connectedFamily == DeviceFamily.WHOOP5 && whoop5EmptyOffload.historyEmpty)
                KEEPALIVE_STALL_5MG_EMPTY_MS else KEEPALIVE_STALL_MS
            if (silentMs > bounceFuse) {
                // Nothing for the fuse window — the live stream/link stalled. Bounce it: the auto-rescan on
                // disconnect re-bonds and resumes streaming (the automatic version of the manual fix).
                log("No data for ${silentMs / 1000}s — bouncing link to resume live stream")
                intentionalDisconnect = false    // make sure the auto-reconnect fires
                // disconnect() throwing on a dead binder would crash from the keep-alive timer;
                // tear down directly so the bounce degrades to a clean disconnect.
                try {
                    gatt?.disconnect()           // → handleDisconnect → reset() (cancels this) → reconnect
                } catch (t: Throwable) {
                    log("keep-alive bounce: gatt.disconnect() threw ${t.javaClass.simpleName}; tearing down")
                    teardownAfterGattFailure()
                }
            } else {
                // Recover a silently-dropped subscription once the stream goes quiet — but only ONCE per
                // quiet episode: re-subscribing every tick floods descriptor writes that collide with the
                // command queue on a slow stack. Re-armed on data.
                if (silentMs > KEEPALIVE_QUIET_MS && !resubscribedSinceData) {
                    resubscribedSinceData = true
                    enableLiveNotifications()
                }
                // WHOOP 4.0 only: re-arm realtime HR so firmware can't let it lapse, and poll battery
                // (~60s), which also keeps the link warm. A 5/MG strap rejects WHOOP4-framed commands, so
                // we skip them and rely on re-subscribe + bounce; the tick still advances for both families.
                keepAliveTick += 1
                if (connectedFamily == DeviceFamily.WHOOP4) {
                    if (wantsRealtime) { realtimeArmed = true; send(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(1)) }
                    if (keepAliveTick % 2 == 0) send(CommandNumber.GET_BATTERY_LEVEL)
                } else if (connectedFamily == DeviceFamily.WHOOP5 && keepAliveTick % 2 == 0) {
                    // 5/MG battery comes only from a 0x2A19 read (no unsolicited notification), so poll it
                    // here (~60s) rather than only while the Live screen is open — the ring stays current
                    // on any screen without a manual sync, and the read keeps the link warm.
                    refreshBattery()
                    refreshBatteryPack()
                }
            }
        }

        // Always re-arm the cadence. After a bounce the pending disconnect cancels this via reset(); a
        // tick that fires while disconnected returns early above — so the keep-alive is never orphaned.
        handler.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    /**
     * Re-enable notifications on the live characteristics — recovers a CCCD subscription the stack
     * silently dropped. [drainCccdQueue] writes them one at a time; draining to empty is a no-op for
     * [startSession] (sessionStarted is already true), so this never re-fires the bond/hello.
     */
    @SuppressLint("MissingPermission")
    private fun enableLiveNotifications() {
        val g = gatt ?: return
        when (connectedFamily) {
            DeviceFamily.WHOOP4 -> g.getService(WHOOP4_SERVICE)?.let { svc ->
                svc.getCharacteristic(CMD_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
                svc.getCharacteristic(EVENT_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
                svc.getCharacteristic(DATA_NOTIFY_CHAR)?.let { cccdQueue.add(it) }
            }
            DeviceFamily.WHOOP5 -> { /* 5/MG live HR rides the standard profile, re-subscribed below */ }
        }
        g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_CHAR)?.let { cccdQueue.add(it) }
        g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_CHAR)?.let { cccdQueue.add(it) }
        drainCccdQueue(g)
    }

    /**
     * The Live screen wants realtime HR. Records the screen want and reconciles.
     */
    fun startRealtime() {
        screenWantsRealtime = true
        reconcileRealtime()
    }

    /** The Live screen no longer needs realtime HR; clear its want and reconcile. */
    fun stopRealtime() {
        screenWantsRealtime = false
        reconcileRealtime()
    }

    /**
     * Single reconciler for the realtime-HR stream. Armed while a screen wants it ([screenWantsRealtime]);
     * we send TOGGLE_REALTIME_HR ONLY on the false↔true edge. The toggle only reaches the strap once it's
     * a WHOOP4 or a bonded 5/MG; otherwise the want is remembered and the post-bond branch arms it.
     */
    private fun reconcileRealtime() {
        // Confine to the GATT looper (main), like [drainWriteQueue]/[drainCccdQueue]: this does an
        // order-sensitive check-then-set on [realtimeArmed]. Every caller runs on Main today (a no-op
        // pass-through), but a future off-main caller is deferred here instead of racing [realtimeArmed].
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { reconcileRealtime() }
            return
        }
        val want = screenWantsRealtime
        wantsRealtime = want   // the keep-alive + post-bond arm-on-connect read this derived value
        if (want == realtimeArmed) return                          // no edge — nothing to send
        if (connectedFamily != DeviceFamily.WHOOP4 && !_state.value.bonded) return   // can't reach the strap yet
        realtimeArmed = want
        // Both families arm/disarm via TOGGLE_REALTIME_HR; send() frames it correctly per family (puffin
        // for 5/MG). A screen re-entry blanks its own smoothing window in the view-model, not here.
        send(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(if (want) 1.toByte() else 0.toByte()))
    }

    
    fun enableWhoop5DeepData() {
        if (connectedFamily != DeviceFamily.WHOOP5) {
            log("Deep-data: needs a WHOOP 5.0/MG strap — ignored."); return
        }
        if (!puffinExperiment.isDeepDataEnabled) {
            log("Deep-data: the deep-data experiment is off — enable it in Settings first."); return
        }
        val s = _state.value
        if (!s.connected || !s.encryptedBond) {
            // The R22 SET_CONFIG writes go over the encrypted command channel, so the live-HR-only
            // shortcut (bonded true, encryptedBond false on a 5/MG still owned by the official app)
            // can't carry them. Require the genuine bond, or the writes silently fail.
            log("Deep-data: needs the full encrypted bond, not the live-HR-only link. Close the official WHOOP app, put the strap in pairing mode, and bond it to NOOP first — ignored."); return
        }
        if (!s.worn) {
            log("Deep-data: the R22 stream is on-wrist only — put the strap ON, then try again."); return
        }
        _state.update { it.copy(r22FlagsAccepted = 0) }   // fresh attempt
        val flags = Whoop5Config.enableR22Sequence
        log("Deep-data: sending the ${flags.size}-flag enable_r22 sequence (experimental, reversible)…")
        flags.forEachIndexed { i, flag ->
            handler.postDelayed({
                sendCommand(CommandNumber.SET_CONFIG, withResponse = true) { s ->
                    RustCodec.setConfigFrame(s, flag.name, flag.value)
                }
            }, 80L * i)
        }
        handler.postDelayed({
            log("Deep-data: sequence sent. Keep the strap on, let it sync, then share your strap log — we're looking for new deep records (type-0x2F) to start arriving.")
        }, 80L * flags.size + 200L)
    }

    /**
     * Send SET_CLOCK in every payload form the WHOOP 4 firmware family accepts (8-byte for newer
     * firmware, 9-byte for fw 41.17.x — each a no-op on the other). A strap that misses its form keeps an
     * invalid RTC and stops banking sensor data to flash. WHOOP 5/MG keeps its single 8-byte send.
     */
    private fun sendSetClockBothForms(withResponse: Boolean = false) {
        val now = System.currentTimeMillis() / 1000L
        val gen = connectedFamily.gen
        val sent = sendCommand(CommandNumber.SET_CLOCK, withResponse) { s -> RustCodec.setClockFrame(gen, s, now) }
        if (sent) log("→ SET_CLOCK (8-byte)")
        if (selectedModel == WhoopModel.WHOOP4) {
            val legacy = sendCommand(CommandNumber.SET_CLOCK, withResponse) { s -> RustCodec.setClockLegacyFrame(Gen.GEN4, s, now) }
            if (legacy) log("→ SET_CLOCK (legacy 9-byte)")
        }
    }

    // ====================================================================================
    // MARK: Write + descriptor queues (Android GATT one-op-at-a-time serialisation)
    // ====================================================================================

    private fun enqueueWrite(item: PendingWrite) {
        writeQueue.add(item)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        // Serialise onto the GATT thread (main looper) — see connectGatt(..., handler). A command
        // issued from a ViewModel coroutine (buzz/send) must not touch the stack off-thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { drainWriteQueue() }
            return
        }
        if (writeInFlight) return
        gatt ?: return
        val ops = gattOps ?: return
        val ch = cmdCharacteristic ?: return
        // A frame rejected BUSY last tick takes priority so it keeps its place in the command sequence.
        val item = pendingRetry ?: writeQueue.poll() ?: return
        pendingRetry = null
        writeInFlight = true

        val writeType = if (item.withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT      // with response (acked)
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        // safeGatt: a throw here means the binder died (radio turned off mid-link) — it tears the link
        // down and returns false. After teardown the queues are cleared and gatt is null, so the recursive
        // re-drain below immediately no-ops instead of retrying against a dead binder.
        val ok = safeGatt("writeCharacteristic") {
            ops.writeCharacteristicCompat(ch, item.frame, writeType)
        }

        if (!ok) {
            // Transient BUSY (stack hasn't freed the previous write, common on Android 13+/16). Re-hold
            // and retry: a dropped TOGGLE_REALTIME_HR / SET_CLOCK / offload-ack silently breaks live HR,
            // the clock, or the backfill. If safeGatt already tore down, gatt is null — bail, don't retry.
            writeInFlight = false
            if (gatt == null) return
            if (writeRetries < MAX_WRITE_RETRIES) {
                writeRetries++
                log("writeCharacteristic busy; retry $writeRetries/$MAX_WRITE_RETRIES")
                pendingRetry = item
                // Escalating backoff (12, 24, … capped ~96ms) — ride out a congestion spike instead of
                // exhausting the retry budget in a few tens of ms. NAMED runnable so teardown can cancel
                // a pending retry.
                handler.postDelayed(drainWriteRetryRunnable, WRITE_RETRY_DELAY_MS * minOf(writeRetries, 8))
            } else {
                // Genuinely stuck after several tries — drop this one frame so it can't wedge the queue.
                log("writeCharacteristic rejected by stack; dropping one frame (after $MAX_WRITE_RETRIES retries)")
                // A dropped TOGGLE_REALTIME_HR would leave live R-R off FOREVER — [realtimeArmed] already
                // latched the SENT value, so reconcileRealtime sees no edge and never re-sends. Flip it to
                // the OPPOSITE so the next keep-alive tick sees the edge and re-sends the CURRENT want.
                if (shouldReArmRealtimeAfterDrop(item.cmd)) {
                    realtimeArmed = !realtimeArmed
                    log("realtime toggle dropped — reconciling on the next keep-alive tick")
                }
                writeRetries = 0
                drainWriteQueue()
            }
            return
        }
        writeRetries = 0   // this frame went out — reset the per-frame retry budget

        // WITHOUT-response writes get NO onCharacteristicWrite callback, so free the slot ourselves —
        // but after a short PACING gap: a bare post fires the next write on the same looper tick, before
        // the stack accepted this one, so Android 16 rejects it. postDelayed, not post.
        if (!item.withResponse) {
            handler.postDelayed({
                writeInFlight = false
                drainWriteQueue()
            }, WITHOUT_RESPONSE_PACE_MS)
        }
    }

    /**
     * Fire the bonding write directly, bypassing the normal queue so it is unambiguously first.
     */
    @SuppressLint("MissingPermission")
    private fun writeBondFrame(ch: BluetoothGattCharacteristic) {
        val ops = gattOps ?: return
        val s = seq.incrementAndGet() and 0xFF
        val bondFrame = RustCodec.getBatteryFrame(Gen.GEN4, seq = s)
        log("Bonding: confirmed write GET_BATTERY_LEVEL to 61080002")
        writeInFlight = true   // hold the slot until onCharacteristicWrite fires (with response).
        // safeGatt: a throw means the binder died — teardown, return false, fall into the
        // "rejected" branch which just clears the (now-stale) in-flight slot.
        val ok = safeGatt("writeBondFrame") {
            ops.writeCharacteristicCompat(ch, bondFrame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        if (!ok) {
            writeInFlight = false
            log("Bond write rejected by stack")
        }
    }

    /**
     * EXPERIMENTAL: WHOOP 5.0/MG opens a session with a static CLIENT_HELLO frame written to its
     * fd4b0002 command characteristic, instead of the WHOOP4 confirmed-write bond. Written WITHOUT a
     * response; write first, then drain the notify subscriptions (like the WHOOP4 bond). Unverified on real MG hardware.
     */
    @SuppressLint("MissingPermission")
    private fun writeClientHello(ch: BluetoothGattCharacteristic) {
        val hello = DeviceFamily.WHOOP5.clientHello ?: return
        val ops = gattOps ?: return
        // CONFIRMED (with-response) write — hardware-verified: this triggers the strap's just-works
        // bond. A 5/MG strap won't stream HR (even over 0x2A37) on an UNauthenticated link, so an
        // unacknowledged write leaves it bond-less and silent. Hold the slot until the ACK.
        log("WHOOP 5/MG: writing CLIENT_HELLO to fd4b0002 with response (to trigger bonding, experimental).")
        writeInFlight = true
        val ok = safeGatt("writeClientHello") {
            ops.writeCharacteristicCompat(ch, hello, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        if (!ok) {
            writeInFlight = false
            log("CLIENT_HELLO write rejected by stack")
        }
    }

    /**
     * Open the session once every notification is subscribed — issuing the first command earlier would
     * race the CCCD descriptor writes and drop the subscriptions. WHOOP 4.0 fires the just-works bond
     * write; WHOOP 5/MG sends CLIENT_HELLO. Guarded to run exactly once per connection.
     */
    private fun startSession() {
        if (sessionStarted) return
        sessionStarted = true
        val cmd = cmdCharacteristic
        if (cmd == null) {
            log("Subscribed, but no command characteristic — cannot open a session")
            return
        }
        when (connectedFamily) {
            DeviceFamily.WHOOP4 -> writeBondFrame(cmd)
            DeviceFamily.WHOOP5 -> writeClientHello(cmd)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainCccdQueue(g: BluetoothGatt) {
        // All GATT mutations must run on the one thread the callbacks are pinned to (the main looper,
        // via connectGatt(..., handler)). Re-post if we got here from any other thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { drainCccdQueue(g) }
            return
        }
        if (cccdInFlight) return
        val ch = cccdQueue.poll()
        if (ch == null) {
            // 5/MG handshake tail: after the PUFFIN notify chars are subscribed, clock the strap and only
            // then kick the offload — an un-clocked WHOOP 5 discards sensor data ("RTC timestamp … is
            // invalid") and offloads complete with zero body frames. Gated to once-per-connection.
            if (connectedFamily == DeviceFamily.WHOOP5 && didBond && !connectHandshakeDone) {
                connectHandshakeDone = true
                noteRebootReconnectIfNeeded()
                sendCommand(CommandNumber.SET_CLOCK, withResponse = true) { s ->
                    RustCodec.setClockFrame(Gen.GEN5, seq = s, nowUnix = System.currentTimeMillis() / 1000L)
                }
                send(CommandNumber.GET_CLOCK, byteArrayOf(), withResponse = true)
                // Populate the battery ring right after connect, not only once the Live screen opens. Posted
                // after the clock writes settle so the 0x2A19 read does not race them on a slow stack.
                handler.postDelayed({ refreshBattery(); refreshBatteryPack() }, BATTERY_ON_CONNECT_DELAY_MS)
                log("WHOOP 5/MG: clock synced (set/get) — strap can persist history now")
                if (!backfillStarted) {
                    backfillStarted = true
                    handler.postDelayed({ requestSync(BackfillTrigger.CONNECT) }, INITIAL_BACKFILL_DELAY_MS)
                    startBackfillTimer()
                }
                return
            }
            // Every notification is enabled — now it's safe to write the first command, one GATT
            // operation at a time, so the bond/hello can't race the CCCD descriptor writes (which would
            // otherwise silently drop every subscription).
            startSession()
            return
        }
        val ops = gattOps ?: return
        cccdInFlight = true

        // Tell the local stack to surface notifications, then write the CCCD so the remote starts
        // sending them. Both are routed through safeGatt so a dead binder tears down instead of crashing.
        val notifyOk = safeGatt("setCharacteristicNotification") {
            ops.setCharacteristicNotificationCompat(ch, true)
        }
        if (!notifyOk && gatt == null) return   // safeGatt tore down — link is gone
        val cccd = ch.getDescriptor(CCCD)
        if (cccd == null) {
            log("No CCCD on ${ch.uuid}; skipping")
            cccdInFlight = false
            drainCccdQueue(g)
            return
        }
        val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = safeGatt("writeDescriptor") {
            ops.writeDescriptorCompat(cccd, enableValue)
        }
        if (!ok) {
            cccdInFlight = false
            if (gatt == null) return   // safeGatt tore down — don't schedule a retry against a dead link
            if (cccdRetries < MAX_CCCD_RETRIES) {
                // Transient BUSY (the stack slot hasn't freed): re-queue this subscribe and retry
                // shortly. Order among the notify chars doesn't matter, so re-add at the tail. NAMED
                // runnable so teardown can cancel a pending retry.
                cccdRetries++
                log("writeDescriptor busy for ${ch.uuid}; retry $cccdRetries/$MAX_CCCD_RETRIES")
                cccdQueue.add(ch)
                handler.postDelayed(drainCccdRetryRunnable, CCCD_RETRY_DELAY_MS)
            } else {
                log("writeDescriptor rejected for ${ch.uuid} (gave up after $MAX_CCCD_RETRIES retries)")
                drainCccdQueue(g)
            }
        }
    }

    // ====================================================================================
    // MARK: Live persistence
    // ====================================================================================

    /** Parse-then-buffer shim: kept for callers that pass raw bytes. The live dispatcher passes the
     *  parse it already did. */
    private fun ingestLiveFrame(frame: ByteArray) =
        ingestLiveFrame(frame, RustAdapter.parseFrame(frame, connectedFamily))

    /**
     * Buffer one complete custom-channel frame; flush when [FLUSH_MAX_FRAMES] frames or
     * [FLUSH_MAX_INTERVAL_MS] have elapsed. No clock-ref gate needed: live decode uses an identity
     * clock, and the historical store (the real metric source) carries its own unix ts.
     */
    private fun ingestLiveFrame(frame: ByteArray, parsed: com.noop.protocol.ParsedFrame) {
        val shouldFlush = synchronized(collectorLock) {
            liveBuffer.add(frame to parsed)   // synchronous append preserves GATT-callback arrival order
            liveBuffer.size >= FLUSH_MAX_FRAMES ||
                (System.currentTimeMillis() - batchStartedAtMs) >= FLUSH_MAX_INTERVAL_MS
        }
        if (shouldFlush) ioScope.launch { flushLive() }
    }

    /**
     * Decode the buffered live frames and persist them. Snapshot+clear under the lock BEFORE the
     * suspend insert, so concurrent ingests accumulate into the next batch.
     */
    private suspend fun flushLive() {
        val frames = synchronized(collectorLock) {
            if (liveBuffer.isEmpty()) return
            val snapshot = ArrayList(liveBuffer)
            liveBuffer.clear()
            batchStartedAtMs = System.currentTimeMillis()
            snapshot
        }
        // REALTIME_DATA carries the strap's OWN timestamp, untrustworthy when its RTC is invalid (a bogus
        // uptime counter). Anchor the batch's newest realtime ts to wall-clock `now` so live HR lands on
        // today's timeline regardless; a no-op when the clock is already valid. History store stays authoritative.
        val now = (System.currentTimeMillis() / 1000L).toInt()
        val parsed = frames.map { it.second }   // the dispatcher already decoded these - don't re-parse
        val newestRealtimeTs = parsed.asSequence()
            .filter { it.ok && it.crcOk != false && it.typeName == "REALTIME_DATA" }
            .mapNotNull { (it.parsed["timestamp"] as? Number)?.toInt() }
            .maxOrNull() ?: now
        // Live row assembly stays Kotlin ([extractStreams] over already-parsed frames), independent of
        // the history/PPG decode path.
        val streams = extractStreams(parsed, deviceClockRef = newestRealtimeTs, wallClockRef = now)
        val batch = StreamPersistence.toBatch(streams)
        if (!batch.isEmpty) {
            try {
                repository.insert(batch, deviceId)
            } catch (t: Throwable) {
                // Re-buffer at the front so these frames retry on the next cadence.
                synchronized(collectorLock) { liveBuffer.addAll(0, frames) }
            }
        }
    }

    /**
     * Buffer one standard 0x2A37 reading (carries a wall-clock ts directly, no clock ref needed).
     * Arms a flush past [LIVE_FLUSH_ROWS] buffered rows.
     */
    private fun ingestStandardHr(hr: Int, rr: List<Int>, ts: Long) {
        val shouldFlush = synchronized(collectorLock) {
            if (hr in 30..220) stdHr.add(HrRow(ts, hr))
            for (r in rr) if (r in 250..3000) stdRr.add(RrRow(ts, r))
            stdHr.size + stdRr.size >= LIVE_FLUSH_ROWS
        }
        if (shouldFlush) ioScope.launch { flushStandardHr() }
    }

    /**
     * Persist the buffered standard HR/RR up to [liveFlushCutoff], holding the newest wall-second back
     * so one second is never split across two inserts. [closing] drains the tail on teardown.
     * Re-buffers on failure.
     *
     * R-R rides [persistLiveRr]: the historical record already carries every beat, stamped by the strap,
     * so storing the live copy too banks each beat twice under two clocks.
     */
    private suspend fun flushStandardHr(closing: Boolean = false) {
        val keepRr = persistLiveRr()
        val (hr, rr) = synchronized(collectorLock) {
            val cutoff = liveFlushCutoff(stdHr, stdRr, closing)
            val h = ArrayList(stdHr.filter { it.ts < cutoff })
            val r = if (keepRr) ArrayList(stdRr.filter { it.ts < cutoff }) else ArrayList()
            if (h.isEmpty() && r.isEmpty()) {
                // Always drain, or a dropped stream would grow the buffer without bound.
                stdHr.removeAll { it.ts < cutoff }
                stdRr.removeAll { it.ts < cutoff }
                return
            }
            stdHr.removeAll { it.ts < cutoff }
            stdRr.removeAll { it.ts < cutoff }
            h to r
        }
        try {
            repository.insert(StreamBatch(hr = hr, rr = rr), deviceId)
        } catch (t: Throwable) {
            synchronized(collectorLock) { stdHr.addAll(0, hr); if (keepRr) stdRr.addAll(0, rr) }
        }
    }

    private fun persistLiveRr(): Boolean = persistsLiveRr(connectedFamily, whoop5EmptyOffload.historyEmpty)

    // ====================================================================================
    // MARK: Historical offload
    // ====================================================================================

    /**
     * Start a historical-offload session: flip the routing flag, kick SEND_HISTORICAL_DATA, arm the
     * idle watchdog. Payload MUST be [0x00], NOT empty - verified on-device this strap serves type-47
     * only with [0x00]. Sequence: HISTORY_START -> type-47 records -> HISTORY_END (acked) -> HISTORY_COMPLETE.
     */
    private fun beginBackfill() {
        if (!connectHandshakeDone) {
            log("Backfill: deferred — connect handshake not done yet")
            return
        }
        if (backfilling) return
        // consecutiveAutoContinues > 0 means this offload is re-kicked after an EARLIER session in the
        // same burst banked rows - tell the backfiller so its no-cursor END reads as "caught up", not "no
        // banked history / charge to 100%". A fresh offload (count 0) keeps the honest guidance.
        backfiller.begin(connectedFamily, continuedAfterRows = consecutiveAutoContinues > 0)   // family drives the +4 puffin offset for 5/MG
        backfilling = true
        lastBackfillAtMs = System.currentTimeMillis()   // the BackfillPolicy floor is measured from the last KICK
        ackedChunksThisSession = 0
        decodedChunksThisSession = 0
        consoleChunksThisSession = 0
        offloadFramesThisSession = 0
        historicalKickSent = false
        _state.update { it.copy(backfilling = true, syncChunksThisSession = 0) }
        // Opt-in raw capture (research aid): pref read fresh per session, like the probes gate.
        if (PuffinExperiment.from(context).isCaptureEnabled) {
            startBackfillCapture()
        }
        if (connectedFamily == DeviceFamily.WHOOP5) {

            // Hardware-validated: query the strap's stored range first, fire the transfer on its SUCCESS
            // response (PENDING precedes it). FAIL-OPEN: hardware sometimes swallows the first
            // GET_DATA_RANGE, so a 2s fallback fires the transfer anyway. WHOOP4 keeps its blind-fire path.
            send(CommandNumber.GET_DATA_RANGE, byteArrayOf(), withResponse = true)
            handler.postDelayed({
                if (backfilling && !historicalKickSent) {
                    log("Backfill: GET_DATA_RANGE unanswered — requesting history anyway (fail-open)")
                    sendHistoricalKick()
                }
            }, DATA_RANGE_GATE_MS)
        } else {
            sendHistoricalKick()
        }
        armBackfillTimeout()
        log("Backfill: session started — historical offload requested")
    }

    /** Fire SEND_HISTORICAL_DATA exactly once per backfill session (gate + fallback can both call). */
    private fun sendHistoricalKick() {
        if (historicalKickSent) return
        historicalKickSent = true
        send(CommandNumber.SEND_HISTORICAL_DATA, byteArrayOf(0), withResponse = true)
    }

    /**
     * Single gated entry point for every historical-offload kick: runs only when connected + bonded
     * and not mid-backfill, then through [BackfillPolicy] keyed by [BackfillTrigger] - AUTOMATIC kicks
     * are floored/backed-off/clock-skipped, manual/connect/foreground run at the 90s floor.
     * `clockUntrusted` recomputes fresh from [strapNewestTs] + wall clock every call, never cached.
     */
    private fun requestSync(trigger: BackfillTrigger) {
        val s = _state.value
        if (!canRequestSync(s.connected, s.bonded, backfilling)) return
        val clockUntrusted = isFutureDatedNewest(strapNewestTs, System.currentTimeMillis() / 1000L)
        if (!BackfillPolicy.shouldRun(
                trigger = trigger,
                nowSeconds = System.currentTimeMillis() / 1000.0,
                lastBackfillAtSeconds = lastBackfillAtMs?.let { it / 1000.0 },
                emptyStreak = emptySyncTracker.consecutiveEmptySyncs,
                clockUntrusted = clockUntrusted,
            )
        ) {
            log(
                "Backfill: skipped ($trigger) - policy floor not met " +
                    "(empty streak ${emptySyncTracker.consecutiveEmptySyncs}" +
                    "${if (clockUntrusted) ", clock future-dated" else ""})",
            )
            return
        }
        beginBackfill()
    }

    /**
     * Public "Sync now" entry point for a user-initiated manual offload. Forwards to the SAME gated
     * [requestSync] the auto-kick/periodic timer use, so it can never bypass the connected+bonded+
     * not-backfilling guard. Posted to the main looper since [beginBackfill] arms handler-scoped timers.
     */
    fun syncNow() {
        handler.post { requestSync(BackfillTrigger.MANUAL) }
    }

    /**
     * App-active entry point: call when NOOP comes to the foreground so opening the app pulls a
     * fresh sync instead of waiting on the 900s periodic timer. Forwards to the SAME gated
     * [requestSync], floored at 90s and never empty-streak/clock-suppressed - a safe, cheap no-op.
     */
    fun onForeground() {
        handler.post { requestSync(BackfillTrigger.FOREGROUND) }
    }

    /** Periodic-timer callback: re-runs the type-47 offload (the primary metric sync). */
    private fun triggerPeriodicBackfill() {
        requestSync(BackfillTrigger.PERIODIC)
        // Re-arm regardless so the cadence continues for the life of the connection. The delay is
        // battery-adaptive (stretched when low), read fresh at each re-arm.
        handler.postDelayed(periodicBackfillRunnable, nextBackfillDelayMs())
    }

    private fun startBackfillTimer() {
        handler.removeCallbacks(periodicBackfillRunnable)
        handler.postDelayed(periodicBackfillRunnable, nextBackfillDelayMs())
    }

    private fun stopBackfillTimer() {
        handler.removeCallbacks(periodicBackfillRunnable)
    }

    /**
     * Feed an offload frame to the Backfiller preserving exact arrival order. Frames are appended
     * synchronously (callback order) and drained sequentially by a single coroutine, so START/data/
     * END chunk assembly is never reordered.
     */
    private fun routeBackfillFrame(frame: ByteArray) {
        backfillFrameQueue.add(frame)
        if (backfillDraining) return
        backfillDraining = true
        ioScope.launch {
            // A throw from ingest() must NEVER leave backfillDraining stuck true (that would wedge the
            // offload - every later frame returns early and the queue never drains). finally guarantees
            // the flag is cleared even if a chunk handler throws.
            try {
                while (true) {
                    val f = backfillFrameQueue.poll() ?: break
                    try {
                        backfiller.ingest(f)
                    } catch (t: Throwable) {
                        log("Backfill: drain error (${t.message}) — skipping frame, offload continues")
                    }
                    // If the Backfiller consumed all historical data, exit the session cleanly.
                    if (backfilling && !backfiller.isBackfilling) {
                        handler.post { exitBackfilling("HISTORY_COMPLETE") }
                    }
                }
            } finally {
                backfillDraining = false
            }
        }
    }

    /**
     * Re-arm the idle watchdog. Called on every offload frame during backfill; if the strap goes
     * silent the timer fires and we exit the session.
     */
    private fun armBackfillTimeout() {
        handler.removeCallbacks(backfillTimeoutRunnable)
        handler.postDelayed(backfillTimeoutRunnable, BACKFILL_IDLE_TIMEOUT_MS)
    }

    private fun onBackfillTimeout() {
        // 5/MG: a session that timed out with ZERO offload frames means the strap never answered the
        // history request (seen on real hardware - the first request after connect can be swallowed).
        // Retry once with a clean teardown; after 2 attempts the 900s periodic timer owns it.
        if (connectedFamily == DeviceFamily.WHOOP5 && offloadFramesThisSession == 0 &&
            whoop5HistoryAttempts < 2 && _state.value.connected && _state.value.bonded
        ) {
            whoop5HistoryAttempts++
            backfiller.timeoutFired()
            backfilling = false
            _state.update { it.copy(backfilling = false, syncChunksThisSession = 0) }
            handler.removeCallbacks(backfillTimeoutRunnable)
            backfillFrameQueue.clear()
            log("Backfill: no history frames arrived — retrying request (attempt ${whoop5HistoryAttempts + 1})")
            // Bounded mid-attempt retry (whoop5HistoryAttempts < 2): AUTO_CONTINUE so the 90s event floor
            // can't suppress it — it's continuing THIS connect's offload, not a fresh periodic kick.
            handler.postDelayed({ requestSync(BackfillTrigger.AUTO_CONTINUE) }, WHOOP5_HISTORY_RETRY_DELAY_MS)
            return
        }
        backfiller.timeoutFired()
        exitBackfilling("timeout")
    }

    /** Tear down the backfill session. Does NOT auto-start live HR. */
    private fun exitBackfilling(reason: String) {
        if (!backfilling) return
        backfilling = false
        // A backfill just ended. Start (or extend) the deep-packet cooldown from this instant so any
        // type-0x2F records the strap flushes in the seconds after aren't miscounted as the live R22
        // stream - they're the offload's tail.
        lastOffloadFrameAtMs = System.currentTimeMillis()
        // Record an honest sync outcome so a cloud-free user can tell sync is working (or stuck):
        // HISTORY_COMPLETE stamps lastSyncAt + clears any error; an idle-watchdog timeout surfaces a
        // non-silent error. A plain disconnect mid-sync leaves both as-is (not a failure).
        val nowSec = System.currentTimeMillis() / 1000L
        // A sync that COMPLETED but banked NO sensor records means the strap isn't saving to flash (its
        // RTC lost sync) - surface the actionable fix instead of a silent "synced". "Banked nothing" =
        // decoded nothing AND persisted zero sensor rows, regardless of console-frame count. The sustained-
        // only banner guard still applies, so a strap that banked rows on an earlier cycle won't trip it.
        val (bankedSensorRecords, bankedNothingRaw) = classifyCompletedOffload(
            decodedChunks = decodedChunksThisSession,
            consoleChunks = consoleChunksThisSession,
            rowsPersisted = backfiller.sessionRowsPersisted,
        )
        // The empty tail of an auto-continue burst (consecutiveAutoContinues > 0) isn't a "banked
        // nothing" sync - an EARLIER session in the burst handed over real rows and this pass just
        // confirms we're caught up. Don't surface "charge to 100%", and don't count it toward the streak.
        val productiveBurstTail = consecutiveAutoContinues > 0
        val bankedNothing = reason == "HISTORY_COMPLETE" && bankedNothingRaw && !productiveBurstTail
        // Only escalate to the clock-lost banner once emptiness is SUSTAINED. A banking cycle (any
        // decoded records / rows persisted) clears the streak, so a single transient empty cycle on a
        // healthy strap stays silent. Track on every completed sync so banking cycles reset it.
        val sustainedEmpty = if (reason == "HISTORY_COMPLETE" && !productiveBurstTail)
            emptySyncTracker.recordCompletedSync(
                bankedSensorRecords = bankedSensorRecords,
                consoleOnly = bankedNothingRaw,
            ) else false
        if (bankedNothing) {
            val detail = if (consoleChunksThisSession >= 3)
                "console-only across $consoleChunksThisSession chunks"
            else "metadata-only, 0 sensor rows persisted"
            log(
                "Backfill: completed but the strap banked no sensor history ($detail); " +
                    "consecutive empty syncs = ${emptySyncTracker.consecutiveEmptySyncs}.",
            )
        }
        // A strap whose newest banked record is dated in the FUTURE (RTC relatched ahead) is future-dated
        // regardless of how this offload ended - a deep future-dated backlog times out as readily as it
        // completes. Compute the banner once so both outcomes name the real cause, not "strap went quiet".
        val futureClockBanner = futureDatedStrapBanner(strapNewestTs, nowSec)
        if (futureClockBanner != null) {
            val aheadH = ((strapNewestTs ?: 0L) - nowSec) / 3600
            log("Backfill: the strap's newest banked record is ${aheadH}h AHEAD of the wall clock - clock set in the future; showing the future-clock banner and importing nothing from this range.")
        }
        // Persist the HISTORY_COMPLETE instant so "Last synced N ago" survives a BLE-client recreation /
        // process restart and stops reverting to "Never".
        if (reason == "HISTORY_COMPLETE") NoopPrefs.setLastSyncAt(context, nowSec)
        // Write-health signal for the export: "Last sync" fires even on an empty/failed offload, so it
        // can't distinguish "0 rows, strap empty" from "0 rows, writes FAILED". Record the last time rows
        // landed, and the last time an offload STALLED on a persist failure (closed-DB-after-restore).
        runCatching {
            val p = NoopPrefs.of(context).edit()
            if (backfiller.sessionRowsPersisted > 0) p.putLong("sync.lastWriteOkAt", nowSec)
            if (backfiller.persistStalled) p.putLong("sync.lastWriteStalledAt", nowSec)
            p.apply()
        }
        // A WHOOP 5/MG whose firmware acks SEND_HISTORICAL_DATA but emits zero type-0x2F frames times out
        // every session - not a failure, live HR streams fine; history is just experimental on that
        // firmware. Route a 5/MG timeout through the empty-offload tracker so a sustained empty streak
        // reads "history experimental", not the WHOOP-4 "went quiet" error, and the bounce loop backs off.
        val isWhoop5 = connectedFamily == DeviceFamily.WHOOP5
        val bankedThisOffload = offloadFramesThisSession > 0 ||
            backfiller.sessionRowsPersisted > 0 || _state.value.deepPacketsThisSession > 0
        var whoop5HistoryExperimental = _state.value.historySyncExperimental
        if (reason == "timeout" && isWhoop5) {
            val crossed = whoop5EmptyOffload.recordOffload(bankedRecords = bankedThisOffload)
            whoop5HistoryExperimental = whoop5EmptyOffload.historyEmpty
            if (crossed) {
                log("Backfill: WHOOP 5/MG offload empty ${whoop5EmptyOffload.consecutiveEmpty}× — history sync is experimental on 5.0; surfacing 'connected, history experimental' (not a sync error) and backing off the bounce loop.")
            }
        } else if (reason == "HISTORY_COMPLETE" && isWhoop5 && bankedSensorRecords) {
            // A real HISTORY_COMPLETE with banked records proves the 5/MG offload IS working — recover.
            whoop5EmptyOffload.reset()
            whoop5HistoryExperimental = false
        }
        _state.update { when (reason) {
            "HISTORY_COMPLETE" -> it.copy(
                backfilling = false,
                syncChunksThisSession = ackedChunksThisSession,
                lastSyncAt = nowSec,
                // bankedNothing keeps its own sustained-empty precedence - future-dated is checked ONLY on
                // the banked-something path, so the two checks never compete for the same banner.
                lastSyncError = when {
                    bankedNothing && sustainedEmpty ->
                        "Synced, but your strap had no stored history to hand over - only its diagnostic output. This usually means its clock has lost sync, so it isn't saving data to flash. Fully charge it to 100%, then reconnect, and it should start banking again."
                    bankedNothing -> null   // banked nothing but not yet sustained - stay silent
                    // The strap banked records but its newest is dated implausibly in the future (RTC
                    // relatched ahead). Samples from that range are dropped so nothing is misfiled, but
                    // this path would otherwise report a clean sync and leave the user with no data and no reason why.
                    else -> futureClockBanner
                },
                historySyncExperimental = whoop5HistoryExperimental,
            )
            "timeout" -> it.copy(
                backfilling = false,
                syncChunksThisSession = ackedChunksThisSession,
                // On a history-experimental 5/MG this isn't a sync failure - suppress the "went quiet" error
                // (it's just the empty offload) and surface the experimental flag instead. A future-dated
                // WHOOP-4 times out on its deep future-dated backlog - prefer the future-clock banner over "went quiet".
                lastSyncError = if (isWhoop5) null
                    else futureClockBanner ?: "Sync interrupted - the strap went quiet. It will retry on the next sync.",
                historySyncExperimental = whoop5HistoryExperimental,
            )
            else -> it.copy(
                backfilling = false,
                syncChunksThisSession = ackedChunksThisSession,
                historySyncExperimental = whoop5HistoryExperimental,
            )
        } }
        handler.removeCallbacks(backfillTimeoutRunnable)
        backfillFrameQueue.clear()
        closeWhoop5BackfillCapture(flushSummary = true)
        log("Backfill: session ended — reason=$reason")
        // Inactivity reminder: read-only hook on the natural offload completion (no cadence change).
        // Only on a true HISTORY_COMPLETE - a timeout/disconnect didn't bring a fresh window.
        if (reason == "HISTORY_COMPLETE") {
            maybeBuzzInactivity()
            // Stress check-in: same read-only hook - fires StressOnsetDetector over the live R-R buffer.
            // Self-gates on the BiofeedbackPrefs master/auto toggles (inert when off).
            maybeNudgeStress()
            // On-device short-nap detection: same read-only hook - judges the freshly offloaded daytime
            // window and queues a confident nap for review. Self-gates on NapPrefs (OFF by default);
            // never auto-writes a sleep session.
            maybeDetectNaps()
        }
        // Success-side summary: failures (decoded-to-0) were logged but never successes, so a strap log
        // couldn't tell a banking strap from a broken one. Emit the per-session persistence tally whenever
        // anything actually landed.
        Backfiller.sessionSummaryLine(
            backfiller.sessionRowsPersisted, backfiller.sessionMotionRows, backfiller.sessionSkinTempRows,
            backfiller.sessionNights,
        )?.let {
            log(it)
            // Fold this session's drained rows into the persisted ALL-TIME tally at the single summary
            // emit point, so the Connection readout can show install-lifetime progress beside the
            // per-session count (which resets on every reconnect). Unconditional - not gated on the Connection test mode.
            testCentre.noteDrainedRows(backfiller.sessionRowsPersisted)
        }

        // Connection test mode: the offload OUTCOME the readout's lastOffloadResult id binds. Gated
        // zero-cost (the CONNECTION bool read before any string is built). A timeout/idle-cap exit is a
        // STALL; HISTORY_COMPLETE with rows is a clean complete; with none it's an empty cycle.
        if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
            val rows = backfiller.sessionRowsPersisted
            val result = when {
                reason == "timeout" -> "stalled (idle timeout, rows=$rows so far)"
                reason == "HISTORY_COMPLETE" && rows > 0 -> "complete rows=$rows nights=${backfiller.sessionNights}"
                reason == "HISTORY_COMPLETE" -> "empty (console only, no sensor records)"
                else -> "$reason rows=$rows"
            }
            log("offload result=$result", com.noop.testcentre.TestDomain.CONNECTION)
        }

        // RE-POLLUTION: this session's ingest gate dropped bad-clock records, so the strap has a wandering
        // clock and may have banked similar garbage on an older build whose gate was weaker. Arm a heal
        // re-run so the next analyze tick purges it; AppViewModel honours the flag on its next tick.
        if (backfiller.sessionDroppedImplausible > 0) {
            NoopPrefs.setTsHealPending(context, true)
        }

        // Auto-continue spin-detector: did THIS session move the strap's trim cursor? Compare the
        // Backfiller's current high-water trim against where it stood when the previous session ended.
        // A frozen cursor (console-only / refusing to trim) means don't re-kick - it would spin forever.
        val currentTrim = backfiller.lastAckedTrim
        val trimAdvanced = currentTrim != null && currentTrim != lastSessionEndTrim
        lastSessionEndTrim = currentTrim
        // A session ending on the 60s idle-cap or HISTORY_COMPLETE, still connected with backlog left and
        // the trim advancing, re-kicks immediately instead of waiting the 900s floor (some straps slice an
        // overnight offload into many small HISTORY_COMPLETE completions, so this must fire on those too).
        // The streak is NOT reset unconditionally on HISTORY_COMPLETE - a sliced offload would never engage
        // the 6-per-connection cap - it clears only once [shouldAutoContinue] proves we're caught up.
        // Bounded by the cap + spin-detector either way.
        if (reason == "timeout" || reason == "HISTORY_COMPLETE") {
            maybeAutoContinueBackfill(trimAdvanced, backfiller.sessionRowsPersisted)
        }
    }

    /**
     * Evaluate (and, if warranted, fire) an immediate back-to-back backfill after a 60s idle-cap exit
     * or a HISTORY_COMPLETE. The "more backlog remains" test needs the persisted data frontier from the
     * repository, so it reads on [ioScope] then re-kicks on the main looper via [requestSync] (same
     * gated path the auto-kick/periodic timer use, so this can't double-start). Decision logic is the
     * pure, unit-testable [shouldAutoContinue]; [trimAdvanced] is the spin-detector signal from
     * [exitBackfilling]. Uses the un-floored [BackfillTrigger.AUTO_CONTINUE] so the 15-min periodic
     * floor can't suppress an in-progress drain.
     */
    private fun maybeAutoContinueBackfill(trimAdvanced: Boolean, rowsPersisted: Int) {
        val s = _state.value
        if (!s.connected || !s.bonded) return
        val newest = strapNewestTs
        val count = consecutiveAutoContinues
        ioScope.launch {
            val frontier = runCatching { repository.latestHrSampleTs(deviceId) }.getOrNull()
            val wallNow = System.currentTimeMillis() / 1000L   // real wall clock, at decision time
            // Local only - NOT cached on the instance. A future-dated newest makes the AUTOMATIC periodic/
            // strap kicks near-useless for THIS decision; [requestSync] recomputes its own verdict fresh
            // from [strapNewestTs] on every call, so a stale value here can't leak forward.
            val clockUntrusted = isFutureDatedNewest(newest, wallNow)
            val stillConnected = _state.value.connected && _state.value.bonded
            if (!shouldAutoContinue(
                    stillConnected = stillConnected,
                    strapNewestTs = newest,
                    ourFrontierTs = frontier,
                    wallNowUnix = wallNow,
                    lastTrimAdvanced = trimAdvanced,
                    consecutiveCount = count,
                    rowsPersistedThisSession = rowsPersisted,
                )
            ) {
                // Name the stop honestly when the future-clock gate is what ended the chain - otherwise the
                // log goes quiet after one pass and a strap-log export can't tell "caught up" from
                // "future-dated range refused". Fires ONLY when the chain would otherwise have continued
                // (still connected, rows banked, trim advanced, under the cap), so a frozen-trim/cap/
                // disconnect stop is never misattributed.
                if (stillConnected && rowsPersisted > 0 && trimAdvanced &&
                    count < MAX_AUTO_CONTINUES && clockUntrusted   // just set above from isFutureDatedNewest(newest, wallNow)
                ) {
                    val aheadH = ((newest ?: wallNow) - wallNow) / 3600L
                    log(
                        "Backfill: not auto-continuing - the strap-reported newest banked record " +
                            "reads ${aheadH}h AHEAD of the wall clock, so the range is future-dated and " +
                            "the strap clock is likely wrong. Stopping after one pass instead of " +
                            "chasing future-dated ranges; the periodic sync keeps draining across connects.",
                    )
                }
                // No re-kick: this is the real "done draining" signal, so clear the streak here so the NEXT
                // deep backlog gets a fresh budget of re-kicks. Reset ONLY here, not on every
                // HISTORY_COMPLETE, so a strap that slices one offload into many completions can't keep
                // resetting the cap and spin forever. EXCEPTION: if the per-connection CAP is what stopped
                // us, leave the streak at/over the cap so it stays engaged for the rest of this connection -
                // zeroing it would let a runaway strap spin again.
                if (count < MAX_AUTO_CONTINUES) {
                    handler.post { consecutiveAutoContinues = 0 }
                }
                return@launch
            }
            handler.post {
                // Re-check on the main looper: a real backfill may already have re-started (periodic) in
                // the gap. requestSync's own gate handles that, but skip the log/counter churn if so.
                if (backfilling) return@post
                consecutiveAutoContinues += 1
                log(
                    "Backfill: auto-continuing — the trim advanced and the strap is still " +
                        "handing over real records (frontier ${frontier ?: "?"}, strap-reported newest " +
                        "${newest ?: "?"}); re-kicking offload $consecutiveAutoContinues/$MAX_AUTO_CONTINUES " +
                        "without waiting the 15-min floor.",
                )
                requestSync(BackfillTrigger.AUTO_CONTINUE)
            }
        }
    }

    /**
     * Ack one HISTORY_END chunk so the strap may trim it. Confirmed write (with response): the strap
     * forgets the chunk once this lands (link-layer half of safe-trim; decoded already persisted).
     * Ack form: HISTORICAL_DATA_RESULT(23) payload = `[0x01] + end_data`, where end_data is the
     * verbatim 8 bytes of HISTORY_END metadata.data[10:18].
     */
    private fun ackHistoricalChunk(trim: Long, endData: ByteArray) {
        val payload = ByteArray(1 + endData.size)
        payload[0] = 0x01
        System.arraycopy(endData, 0, payload, 1, endData.size)
        send(CommandNumber.HISTORICAL_DATA_RESULT, payload, withResponse = true)
        // Progress signal for the "Syncing strap history..." UI. Republish every 10th chunk only - the
        // FGS notification re-posts on every LiveState emission. Runs on the single serial drain
        // coroutine, so the counter is race-free.
        ackedChunksThisSession += 1
        if (ackedChunksThisSession % 10 == 0) {
            _state.update { it.copy(syncChunksThisSession = ackedChunksThisSession) }
        }
        log("Backfill: acked chunk trim=$trim")
    }

    // ====================================================================================
    // MARK: GATT crash-safety - dead-binder guards
    // ====================================================================================

    /**
     * Run a raw GATT operation, swallowing the dead-binder exceptions that escape `BluetoothGatt` once
     * the OS Bluetooth radio is turned off mid-link, and route into full teardown if one fires.
     *
     * Turning Bluetooth off doesn't disconnect NOOP's `BluetoothGatt`; the next write hits a dead
     * binder and throws an unchecked `DeadObjectException` the GATT layer never declared, crashing
     * the app on the next buzz/sync. Also seen: `IllegalStateException` (adapter off) and
     * `SecurityException` (permission revoked). On ANY of these the link is gone - tear down.
     *
     * @return the block's boolean on success, or `false` if the binder was dead (callers' "rejected
     *   by stack" path is then inert - the queues are cleared and `gatt` is null).
     */
    private fun safeGatt(reason: String, block: () -> Boolean): Boolean =
        try {
            block()
        } catch (t: Throwable) {
            // DeadObjectException / IllegalStateException / SecurityException all mean the link is
            // unusable. Catching Throwable here is deliberate: any GATT call that throws AT ALL once
            // the binder is dead must not crash the app — there's no recovery, only teardown. The
            // policy (always tear down) is single-sourced in shouldTeardownOnGattThrow so it's testable.
            if (shouldTeardownOnGattThrow(t)) {
                log("GATT op '$reason' failed (${t.javaClass.simpleName}); tearing down link")
                teardownAfterGattFailure()
            }
            false
        }

    /**
     * Full teardown after a raw GATT call threw because the binder died. Reached from the catch path,
     * so it must do everything [handleDisconnect]+[reset] do AND cancel the two BUSY-retry kicks - a
     * still-pending [drainWriteRetryRunnable]/[drainCccdRetryRunnable] would otherwise fire after the
     * link is dead and re-enter the dead write, throwing again. Marks the disconnect intentional so no
     * auto-rescan loops against a powered-off radio.
     */
    private fun teardownAfterGattFailure() {
        // Cancel any scheduled BUSY-retry kicks BEFORE handleDisconnect/reset clears the queues, so a
        // retry can't re-enter drainWriteQueue/drainCccdQueue against the dead binder.
        handler.removeCallbacks(drainWriteRetryRunnable)
        handler.removeCallbacks(drainCccdRetryRunnable)
        intentionalDisconnect = true   // don't auto-rescan against a dead/off radio
        // reset() (inside handleDisconnect) clears writeInFlight + the write/cccd queues + pendingRetry
        // and cancels the keep-alive/backfill timers; handleDisconnect publishes connected=false and
        // closes + nulls gatt. Also drop the GattOps wrapper so a late call can't reach the dead gatt.
        handleDisconnect(BluetoothGatt.GATT_FAILURE)
        gattOps = null
    }

    // ====================================================================================
    // MARK: Disconnect / teardown
    // ====================================================================================

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(status: Int) {
        // Reboot trail: if a user reboot is in flight, this drop is the strap acting on it. Log how long
        // the link stayed up (a real reboot drops within ~1-2s) and cancel the no-disconnect watchdog.
        // rebootRequestedAtMs stays set so the handshake can compute the round-trip once it completes.
        rebootRequestedAtMs?.let { t ->
            rebootWatchdog?.let { handler.removeCallbacks(it) }; rebootWatchdog = null
            val ms = SystemClock.elapsedRealtime() - t
            // A dropped LINK only proves a reboot on WHOOP 5.0 (verified fw). On WHOOP 4.0 it's unconfirmed -
            // opcode 29/payload01 was observed to drop the BLE link WITHOUT power-cycling the strap (sensor
            // stayed on) - so don't claim a reboot; report the drop honestly.
            log(if (connectedFamily == DeviceFamily.WHOOP5)
                "reboot: link dropped ${ms}ms after send — reboot took effect; awaiting reconnect"
            else
                "reboot: link dropped ${ms}ms after send — but a WHOOP 4.0 reboot isn't confirmed; a dropped link alone isn't proof (a real reboot also switches the sensor light off). Awaiting reconnect")
        }
        // Connection test mode: capture whether THIS attempt ever reached STATE_CONNECTED before the state
        // copy below clears `connected`. Android delivers both a post-connect involuntary drop and a
        // connect that never reached connected through this ONE seam, so `connected` is the only signal
        // splitting them (true = involuntary drop, false = failed connect that never came up).
        val wasConnected = _state.value.connected
        // Capture BEFORE reset() wipes didBond: a bonded fast-path connect that dropped without ever
        // reaching a session means the OS bond is stale - fall back to a scan so a new/re-paired strap
        // can still be found (and "No WHOOP strap found" guidance still appears).
        val staleDirectBond = bondedDirectAttempt && !didBond
        bondedDirectAttempt = false

        // Bond-loop detection: read the bond timestamp before it's cleared below. The tell is a CONNECTION
        // TIMEOUT landing within seconds of a genuine bond - bond -> drop -> rescan -> bond -> drop,
        // forever. Require the stack to classify the drop as GATT_CONN_TIMEOUT, not any non-zero status,
        // so a one-off radio blip isn't mistaken for the loop. Trips into the existing re-pair guide.
        val bondedAtSnapshot = bondedAtMs
        val msSinceBond = bondedAtSnapshot?.let { System.currentTimeMillis() - it }
        val connTimedOut = status == GATT_CONN_TIMEOUT && !intentionalDisconnect
        if (postBondLoop.connectionEnded(
                wasBonded = bondedAtSnapshot != null,
                msSinceBond = msSinceBond,
                timedOut = connTimedOut,
            )
        ) {
            log("Bond-loop: ${postBondLoop.consecutiveBondTimeouts} bond-then-timeout cycles — surfacing the re-pair guide and pausing auto-reconnect")
            // The loop is bond -> drop -> rescan -> bond -> drop, forever, draining the battery. Surfacing
            // the guide alone left the involuntary-drop rescan running - pause auto-reconnect too (a user
            // Connect or a genuine bond re-arms it). Does NOT touch the bond/parse path; the stale OS
            // pairing is the problem.
            autoReconnectPausedForBondLoop = true
            bondLoopPausedAtMs = System.currentTimeMillis()   // the hole-4 salvage probe covers this pause too (one bounded cycle)
            if (_state.value.reconnectGuide == null) {
                _state.update { it.copy(
                    reconnectGuide = """
                    Your strap keeps connecting and then dropping a second later. This is almost always a stale Bluetooth pairing - usually after a WHOOP firmware update, or the official WHOOP app holding the strap. NOOP works fine once it's re-paired:

                    1. Quit the official WHOOP app (or turn off Bluetooth on that phone).
                    2. Open Settings → Bluetooth, find your WHOOP, and Forget / Unpair it.
                    3. Tap the band repeatedly until its LEDs flash blue (pairing mode).
                    4. Come back here and tap Connect.
                    """.trimIndent()
                ) }
            }
        }
        bondedAtMs = null   // cleared after the bond-loop detector above read it

        // The OTHER unbounded loop: a strap that connects + subscribes but never bonds, then self-drops
        // (status 0) BEFORE the escalating bond watchdog fires, advancing neither give-up counter. Feed
        // this drop into the SAME give-up counter so the loop is bounded and hands off to the identical
        // re-pair guide. `didBond` is still valid here ([reset] clears it below); the check excludes our
        // own localTerminate bounce to avoid double-counting a cycle.
        if (shouldCountNeverBondedSelfDrop(
                wasConnected = wasConnected,
                didBond = didBond,
                intentionalDisconnect = intentionalDisconnect,
                staleDirectBond = staleDirectBond,
                status = status,
                alreadyPausedForBondLoop = autoReconnectPausedForBondLoop,
            ) && bondWatchdogBackoff.recordBounce()
        ) {
            log("Strap connects and subscribes but never finishes pairing, then self-drops before the bond watchdog fires (${bondWatchdogBackoff.consecutiveBounces} cycles) — pausing auto-reconnect and surfacing the re-pair guide")
            autoReconnectPausedForBondLoop = true
            bondLoopPausedAtMs = System.currentTimeMillis()   // the hole-4 salvage probe covers this pause too
            if (_state.value.reconnectGuide == null) {
                _state.update { it.copy(
                    reconnectGuide = """
                    Your strap connects but never finishes pairing with NOOP, so it drops and retries in a loop. This is almost always a stale Bluetooth pairing, usually after a WHOOP firmware update, or the official WHOOP app holding the strap. NOOP works fine once it's re-paired:

                    1. Quit the official WHOOP app (or turn off Bluetooth on that phone).
                    2. Open Settings → Bluetooth, find your WHOOP, and Forget / Unpair it.
                    3. Tap the band repeatedly until its LEDs flash blue (pairing mode).
                    4. Come back here and tap Connect.
                    """.trimIndent()
                ) }
            }
        }

        // Persist anything buffered before tearing down. Runs on the IO scope.
        ioScope.launch { flushLive(); flushStandardHr(closing = true) }

        // Reset all per-connection state and clear UI flags, including the syncing pill (a dropped link
        // mid-offload must not leave "Syncing strap history..." stuck on). clearedBiometrics() also blanks
        // HR / R-R / the rolling buffer so a stale heart rate or R-R strip can't outlive the link.
        // atomic update: LiveState is written from multiple threads (binder/main/IO).
        _state.update { it.clearedBiometrics().copy(
            connected = false, bonded = false, encryptedBond = false,
            backfilling = false, syncChunksThisSession = 0,
            charging = null,        // a stale charging flag must not outlive the link
            strapFirmware = null,   // nor stale firmware/layout versions
            historyLayoutVersion = null,
            packSocPct = null,      // nor the pack the dropped strap was reporting
            packSerial = null,
            packMillivolts = null,
        ) }
        packReplyLogged = false
        // Multi-WHOOP: the link is down - clear the published connected address so SourceCoordinator's
        // adoption sink can't re-fire on a stale strap id. Same for the serial, so the next strap's
        // identity is never resolved against the last one's.
        _connectedPeripheralAddress.value = null
        _connectedStrapSerial.value = null
        _connectedStrapHardwareRev.value = null
        reset()

        // close() can itself throw DeadObjectException on a dead binder - teardown must NEVER throw,
        // or the catch in safeGatt re-raises and we're back to the crash. Swallow it.
        try { gatt?.close() } catch (t: Throwable) { log("gatt.close() threw ${t.javaClass.simpleName} during teardown (ignored)") }
        gatt = null
        gattOps = null
        cmdCharacteristic = null

        if (autoReconnectPausedForBondLoop) {
            // The bond keeps being refused, so auto-reconnect is paused: stop hammering a strap that can't
            // bond (the epitaph + paused hint were already surfaced when the give-up tripped). The user
            // re-arms it by tapping Connect ([clearPairingHintForUserConnect]). Do NOT schedule a reconnect.
            log("Disconnected (status=$status); auto-reconnect paused (strap keeps refusing to pair; tap Connect once it's free)")
            if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
                log("connect down (uptime ends)", com.noop.testcentre.TestDomain.CONNECTION)
                log("reconnect paused=bondLoop (strap refusing bond)", com.noop.testcentre.TestDomain.CONNECTION)
            }
        } else if (!intentionalDisconnect) {
            // Connection test mode: count + describe the reconnect churn, mark the link down for the uptime
            // readout. Gated zero-cost; diagnostic only, reconnect logic below is unchanged. A real drop
            // AFTER a session (wasConnected) increments the count then emits `connect down` +
            // `reconnect n=N`; a FAILED connect (never reached STATE_CONNECTED) emits `failedConnect` at
            // the CURRENT count WITHOUT incrementing it.
            if (wasConnected) connReconnectCount += 1
            if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
                val reason = when (status) {
                    GATT_CONN_TIMEOUT -> "connectionTimeout"
                    GATT_CONN_TERMINATE_LOCAL_HOST -> "localTerminate"   // our own bounce (e.g. bond watchdog)
                    else -> "status$status"
                }
                if (wasConnected) {
                    log("connect down (uptime ends)", com.noop.testcentre.TestDomain.CONNECTION)
                    log("reconnect n=$connReconnectCount reason=$reason", com.noop.testcentre.TestDomain.CONNECTION)
                } else {
                    log("reconnect n=$connReconnectCount failedConnect reason=$reason", com.noop.testcentre.TestDomain.CONNECTION)
                }
            }
            if (staleDirectBond) {
                staleDirectFailures++
                log("Disconnected (status=$status) before the bonded fast-path reached a session — stale OS bond (attempt $staleDirectFailures); falling back to a scan")
                lastDevice = null
                // Two consecutive wiped-bond failures means the strap really reset its pairing (firmware
                // update / official WHOOP app re-bond), not a one-off transient drop. Surface the
                // forget+re-pair guide; keep scanning so a fresh re-pair is picked up automatically and
                // the guide clears on next connect.
                if (staleDirectFailures >= 2) {
                    _state.update { it.copy(
                        reconnectGuide = """
                        Your strap's Bluetooth pairing was reset - usually by a WHOOP firmware update, or the official WHOOP app reconnecting. NOOP works fine on the new firmware; you just need to re-pair:

                        1. Quit the official WHOOP app (or turn off Bluetooth on that phone).
                        2. Open Settings → Bluetooth, find your WHOOP, and Forget / Unpair it.
                        3. Tap the band repeatedly until its LEDs flash blue (pairing mode).
                        4. Come back here and tap Connect.
                        """.trimIndent()
                    ) }
                }
                // Route through scheduleReconnect so this timer is cancellable and can't tear down a link
                // that returns before it fires. Capped-exponential backoff keyed on staleDirectFailures
                // (3->6->12->24->48->60s), not a fixed 3s: a persistent stale OS bond can only be cleared
                // by a USER forget+re-pair, so hammering connect->fail->teardown every 3s just burns CPU +
                // BLE radio. The first retry stays fast (attempt 1 = 3s) for a transient drop; a genuine
                // bond resets staleDirectFailures to 0, so the backoff resets too and a fresh re-pair is
                // auto-picked-up within <=60s.
                scheduleReconnect(ReconnectBackoff.nextDelayMs(staleDirectFailures)) { connect(selectedModel) }
                return
            }
            val dev = lastDevice
            if (dev != null && isPreferred(dev)) {
                // Reconnect DIRECTLY to the strap we already know (autoConnect=true): the OS reconnects as
                // soon as it's reachable, with no scan or advertisement required - fixing the dropout loop
                // where a bonded strap that wasn't advertising could never be re-found by scanning.
                // Multi-WHOOP: gated on [isPreferred] so an involuntary reconnect can never re-attach to a
                // strap that's no longer the active-pinned one; on the single-WHOOP path [preferredAddress]
                // is null, so isPreferred is always true and this is byte-for-byte unchanged.
                // Capped-exponential backoff (3,6,12,24,48,60s) so a strap that's genuinely out of range
                // stops hammering BLE. The counter resets on the next STATE_CONNECTED and on an explicit user Connect.
                val directDelay = nextReconnectDelayMs()
                // The first reconnect attempts use the fast direct connect (autoConnect=false), same path
                // as the initial connect. A 5/MG the OS still holds bonded + ACL-connected never re-emits
                // the advertisement/connection-complete event autoConnect=true waits for, so passive mode
                // stalls. Falls back to autoConnect=true from the third attempt for a strap genuinely out of range.
                // Don't escalate to PASSIVE on attempt count alone. A band the OS still holds ACL-connected
                // (co-resident with the WHOOP app) stalls under autoConnect regardless of count - keep it
                // DIRECT; only a genuinely-out-of-range band escalates to PASSIVE for power.
                val aclHeld = isStrapAclHeld(dev.address)
                val passiveReconnect = passiveReconnectDecision(failedReconnectAttempts, aclHeld)
                log("Disconnected (status=$status); reconnecting ${if (passiveReconnect) "passively" else "directly"} in ${directDelay / 1000}s (attempt $failedReconnectAttempts${if (aclHeld) ", ACL-held" else ""})")
                // Cancellable backoff timer (see scheduleReconnect).
                scheduleReconnect(directDelay) { connectToDevice(dev, autoConnect = passiveReconnect) }
            } else {
                val rescanDelay = nextReconnectDelayMs()
                log("Disconnected (status=$status); rescanning in ${rescanDelay / 1000}s (attempt $failedReconnectAttempts)")
                // Cancellable backoff timer (see scheduleReconnect).
                scheduleReconnect(rescanDelay) { connect(selectedModel) }
            }
        } else {
            log("Disconnected (intentional)")
            // A user-initiated teardown ends the churn count for the run and marks the link down so the
            // uptime readout reads "not connected" rather than a stale uptime. Gated zero-cost.
            connReconnectCount = 0
            if (testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) {
                log("connect down (intentional)", com.noop.testcentre.TestDomain.CONNECTION)
            }
        }
    }

    /** Clear per-connection state. */
    private fun reset() {
        didBond = false
        connectHandshakeDone = false
        clockReference.reset()
        seq.set(0)
        writeQueue.clear()
        cccdQueue.clear()
        writeInFlight = false
        pendingRetry = null
        writeRetries = 0
        // Cancel any scheduled BUSY-retry kicks so a queued retry can't fire after teardown and
        // re-enter a dead write/descriptor.
        handler.removeCallbacks(drainWriteRetryRunnable)
        handler.removeCallbacks(drainCccdRetryRunnable)
        resubscribedSinceData = false
        cccdInFlight = false
        cccdRetries = 0
        sessionStarted = false
        // Clear the onMtuChanged dedup so the first MTU callback of the NEXT connection - even to the
        // same strap with the same granted mtu - is never mistaken for a duplicate of the last one.
        lastMtuValue = -1
        lastMtuAtMs = 0L
        // The strap forgets the realtime-HR toggle across a disconnect; the post-bond branch re-arms it
        // from [wantsRealtime]. Clear only the "what we last sent" flag - the screen WANTS
        // ([screenWantsRealtime]/[wantsRealtime]) are intent and must survive a reconnect.
        realtimeArmed = false

        // Reset offload state so the next connect starts a fresh session. Timers are handler-posted,
        // so cancel them here.
        backfillStarted = false
        backfilling = false
        backfillDraining = false
        backfillFrameQueue.clear()
        strapNewestTs = null
        offloadFramesThisSession = 0
        lastOffloadFrameAtMs = 0L   // don't carry a stale cooldown reference into the next session
        historicalKickSent = false
        whoop5HistoryAttempts = 0
        // A fresh connection earns a fresh empty-offload streak - a strap that was history-empty last
        // session might bank this time (or vice-versa). Published flag cleared in [disconnectedLiveState].
        whoop5EmptyOffload.reset()
        // The auto-continue streak + spin-detector are per-connection - a fresh connection earns a fresh
        // budget of back-to-back re-kicks and restarts its trim-advance comparison from scratch.
        consecutiveAutoContinues = 0
        lastSessionEndTrim = null
        // A mid-offload link drop must still flush the capture file (summary already logged or not —
        // don't double-log it here).
        closeWhoop5BackfillCapture(flushSummary = false)
        handler.removeCallbacks(backfillTimeoutRunnable)
        stopBackfillTimer()
        stopKeepAlive()
        // The bonded-handshake watchdog is per-connection - cancel it so a pending bounce can't fire
        // after the link is already down (it would otherwise re-enter a dead/null gatt).
        cancelBondWatchdog()

        // Fresh reassembler per connection: stops a partial/garbage frame left over from one session
        // wedging the live stream after a reconnect, so the keep-alive's link-bounce actually recovers
        // a frozen stream.
        reassembler.reset()
    }

    // ====================================================================================
    // MARK: Helpers
    // ====================================================================================

    /** Coerce a parsed value to an Int list (rr_intervals may arrive as List<Int> or IntArray). */
    @Suppress("UNCHECKED_CAST")
    private fun intArrayValue(v: Any?): List<Int>? = when (v) {
        is List<*> -> v.mapNotNull { (it as? Number)?.toInt() }
        is IntArray -> v.toList()
        else -> null
    }

    /** Coerce a parsed value to a Double (battery_pct may arrive as Double or Int). */
    private fun doubleValue(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // MARK: 5/MG raw backfill capture (opt-in research aid)
    //
    // Records every frame of a 5/MG backfill session as one JSONL line (parsed fields + raw hex) so
    // real users can contribute the ground-truth material the puffin biometric decode needs. Gated
    // on PuffinExperiment.isCaptureEnabled (default OFF); appends across sessions with per-session
    // ids; rotates at the cap; fail-soft - capture can never break the sync it observes.

    @Volatile private var captureWriter: java.io.BufferedWriter? = null
    @Volatile private var captureDisabled = false
    @Volatile private var captureLines = 0
    private var captureSessionId = ""
    private val captureSummary = BackfillCaptureSummary()

    private fun startBackfillCapture() {
        if (captureWriter != null || captureDisabled) return
        runCatching {
            val f = java.io.File(context.filesDir, WHOOP5_CAPTURE_FILE)
            // Rotate at the cap: keep one previous generation, then start fresh.
            if (f.exists() && f.length() > WHOOP5_CAPTURE_MAX_BYTES) {
                val old = java.io.File(context.filesDir, "$WHOOP5_CAPTURE_FILE.1")
                old.delete()
                f.renameTo(old)
            }
            captureWriter = java.io.BufferedWriter(java.io.FileWriter(f, true))
            captureLines = 0
            captureSessionId = "${connectedFamily?.name?.lowercase() ?: "unknown"}-${System.currentTimeMillis()}"
            captureSummary.reset()
            log("Capture: backfill capture started ($captureSessionId)")
        }.onFailure {
            captureDisabled = true
            log("Capture: could not open capture file (${it.message}) — capture disabled")
        }
    }

    private fun writeBackfillCapture(characteristic: String, frame: ByteArray, parsed: com.noop.protocol.ParsedFrame) {
        val w = captureWriter ?: return
        runCatching {
            // Reuse the whoop-rs-decoded frame the inbound loop already produced (no re-parse). The
            // annotation (typeName/crcOk/parsed) is whoop-rs's for the live-decodable types; the raw `hex`
            // is always the authoritative research payload.
            captureSummary.record(parsed.typeName, parsed.crcOk, frame.size, characteristic, frame.toHex())
            val line = BackfillCaptureJsonl.encode(
                BackfillCaptureRecord(
                    capturedAtMs = System.currentTimeMillis(),
                    sessionId = captureSessionId,
                    characteristic = characteristic,
                    typeName = parsed.typeName,
                    crcOk = parsed.crcOk,
                    offload = isOffloadFrame(frame, connectedFamily),
                    size = frame.size,
                    parsed = parsed.parsed,
                    hex = frame.toHex(),
                ),
            )
            synchronized(w) {
                w.write(line)
                w.newLine()
                if (++captureLines % 100 == 0) w.flush()
            }
            if (captureLines >= WHOOP5_CAPTURE_MAX_LINES) {
                log("Capture: line cap reached — capture paused until next session")
                closeWhoop5BackfillCapture(flushSummary = false)
            }
        }.onFailure {
            captureDisabled = true
            closeWhoop5BackfillCapture(flushSummary = false)
            log("Capture: write failed (${it.message}) — capture disabled")
        }
    }

    @Volatile private var eventLogDisabled = false

    /**
     * Durable append-only log of WHOOP 5.0/MG EVENT (type 48 / 0x30) frames, for deep-data protocol
     * research. EVENT frames are rare and still mostly uncatalogued - e.g. the bi-hourly 56-byte
     * record that tracks the nightly sleep-SpO2 cycle. Decoding them needs (raw bytes, ground-truth)
     * pairs across weeks, which the session-scoped backfill capture can't provide. Keeps ONLY the
     * ~150 tiny EVENT frames a day in its own file; gated on the same capture pref, one JSONL line
     * per frame (`{"ts_ms":...,"char":...,"hex":"..."}`); rotates at the cap.
     */
    private fun writeWhoop5EventLogIfEvent(characteristic: String, frame: ByteArray) {
        if (eventLogDisabled || !isWhoop5EventFrame(frame)) return
        if (!PuffinExperiment.from(context).isCaptureEnabled) return
        runCatching {
            val f = java.io.File(context.filesDir, WHOOP5_EVENT_LOG_FILE)
            if (f.exists() && f.length() > WHOOP5_EVENT_LOG_MAX_BYTES) {
                val old = java.io.File(context.filesDir, "$WHOOP5_EVENT_LOG_FILE.1")
                old.delete()
                f.renameTo(old)
            }
            val hex = frame.toHex()
            f.appendText("{\"ts_ms\":${System.currentTimeMillis()},\"char\":\"$characteristic\",\"hex\":\"$hex\"}\n")
        }.onFailure {
            // A diagnostics log must never affect the connection path: disable for this process.
            eventLogDisabled = true
            log("Capture: event log write failed (${it.message}) — event log disabled")
        }
    }

    @Volatile private var deepBufferDisabled = false

    /**
     * Durable append-only log of WHOOP 5.0/MG high-rate R22 deep buffers - the big type-0x2F buffers
     * (>= 1 KB: 1244-B 6-axis IMU, 2140-B optical) that carry tens-of-Hz sensor data, kept RAW so a
     * byte-perfect decoder can be reversed offline from many (raw buffer, wall-clock) pairs. The
     * historical decoder pulls only the 1 Hz gravity vector from these and discards the rest. One
     * JSONL line per buffer (`{"ts_ms":...,"strap_ts":...,"size":...,"offload":...,"char":...,
     * "hex":"..."[,"imu":{...}]}`); `strap_ts` is the unix second stamped at frame offset 15 - the key
     * for aligning a buffer with wearer activity. `imu` is the decoded activity summary, present only
     * on the 1244-B buffer ([PuffinDeepBufferLog.decodedImuField]). Rotates at a soft cap keeping one
     * previous generation.
     */
    private fun writeWhoop5DeepBufferIfBig(characteristic: String, frame: ByteArray, isOffload: Boolean) {
        if (deepBufferDisabled || !PuffinDeepBufferLog.isDeepBuffer(frame)) return
        if (!PuffinExperiment.from(context).isCaptureEnabled) return
        runCatching {
            val f = java.io.File(context.filesDir, WHOOP5_DEEPBUFFER_FILE)
            if (f.exists() && f.length() > WHOOP5_DEEPBUFFER_MAX_BYTES) {
                val old = java.io.File(context.filesDir, "$WHOOP5_DEEPBUFFER_FILE.1")
                old.delete()
                f.renameTo(old)
            }
            val strapTs = PuffinDeepBufferLog.strapTs(frame)?.toString() ?: "null"
            val hex = frame.toHex()
            // Decode the 1244-B IMU buffer inline so each captured line carries its activity summary
            // (cadence/energy/jerk/gyro) beside the raw hex - self-checking (raw <-> decode), no stored
            // table, migration, or downstream gate. The 2140-B optical buffer stays raw (layout not decoded yet).
            val imu = PuffinDeepBufferLog.decodedImuField(frame)
            f.appendText(
                "{\"ts_ms\":${System.currentTimeMillis()},\"strap_ts\":$strapTs,\"size\":${frame.size}," +
                    "\"offload\":$isOffload,\"char\":\"$characteristic\",\"hex\":\"$hex\"$imu}\n",
            )
        }.onFailure {
            // A diagnostics log must never affect the connection path: disable for this process.
            deepBufferDisabled = true
            log("Capture: deep-buffer log write failed (${it.message}) — deep-buffer log disabled")
        }
    }

    private fun closeWhoop5BackfillCapture(flushSummary: Boolean) {
        val w = captureWriter ?: return
        captureWriter = null
        runCatching { synchronized(w) { w.flush(); w.close() } }
        if (flushSummary) {
            log("Capture: session frame counts — ${captureSummary.countsText()}")
            val unknown = captureSummary.unknownSamplesText()
            if (unknown != "none") log("Capture: UNKNOWN type samples — $unknown")
        }
    }

    private fun log(s: String, domain: com.noop.testcentre.TestDomain? = null) {
        // A diagnostic log line must NEVER crash the app: log() runs on the GATT binder thread and the
        // background reconnect service, so an uncaught throw here takes the WHOLE process down.
        // Belt-and-suspenders - nothing in here may propagate.
        try {
            // Scrub personal identifiers FIRST so a user can safely share the strap log, THEN apply the
            // optional Test Centre domain tag in front of the already-safe line.
            val safe = taggedStrapLogLine(redactPii(s), domain)
            // logcat is opt-in (Settings → Strap → "Debug logging"); default OFF so normal users don't
            // emit the strap log to the system log. The in-app ring buffer below always records.
            if (debugLogcat) Log.d(TAG, safe)
            // Mirror into the in-app ring buffer (format under the lock — SimpleDateFormat isn't
            // thread-safe, created per-call so no field is shared across threads).
            synchronized(logBuffer) {
                logBuffer.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(System.currentTimeMillis())}  $safe")
                while (logBuffer.size > LOG_BUFFER_MAX) logBuffer.removeFirst()
            }
        } catch (t: Throwable) {
            // Last resort: note that a log line failed, without risking another throw. Never rethrow.
            runCatching {
                synchronized(logBuffer) {
                    logBuffer.addLast("[log error: ${t.javaClass.simpleName}]")
                    while (logBuffer.size > LOG_BUFFER_MAX) logBuffer.removeFirst()
                }
            }
        }
    }

    /** Scrub personal identifiers from a strap-log line so it's safe to share publicly: BLE MAC
     *  addresses are masked to their first + last byte, and the WHOOP SERIAL - carried in its device
     *  name ("WHOOP 4C1594026") - is removed. Applied at the single log sink so EVERY line is covered.
     *  MACs require colons, so hex command payloads are untouched; short dotted model names ("WHOOP
     *  4.0"/"5.0") don't match the serial pattern. */
    private fun redactPii(s: String): String = redactStrapLogPii(s)

    /**
     * Write a line into the SAME in-app strap-log ring buffer the user exports via [exportLogText],
     * from an ISOLATED BLE source that must never import or share state with this client.
     * The coordinator injects this as a closure so generic-HR lifecycle lines land in the
     * one log the user copies for a bug report.
     */
    fun externalLog(s: String, domain: com.noop.testcentre.TestDomain? = null) { log(s, domain) }

    /** Emit one Connection & Sync test-mode bond-state line, gated zero-cost behind testCentre.active(CONNECTION).
     *  The gate (one SharedPreferences bool) is read BEFORE the tagged string is built, so this is a no-op when
     *  the mode is off. Diagnostic only - it never changes the bond path. */
    private fun emitConnectionBondState(detail: String) {
        if (!testCentre.active(com.noop.testcentre.TestDomain.CONNECTION)) return
        log("bondState $detail", com.noop.testcentre.TestDomain.CONNECTION)
    }

    /** Snapshot of the recent strap log, newest last, for the "Share strap log" diagnostics export. */
    fun exportLogText(): String = synchronized(logBuffer) { logBuffer.joinToString("\n") }
}

// PII scrubbers for the shareable strap log. Kept at FILE scope (not inside WhoopBleClient) so
// they're unit-testable without constructing the Android-only BLE client.
//
//   - MAC: keep the first + LAST octet, mask the four middle octets. The regex captures exactly
//     TWO groups (first octet, last octet), so the replacement must reference $1 and $2 only.
//   - WHOOP serial: carried in the device name ("WHOOP 4C1594026"); dotted model names
//     ("WHOOP 4.0") are too short to match.
private val PII_MAC_RE = Regex("([0-9A-Fa-f]{2}):[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:([0-9A-Fa-f]{2})")
private val PII_WHOOP_SERIAL_RE = Regex("WHOOP (\\d[0-9A-Za-z]{5,})")

/**
 * The payload of a WHOOP 4.0 COMMAND_RESPONSE: the bytes after `[type,seq,cmd,origin_seq,result]`
 * (payload starts at absolute offset 9) up to the crc32 trailer at `length` (u16 LE at
 * frame[1..2]). null when too short to carry any payload. File-scope so it unit-tests without
 * the Android-only BLE client.
 */
internal fun whoop4CommandResponsePayload(frame: ByteArray): ByteArray? {
    if (frame.size < 3) return null
    val length = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
    val start = 9   // SOF(1) + len(2) + crc8(1) + type,seq,cmd,origin_seq,result(5)
    if (length > frame.size || start >= length) return null
    return frame.copyOfRange(start, length)
}

/** Space-separated lowercase hex of a COMMAND_RESPONSE payload, for the raw-hex diagnostic fallback
 *  when a readback payload doesn't decode. null when the frame carries no payload. */
internal fun whoop4AlarmReadbackPayloadHex(frame: ByteArray): String? =
    whoop4CommandResponsePayload(frame)?.takeIf { it.isNotEmpty() }
        ?.joinToString(" ") { "%02x".format(it) }

/** Plausibility gate for a readback epoch: a real armed alarm is near-now, so anything outside
 *  2017..2100 (1_500_000_000 to 4_102_444_800, inclusive) is garbage or a strap with no alarm armed -
 *  the caller falls back to the raw-hex line rather than logging a misleading date. */
internal fun isPlausibleAlarmEpoch(epoch: Long): Boolean = epoch in 1_500_000_000L..4_102_444_800L

/**
 * Extract the armed-alarm epoch from a GET_ALARM_TIME (cmd 67) COMMAND_RESPONSE. The WHOOP 4.0
 * response layout is UNDOCUMENTED, so this tries the two shapes the firmware could plausibly
 * answer with - the SET_ALARM_TIME mirror (`[form 0x01][u32 LE epoch]...`, matching the 9-byte
 * payload we arm with) first, then a bare leading u32 LE - accepting a candidate only when it
 * passes [isPlausibleAlarmEpoch]. Anything else returns null and the caller logs raw hex instead.
 * Pinned by `AlarmReadbackDecodeTest`.
 */
internal fun whoop4ArmedAlarmEpoch(frame: ByteArray): Long? {
    val payload = whoop4CommandResponsePayload(frame) ?: return null
    fun u32le(at: Int): Long? {
        if (payload.size < at + 4) return null
        return (payload[at].toLong() and 0xFFL) or
            ((payload[at + 1].toLong() and 0xFFL) shl 8) or
            ((payload[at + 2].toLong() and 0xFFL) shl 16) or
            ((payload[at + 3].toLong() and 0xFFL) shl 24)
    }
    if (payload.isNotEmpty() && payload[0] == 0x01.toByte()) {
        u32le(1)?.takeIf { isPlausibleAlarmEpoch(it) }?.let { return it }
    }
    return u32le(0)?.takeIf { isPlausibleAlarmEpoch(it) }
}

/**
 * True when a GET_ALARM_TIME readback explicitly reports NO alarm stored - the epoch field
 * decodes to 0 in the same shapes [whoop4ArmedAlarmEpoch] reads (SET-mirror `[0x01][u32=0]`
 * first, then a bare `u32=0`). This is the strap's "nothing armed" sentinel, distinct from a
 * genuinely unparseable payload. Only consulted AFTER [whoop4ArmedAlarmEpoch] returns null;
 * pinned by `AlarmReadbackDecodeTest`.
 */
internal fun whoop4ReadbackReportsNoAlarm(frame: ByteArray): Boolean {
    val payload = whoop4CommandResponsePayload(frame) ?: return false
    fun u32le(at: Int): Long? {
        if (payload.size < at + 4) return null
        return (payload[at].toLong() and 0xFFL) or
            ((payload[at + 1].toLong() and 0xFFL) shl 8) or
            ((payload[at + 2].toLong() and 0xFFL) shl 16) or
            ((payload[at + 3].toLong() and 0xFFL) shl 24)
    }
    if (payload.isNotEmpty() && payload[0] == 0x01.toByte()) {
        return u32le(1)?.let { it == 0L } ?: false
    }
    return u32le(0)?.let { it == 0L } ?: false
}

/** Local wall-clock render for the readback log line ("EEE HH:mm zzz") so the armed + strap-reports
 *  lines read as one sequence. */
internal fun alarmReadbackLocalTime(epochSec: Long): String =
    java.text.SimpleDateFormat("EEE HH:mm zzz", java.util.Locale.US)
        .format(java.util.Date(epochSec * 1000L))

/** Mask MAC addresses and WHOOP serials in a strap-log line before it's shown/exported.
 *  TOTAL - never throws: a redaction failure returns a safe placeholder rather than leaking the
 *  raw line or crashing the caller. The MAC regex captures exactly two groups (first + last
 *  octet), so the replacement references $1/$2 only. */
internal fun redactStrapLogPii(s: String): String = try {
    s.replace(PII_MAC_RE, "$1:••:••:••:••:$2")
        .replace(PII_WHOOP_SERIAL_RE, "WHOOP <serial>")
} catch (t: Throwable) {
    "[redaction error - line withheld]"
}

/**
 * Whether to ask the OS to pair with a strap in [bondState] before connecting.
 *
 * Only a strap with NO bond: it has no link key, so the encrypted handshake write is refused and the
 * bond watchdog bounces the link before anything can complete. BONDING is already in progress and
 * BONDED needs nothing, so both are left alone. Pure and file-scope so it unit-tests without
 * constructing the BLE client.
 */
internal fun shouldRequestOsBond(bondState: Int): Boolean = bondState == BluetoothDevice.BOND_NONE

/**
 * Whether the live 0x2A37 profile's R-R should be persisted for a strap of [family].
 *
 * The historical record carries every beat already, stamped by the strap's own clock, so banking the
 * live copy as well stores each beat twice under two clocks. The live copy is kept only for a 5/MG
 * whose offload is empty ([historyEmpty]), where it is the one source there is. Pure and file-scope so
 * it unit-tests without constructing the BLE client.
 */
internal fun persistsLiveRr(family: DeviceFamily, historyEmpty: Boolean): Boolean =
    family == DeviceFamily.WHOOP5 && historyEmpty

/** Prefix a compact, parseable domain marker onto an already-redacted strap-log line, or return it
 *  unchanged when no domain is given. The export filters on this "[<id>] " marker. Pure and
 *  file-scope so it unit-tests without constructing the BLE client. */
internal fun taggedStrapLogLine(redacted: String, domain: com.noop.testcentre.TestDomain?): String =
    if (domain == null) redacted else "[${domain.id}] $redacted"

/**
 * A connected WHOOP 5/MG whose firmware acks SEND_HISTORICAL_DATA but emits ZERO type-0x2F offload
 * frames. Live HR streams fine over 0x2A37, but the historical offload is empty, so every session
 * times out the 60s idle watchdog and would surface the WHOOP-4 "strap went quiet" error - even
 * though nothing is wrong, 5/MG history is simply experimental on that firmware. The idle link also
 * lets the 120s liveness watchdog bounce-disconnect/rescan every ~2 min in a thrash loop.
 *
 * This pure tracker counts CONSECUTIVE empty 5/MG offloads. Once [quietThreshold] is reached it
 * reports "history-empty" so the caller surfaces an honest experimental state instead of a sync
 * error, and backs off the bounce loop. Any offload with real records clears the streak.
 */
internal class Whoop5EmptyOffloadTracker(
    /** Consecutive empty 5/MG offloads before we treat the strap as history-empty. 2 (not 1): the very
     *  first offload after connect can race the strap waking its flash, so one empty cycle is noise. */
    private val quietThreshold: Int = 2,
) {
    var consecutiveEmpty = 0
        private set

    /** True once [quietThreshold] consecutive empty offloads have been seen - the link is up + live HR is
     *  flowing but the 5/MG history offload is empty. Drives the honest flag AND the bounce backoff. */
    var historyEmpty = false
        private set

    /** Record a completed/timed-out 5/MG offload. [bankedRecords] = this offload routed real offload
     *  frames / persisted rows. Returns true if THIS call freshly crossed the threshold (log/surface once).
     *  A banking offload resets everything. */
    fun recordOffload(bankedRecords: Boolean): Boolean {
        if (bankedRecords) {
            consecutiveEmpty = 0
            historyEmpty = false
            return false
        }
        consecutiveEmpty++
        if (!historyEmpty && consecutiveEmpty >= quietThreshold) {
            historyEmpty = true
            return true
        }
        return false
    }

    /** Clear all suspicion - a fresh connect, or the user re-requested a sync. */
    fun reset() {
        consecutiveEmpty = 0
        historyEmpty = false
    }
}
