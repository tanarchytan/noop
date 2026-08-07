package com.noop

import android.app.Application
import android.util.Log
import com.noop.ble.SourceCoordinator
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DeviceRegistry
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import com.noop.ui.UnitPrefs
import kotlinx.coroutines.runBlocking

/**
 * Application entry point.
 *
 * NOOP is a fully on-device WHOOP companion: it connects to the strap over BLE and persists
 * everything locally via Room. There is no network layer (the opt-in AI Coach aside).
 *
 * The data layer ([WhoopRepository]) and the BLE client ([WhoopBleClient]) are owned **here**, at the
 * process level, rather than by the Activity-scoped AppViewModel. That is what lets a connection keep
 * streaming when the app is backgrounded or closed: [com.noop.ble.WhoopConnectionService] holds the
 * process up with a foreground notification, and both it and the UI share this one BLE client. The
 * macOS app gets the same outcome for free — its `AppModel` is an app-level `@StateObject` kept alive
 * by the menu-bar extra.
 */
class NoopApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Record any uncaught crash to a file so it rides along in the shareable strap log — a
        // device-specific crash (e.g. Insights) is otherwise lost to an unreachable logcat.
        CrashCapture.install(this)
        // Apply a staged backup restore before the Room store is opened, so the file swap runs with no
        // live connection or background coroutine that could re-open a torn file mid-swap. No-op normally.
        WhoopDatabase.applyPendingRestore(this)
        // Seed the unit/Effort display state before any screen reads it, so the first composition
        // shows the stored preference rather than the Metric default.
        UnitPrefs.reload(this)
    }

    /** Process-wide Room-backed store. One instance shared by the UI and the background service. */
    val repository: WhoopRepository by lazy {
        WhoopRepository(WhoopDatabase.get(this).whoopDao())
    }

    /** Process-wide device registry over the same Room DB — the single source of the active device id. */
    val deviceRegistry: DeviceRegistry by lazy { DeviceRegistry(WhoopDatabase.get(this)) }

    /**
     * The WRITE id: the source live BLE samples are banked under, resolved once at startup from the
     * registry and falling back to [WhoopBleClient.DEFAULT_DEVICE_ID] while the registry holds no
     * device — the import sink, which every read covers, and which
     * [SourceCoordinator.connectedPeripheralChanged] replaces with the strap's own id the moment one
     * connects. READS never use it, so this fallback cannot narrow what the user sees.
     * Guarded blocking call: any failure is swallowed, so startup can never be broken by this.
     */
    val activeDeviceId: String by lazy {
        runCatching { runBlocking { deviceRegistry.activeDeviceId() } }
            .onFailure { Log.w("NoopApplication", "activeDeviceId resolve failed; using fallback", it) }
            .getOrNull() ?: WhoopBleClient.DEFAULT_DEVICE_ID
    }

    /** Process-wide BLE client. Owns the GATT connection and outlives any single Activity/ViewModel. */
    val ble: WhoopBleClient by lazy {
        WhoopBleClient(applicationContext, repository = repository, deviceId = activeDeviceId).apply {
            // Apply the persisted "Debug logging" preference at the composition root so the low-level
            // client never has to read the UI/prefs layer. Default OFF — see WhoopBleClient.debugLogcat.
            debugLogcat = NoopPrefs.debugLogging(applicationContext)
        }
    }

    /**
     * Multi-source coordinator (Phase 1B): runs exactly one device's live BLE at a time, driven by the
     * registry's active device id. DORMANT whenever the active device is the WHOOP (the default and every
     * single-WHOOP install), so the existing WHOOP flow is untouched. Only when a non-WHOOP Oura ring
     * becomes active does it pause WHOOP and run the isolated [com.noop.ble.OuraLiveSource].
     *
     * Wired to the EXISTING [ble] entry points via closures — it never touches [WhoopBleClient]
     * internals. Strap live HR is pushed into the same [ble] state flow the UI observes via
     * [WhoopBleClient.publishExternalLiveHr]. [SourceCoordinator.start] reconciles once against the
     * current active id at launch (a no-op for a single-WHOOP install); the Devices screen (next task)
     * calls [SourceCoordinator.onActiveDeviceChanged] after a setActive.
     *
     * Multi-WHOOP identity adoption: AppViewModel's init collects [WhoopBleClient.connectedPeripheralAddress]
     * (distinctUntilChanged) into [SourceCoordinator.connectedPeripheralChanged] — the Kotlin analogue of
     * macOS wiring `BLEManager.connectedPeripheralUUID` into the coordinator's adoption sink. Kept beside
     * the other `ble`-flow collectors there (this Application owns no CoroutineScope of its own).
     */
    val sourceCoordinator: SourceCoordinator by lazy {
        SourceCoordinator(
            context = applicationContext,
            registry = deviceRegistry,
            repository = repository,
            liveSink = { hr, rr -> ble.publishExternalLiveHr(hr, rr) },
            // reconnect on the PERSISTED family, not the WhoopModel.WHOOP4 default - otherwise a
            // 5/MG WHOOP->WHOOP switch rescans the wrong service and misses the 5/MG direct-bond fast
            // path (status=133 on an OS-bonded strap). Mirrors macOS AppModel.scan() reading the persisted
            // "selectedWhoopModel". Same-strap switches now adopt in place (no reconnect) via the
            // coordinator, so this only fires for a genuinely different WHOOP.
            startWhoop = { ble.connect(persistedWhoopModel()) },
            stopWhoop = { ble.disconnect() },
            // Multi-WHOOP (MW-2/MW-3): pin the connection to the active WHOOP's persisted address and
            // re-attribute live samples to it on a WHOOP→WHOOP switch. Both inert on the single-WHOOP
            // path — the coordinator only invokes them for a non-legacy WHOOP / a non-null peripheralId.
            setWhoopPreferredAddress = { addr -> ble.preferredAddress = addr },
            setWhoopActiveDeviceId = { id -> ble.setActiveDeviceId(id) },
            // The family a lazily-created strap row records, so its skin-temp scale and reconnect
            // service are right from the first connect. Same persisted value startWhoop reconnects on.
            whoopFamily = { persistedWhoopModel() },
            // Generic-HR connect lifecycle → the SAME in-app strap log the user exports, so a
            // "connected but no data" report is no longer blind to the Polar/Wahoo/etc path.
            straplog = { ble.externalLog(it) },
            // A generic strap's standard battery (0x180F) → the same live battery field the WHOOP uses.
            batterySink = { pct -> ble.publishExternalBattery(pct) },
        )
    }

    /** The WHOOP family last seen advertising, persisted by [WhoopBleClient.persistSelectedModel] under
     *  "noop.selectedWhoopModel" in the shared noop_prefs store. Defaults to [WhoopModel.WHOOP4] when
     *  unset or unparseable (the historical connect() default), so a fresh install is unchanged. Used to
     *  reconnect on the right service after a WHOOP->WHOOP switch. */
    private fun persistedWhoopModel(): WhoopModel =
        NoopPrefs.of(this).getString("noop.selectedWhoopModel", null)
            ?.let { runCatching { WhoopModel.valueOf(it) }.getOrNull() }
            ?: WhoopModel.WHOOP4
}
