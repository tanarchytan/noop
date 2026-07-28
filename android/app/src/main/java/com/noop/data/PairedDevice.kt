package com.noop.data

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
     * Nullable; the seeded "my-whoop" row stays NULL until the strap is (re)paired.
     */
    val peripheralId: String? = null,
    val sourceKind: String,
    val capabilities: String, // comma-joined Metric rawValues, e.g. "hr,hrv,sleep"
    val status: String,
    val addedAt: Long, // unix seconds
    val lastSeenAt: Long, // unix seconds
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

/** Lifecycle of a paired device. Stored as the lowercase enum name. */
enum class DeviceStatus { active, paired, archived }

/** How a device's data reaches the store. Stored as the enum name.
 *  [oura] = an EXPERIMENTAL Oura ring live BLE source with its OWN scanner/GATT (never touches the
 *  WHOOP client); decodes the ring's own signals, runs NOOP's own scoring, and surfaces an honest
 *  "needs pairing" state instead of Oura's encrypted scores when the install key is absent. Free-text
 *  column: existing rows never carry it, so no migration was needed. */
// `activityFile`: a GPX/TCX/FIT activity file under the `activity-file` device. Ranked BELOW
// `fileImport` (a whole-day WHOOP CSV export) by the day-owner resolver, so a 90-minute ride never
// displaces a full-day HR source; it owns a day only when nothing else has data.
enum class SourceKind { liveBLE, historyBLE, cloudImport, fileImport, oura, activityFile }

/** A canonical metric a source can provide — drives capability-aware UI + the day-owner resolver.
 *  Stored as the enum name inside the comma-joined `capabilities` string. */
enum class Metric { hr, hrv, spo2, skinTemp, steps, sleep, strainLoad }
