package com.noop.data

/**
 * Small in-test schema descriptors for the cross-fork import tests. Each shape is a
 * `table -> ordered [DataBackup.SchemaColumn]s` map, exactly what [DataBackup.readSchema] returns at
 * runtime from `PRAGMA table_info` — so the pure router ([DataBackup.foreignBackupKind]) and planner
 * ([DataBackup.planRowCopyImport]) can be driven with no live SQLite, no Robolectric, no binary blobs.
 *
 * The four shapes mirror the real stores a user could pick to restore into this app's own Android build:
 *   - [ownAndroidStore]   — this app's own Room store (also the reconcile TARGET);
 *   - [iosGrdbStore]      — the iOS/GRDB store (same column names by the cross-platform parity contract);
 *   - [behindAndroidFork] — an Android fork a few migrations BEHIND this build;
 *   - [aheadAndroidFork]  — an Android fork AHEAD of this build, carrying upstream-absent markers.
 *
 * Columns are realistic subsets of the live entities (see `Entities.kt`), trimmed to the fields that
 * make the intersection / warning behaviour observable. The distinguishing facts, per the schema map:
 *   - the own store's `dailyMetric` carries `skinTempDevC`, NOT `skinTempAbsC`, and has the
 *     on-device-only `spo2Red` / `spo2Ir`; it also carries the `ppgWaveformSample` table;
 *   - the own store's `stepSample` / `ppgHrSample` carry a `synced` flag that is NOT NULL with NO
 *     default — Room emits no SQL default for a Kotlin `= 0`, the exact shape that made a naive
 *     `INSERT OR IGNORE` drop whole tables' rows (the critical regression these tests lock);
 *   - the iOS/GRDB store lacks the on-device-only `spo2Red` / `spo2Ir` (imports never bank them),
 *     carries a GRDB-only collector table, and its `stepSample` / `ppgHrSample` lack the Android-only
 *     `synced` column — so reconciling it must FILL `synced` with a default, never drop the rows;
 *   - the BEHIND fork has no `ppgWaveformSample`, no `stepSample` / `ppgHrSample`, no `seq` on
 *     `rrInterval`, and none of the v7 sleep aggregate columns — but carries NO upstream-absent marker
 *     (a version gap, not a content divergence);
 *   - the AHEAD fork carries the two upstream-absent markers: the `spo2PctSample` table and
 *     `dailyMetric.skinTempAbsC`.
 */
internal object CrossForkSchemaFixtures {

    // A nullable-no-default column: present in the source -> copied; absent -> SQLite fills NULL. The
    // declared type is irrelevant to such columns (it only matters for a filled NOT NULL-no-default one),
    // so the concise helper defaults it.
    private fun col(name: String, type: String = "INTEGER") =
        DataBackup.SchemaColumn(name, type, notNull = false, hasDefault = false)

    private fun cols(vararg names: String): List<DataBackup.SchemaColumn> = names.map { col(it) }

    // A NOT NULL column with NO schema default (Room's `= 0`). Source-absent -> the planner MUST fill it
    // with a typed zero literal, else INSERT OR IGNORE drops the row.
    private fun notNullNoDefault(name: String, type: String = "INTEGER") =
        DataBackup.SchemaColumn(name, type, notNull = true, hasDefault = false)

    /** Shape A — this app's own Android (Room) store. Also the reconcile TARGET. */
    val ownAndroidStore: Map<String, List<DataBackup.SchemaColumn>> = mapOf(
        // Housekeeping — the planner must filter these out; the router reads `room_master_table`.
        "room_master_table" to cols("id", "identity_hash"),
        "android_metadata" to cols("locale"),
        "sqlite_sequence" to cols("name", "seq"),
        // Data.
        "hrSample" to cols("deviceId", "ts", "bpm"),
        "rrInterval" to cols("deviceId", "ts", "rrMs", "seq"),
        "dailyMetric" to cols("deviceId", "day", "restingHr", "avgHrv", "skinTempDevC", "spo2Red", "spo2Ir"),
        "ppgWaveformSample" to cols("deviceId", "ts", "samples"),
        "sleepSession" to cols("deviceId", "startTs", "endTs", "efficiency"),
        // These two carry the NOT NULL-no-default `synced` flag (Room's `= 0`), the shape that made a
        // naive INSERT OR IGNORE drop the whole table's rows when a source lacked the column.
        "stepSample" to listOf(
            col("deviceId"), col("ts"), col("counter"), col("activityClass"), notNullNoDefault("synced"),
        ),
        "ppgHrSample" to listOf(
            col("deviceId"), col("ts"), col("bpm"), col("conf"), notNullNoDefault("synced"),
        ),
    )

    /** Shape B — the iOS/GRDB store. Same logical column names by the parity contract; `grdb_migrations`
     *  bookkeeping; a GRDB-only collector table (`healthKitCursor`); `dailyMetric` lacks the on-device-only
     *  `spo2Red` / `spo2Ir`; `stepSample` / `ppgHrSample` LACK the Android-only `synced` column, so
     *  reconciling this store must FILL `synced` with a default rather than drop the rows. */
    val iosGrdbStore: Map<String, List<DataBackup.SchemaColumn>> = mapOf(
        "grdb_migrations" to cols("identifier"),
        "hrSample" to cols("deviceId", "ts", "bpm"),
        "rrInterval" to cols("deviceId", "ts", "rrMs", "seq"),
        "dailyMetric" to cols("deviceId", "day", "restingHr", "avgHrv", "skinTempDevC"),
        "ppgWaveformSample" to cols("deviceId", "ts", "samples"),
        "sleepSession" to cols("deviceId", "startTs", "endTs", "efficiency"),
        "healthKitCursor" to cols("id", "anchor"),
        "stepSample" to cols("deviceId", "ts", "counter", "activityClass"),
        "ppgHrSample" to cols("deviceId", "ts", "bpm", "conf"),
    )

    /** Shape C — a behind Android fork (Room), a few migrations behind this build: no `ppgWaveformSample`,
     *  no `stepSample` / `ppgHrSample`, no `seq` on `rrInterval`, `dailyMetric` without the v7 sleep
     *  aggregates. Carries NO upstream-absent marker, so the live import keeps it on the ordinary
     *  open-time-migrator restore. */
    val behindAndroidFork: Map<String, List<DataBackup.SchemaColumn>> = mapOf(
        "room_master_table" to cols("id", "identity_hash"),
        "hrSample" to cols("deviceId", "ts", "bpm"),
        "rrInterval" to cols("deviceId", "ts", "rrMs"),
        "dailyMetric" to cols("deviceId", "day", "restingHr", "avgHrv"),
        "sleepSession" to cols("deviceId", "startTs", "endTs", "efficiency"),
    )

    /** Shape D — an ahead Android fork (Room), ahead of this build: carries the upstream-absent
     *  `spo2PctSample` table and `dailyMetric.skinTempAbsC`, so it can't be brought forward by this
     *  build's Room migrator — the content-based router reroutes it to the row-copy reconcile. Being an
     *  Android Room fork, its `stepSample` / `ppgHrSample` DO carry the NOT NULL-no-default `synced`. */
    val aheadAndroidFork: Map<String, List<DataBackup.SchemaColumn>> = mapOf(
        "room_master_table" to cols("id", "identity_hash"),
        "hrSample" to cols("deviceId", "ts", "bpm"),
        "rrInterval" to cols("deviceId", "ts", "rrMs", "seq"),
        "dailyMetric" to cols(
            "deviceId", "day", "restingHr", "avgHrv", "skinTempDevC", "skinTempAbsC", "spo2Red", "spo2Ir",
        ),
        "ppgWaveformSample" to cols("deviceId", "ts", "samples"),
        "sleepSession" to cols("deviceId", "startTs", "endTs", "efficiency"),
        "spo2PctSample" to cols("deviceId", "ts", "pct"),
        "stepSample" to listOf(
            col("deviceId"), col("ts"), col("counter"), col("activityClass"), notNullNoDefault("synced"),
        ),
        "ppgHrSample" to listOf(
            col("deviceId"), col("ts"), col("bpm"), col("conf"), notNullNoDefault("synced"),
        ),
    )

    /** The `sqlite_master` table-name set the router reads. */
    fun tablesOf(shape: Map<String, List<DataBackup.SchemaColumn>>): Set<String> = shape.keys

    /** The `dailyMetric` column-name set the router reads for the fork-marker column probe. */
    fun dailyMetricColumnsOf(shape: Map<String, List<DataBackup.SchemaColumn>>): Set<String> =
        shape["dailyMetric"]?.map { it.name }?.toSet() ?: emptySet()
}
