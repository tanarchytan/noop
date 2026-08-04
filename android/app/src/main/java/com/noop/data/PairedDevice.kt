package com.noop.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Device-registry schema. Added to Room as the v7 -> v8 additive migration.
 *
 * `id` is the SAME string used as `deviceId` in every sample table's (deviceId, ts) key, so a
 * device's raw samples are already isolated by id — no per-row source column is needed. The
 * existing WHOOP keeps id "my-whoop" (zero sample-row migration); seeded `active` by MIGRATION_7_8.
 */

/**
 * A device the user has paired. PK = [id] (== `deviceId` in the sample tables). [capabilities] is
 * the comma-joined set of [Metric] rawValues. Exactly one row is `active` at a time (invariant I1,
 * enforced in [DeviceRegistry.setActive]).
 *
 * Two INDEPENDENT axes: [status] is presence (BLE only), [dataIncluded] is inclusion (read scope
 * only). They never gate each other, so "removed, data kept" is expressible.
 */
@Entity(tableName = "pairedDevice")
data class PairedDeviceRow(
    @PrimaryKey
    val id: String,
    val brand: String,
    val model: String,
    val nickname: String?,
    /**
     * The strap's stable BLE peripheral identifier ([android.bluetooth.BluetoothDevice] MAC address).
     * Lets the BLE client pin a connect to ONE specific strap and look up a freshly-paired device.
     * Nullable; a row stays NULL until the strap is (re)paired.
     */
    val peripheralId: String? = null,
    val sourceKind: String,
    val capabilities: String, // comma-joined Metric rawValues, e.g. "hr,hrv,sleep"
    /** PRESENCE, BLE only: scan, auto-connect, the device picker. Never narrows a read. */
    val status: String,
    val addedAt: Long, // unix seconds
    val lastSeenAt: Long, // unix seconds
    /** INCLUSION, read scope only ([WhoopRepository.importedSourceIdsFor]). Toggled per dataset in
     *  Data Sources; false hides this dataset's rows from every chart without deleting one. The SQL
     *  default matches the migration's, so a created and a migrated table agree. */
    @ColumnInfo(defaultValue = "1")
    val dataIncluded: Boolean = true,
    /** The strap's own serial (GATT 0x2A25), read over a connection because it is not advertised. The
     *  identity that survives an address change; null means unverified, and an unverified row is never
     *  merged with anything. Resolution lives in [StrapIdentity]. */
    val serial: String? = null,
    /** The strap's hardware-revision string (GATT 0x2A27), stored verbatim. It is what separates a 5.0
     *  from an MG, and so what an electrode-only capability may be offered on; the prefix table that
     *  classifies it stays in whoop-rs. Null until a connection reads it. */
    val hardwareRev: String? = null,
)

/**
 * Override of which device owns a given local day's displayed/scored metrics (invariant I2: a
 * day's scores are never blended across sources). PK = [day] ("YYYY-MM-DD"). [locked] = true means
 * an explicit decision (import-overlap resolution / user choice) that the resolver must honour over
 * its priority default.
 */
@Entity(tableName = "dayOwnership")
data class DayOwnershipRow(
    @PrimaryKey
    val day: String,
    val deviceId: String,
    val locked: Boolean = false,
)

/** PRESENCE of a paired device, stored as the lowercase enum name: [active] = the one connect
 *  target, [paired] = known, [archived] = removed (never scanned for, never auto-connected, never
 *  offered in the picker). Inclusion in a read is [PairedDeviceRow.dataIncluded], not this. */
enum class DeviceStatus { active, paired, archived }

/** How a device's data reaches the store. Stored as the enum name.
 *  [oura] = an EXPERIMENTAL Oura ring live BLE source with its OWN scanner/GATT (never touches the
 *  WHOOP client); decodes the ring's own signals, runs NOOP's own scoring, and surfaces an honest
 *  "needs pairing" state instead of Oura's encrypted scores when the install key is absent. Free-text
 *  column: existing rows never carry it, so no migration was needed. */
// `activityFile`: a GPX/TCX/FIT activity file under the `activity-file` device. Ranked BELOW
// `fileImport` (a whole-day WHOOP CSV export) by the day-owner resolver, so a 90-minute ride never
// displaces a full-day HR source; it owns a day only when nothing else has data.
// `legacy`: the bucket holding rows written before the device registry existed. It names a pile of
// data, not a device, so it is never listed as one and never owns a day over a real source. When a
// strap adopts it (its peripheralId is set) it becomes a `liveBLE` device, which is the only moment a
// device is known to exist.
enum class SourceKind { liveBLE, historyBLE, cloudImport, fileImport, oura, activityFile, legacy }

/** The kinds that are an actual device the user owns, as opposed to a file, a service or a data
 *  bucket. Presentation and device-scoped actions filter on this, so an id with no device behind it
 *  can never render as one. */
val DEVICE_SOURCE_KINDS: Set<String> =
    setOf(SourceKind.liveBLE, SourceKind.historyBLE, SourceKind.oura).map { it.name }.toSet()

/** True when [row] is hardware the user owns. The WHOOP import sink ([WhoopRepository.WHOOP_SOURCE])
 *  is a data bucket whatever kind an old install left on it, so it is never one — it has no strap to
 *  connect to, and offering it is what put a phantom band in the device list. */
fun isDeviceRow(row: PairedDeviceRow): Boolean =
    row.sourceKind in DEVICE_SOURCE_KINDS && row.id != WhoopRepository.WHOOP_SOURCE

/** The straps the app may reach over BLE: hardware ([isDeviceRow]) that has not been removed. The
 *  PRESENCE axis, so it drives the picker, the scan and auto-connect and never a read — read scope is
 *  [PairedDeviceRow.dataIncluded] via [WhoopRepository.importedSourceIdsFor]. */
fun connectableDevices(devices: List<PairedDeviceRow>): List<PairedDeviceRow> =
    devices.filter { isDeviceRow(it) && it.status != DeviceStatus.archived.name }

/** A canonical metric a source can provide — drives capability-aware UI + the day-owner resolver.
 *  Stored as the enum name inside the comma-joined `capabilities` string. */
enum class Metric { hr, hrv, spo2, skinTemp, steps, sleep, strainLoad }
