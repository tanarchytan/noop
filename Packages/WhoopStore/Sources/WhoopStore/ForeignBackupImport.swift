import Foundation
import GRDB

/// Content-based cross-fork / cross-platform `.noopbak` restore by ROW COPY (#222 family).
///
/// UNVERIFIED ON macOS — READ FIRST. This file was authored on a non-Apple host and has NOT been
/// compiled or run through `swift test` / `xcodebuild`. It is the Swift twin of Android's
/// `DataBackup.reconcileForeignBackup` / `planRowCopyImport`, added to honour the cross-platform
/// parity contract, but the CI-gated `swift-packages` leg and the app build must both be run on a Mac
/// before merge (`cd Packages/WhoopStore && swift test --filter ForeignBackupImportTests`, then build
/// the `Strand` app target — `swift-packages` does NOT compile the app).
///
/// A normal restore file-swaps the backup over the live store, which is only valid when the backup
/// came from THIS engine (GRDB): an Android/Room `.noopbak` carries our data tables but Room's
/// `room_master_table` bookkeeping instead of `grdb_migrations`, so dropping it in place strands the
/// store — the migrator then re-runs `v1` and throws `table "device" already exists` forever (#222,
/// the failure `WhoopStore.quarantineIncompatibleDatabase` was added to contain). The old guard
/// REJECTED such a backup and pointed the user at the WHOOP-format CSV. This reconciles it instead:
/// COPY the live GRDB store (inheriting our exact schema + `grdb_migrations` identity), ATTACH the
/// foreign backup, and row-copy the intersection of shared tables/columns into the copy, which the
/// caller then swaps in through the normal snapshot/rollback path.
///
/// Version-agnostic BY DESIGN: it reads tables/columns from `PRAGMA table_info` at run time, never a
/// schema version — GRDB always reports `user_version 0` and the Room forks reuse the same integers,
/// so version is unusable for routing. Any fork's backup (an ahead or behind Android Room fork, or the
/// GRDB store itself) lands by LOGICAL data alone. A target column the backup lacks is handled by the
/// SAME rule on both platforms: a NOT NULL column with no schema default gets a typed zero literal in
/// the SELECT (so `INSERT OR IGNORE` can't silently drop the whole table on that constraint), while a
/// nullable or defaulted column is simply omitted and SQLite fills NULL / the column default. Mirrors
/// the Android planner byte-for-byte at the DATA level: same shared-table set, same target-column-order
/// intersection, same NOT NULL-no-default fill, same `INSERT OR IGNORE` dedup, same REPLACE-clears-first
/// semantics — so the same `.noopbak` reconciled on either platform yields the same rows. Dedup is
/// SQLite's own PK conflict resolution, never a Swift `hashValue`, so nothing platform-specific
/// crosses the backup boundary.
///
/// Lives in the package (not the app's `DataBackup`) for the same reason `BackupSettings` and
/// `DatabaseIntegrity` do: the planner is pure and the executor is pure GRDB, so both are
/// unit-testable headlessly against throwaway SQLite files, never the user's live store.
public enum ForeignBackupImport {

    /// How a backup's rows fold into the target store.
    public enum ImportMode {
        /// Keep existing rows; a primary-key clash is dropped (`INSERT OR IGNORE`).
        case merge
        /// Restore semantics: clear each shared table first, then insert the backup's rows.
        case replace
    }

    /// SQLite / Room / GRDB bookkeeping tables — NEVER copied. Copying them would overwrite the
    /// target's own identity, autoincrement counters, or migration ledger and corrupt the store.
    /// `sqlite_`-prefixed tables (`sqlite_sequence`, `sqlite_stat1`, …) are excluded by prefix too.
    /// Matches the Android `HOUSEKEEPING_TABLES` set exactly.
    static let housekeepingTables: Set<String> = [
        "android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations",
    ]

    /// One target column as `PRAGMA table_info` reports it, carrying exactly the three facts the copy
    /// needs: its `name`, its declared `type` (for the typed-zero literal), and whether it is `notNull`
    /// with no `hasDefault`. That last pair is load-bearing: a NOT NULL column with no schema default
    /// that the backup lacks must be FILLED (a typed zero) rather than omitted, or `INSERT OR IGNORE`
    /// drops the whole table's rows on the constraint. Twin of the fields the Android `readSchema`
    /// reads from its own `PRAGMA table_info` (name, notNull, dflt_value).
    public struct ColumnInfo: Equatable {
        public let name: String
        /// The declared type text (`INTEGER`, `TEXT`, `BLOB`, `REAL`, …); empty for an untyped column.
        public let type: String
        public let notNull: Bool
        public let hasDefault: Bool
        /// Part of the PRIMARY KEY or a UNIQUE index — a column the planner must NEVER constant-fill: every
        /// row would take the same value and `INSERT OR IGNORE` would collapse the table to one row. Read
        /// from `PRAGMA table_info` (`pk` > 0) plus the UNIQUE indexes in `PRAGMA index_list` / `index_info`.
        /// Twin of the Android `SchemaColumn.key`.
        public let key: Bool
        public init(name: String, type: String = "", notNull: Bool = false, hasDefault: Bool = false,
                    key: Bool = false) {
            self.name = name
            self.type = type
            self.notNull = notNull
            self.hasDefault = hasDefault
            self.key = key
        }
    }

    /// A content-based import plan: the SQL to run (inside one transaction with the backup ATTACHed as
    /// `src`), plus what didn't line up — surfaced to the user as warnings, never a hard error.
    /// Twin of the Android `RowCopyPlan`.
    public struct RowCopyPlan: Equatable {
        /// The ordered statements to run against the reconciled copy with the backup ATTACHed as `src`.
        public let statements: [String]
        /// Target tables absent from the backup — no data to import for them.
        public let missingTables: [String]
        /// Backup tables with no home in the target — their rows are skipped.
        public let droppedTables: [String]
        /// Per table, source-absent columns that are NULLABLE or carry a schema default — omitted from
        /// the copy so SQLite fills NULL / the column default (the rows still land).
        public let missingColumns: [String: [String]]
        /// Per table, source-absent columns that are NOT NULL with NO schema default — FILLED with a
        /// typed zero literal in the SELECT so the rows are kept (never dropped by the constraint under
        /// `INSERT OR IGNORE`). On a store whose twin column carries a default (e.g. GRDB's `synced`)
        /// the same column lands in `missingColumns` instead, but the resulting cell is the same zero,
        /// so the two platforms store byte-identical rows.
        public let filledColumns: [String: [String]]
        /// Per table, source-absent NOT NULL-no-default KEY (PK / UNIQUE) columns filled with the source
        /// `rowid` (a per-row-unique id) so the rows import without a constant collapsing the table. Maps
        /// table -> those key column(s). Twin of the Android `synthesizedKeyColumns`.
        public let synthesizedKeyColumns: [String: [String]]
        /// Tables an INSERT was emitted for — the set the executor's row-count backstop verifies.
        public let copiedTables: [String]

        public init(statements: [String], missingTables: [String], droppedTables: [String],
                    missingColumns: [String: [String]], filledColumns: [String: [String]],
                    synthesizedKeyColumns: [String: [String]] = [:], copiedTables: [String] = []) {
            self.statements = statements
            self.missingTables = missingTables
            self.droppedTables = droppedTables
            self.missingColumns = missingColumns
            self.filledColumns = filledColumns
            self.synthesizedKeyColumns = synthesizedKeyColumns
            self.copiedTables = copiedTables
        }

        /// Human-readable warnings, empty when the backup lines up cleanly. Walks the column maps SORTED
        /// so the text is deterministic and matches the order the Android `warnings()` produces (its
        /// `LinkedHashMap` is filled in sorted-table order). NOTE the NOT NULL-no-default "filled …"
        /// lines are NOT emitted here — they carry a kept-row COUNT the pure planner can't know, so the
        /// GRDB executor appends them (see `reconcile`); the Android reconcile appends its twin the same
        /// way. What the planner DOES guarantee is that a filled column is never reported as "imported
        /// empty": its rows are kept, not dropped.
        public func warnings() -> [String] {
            var out: [String] = []
            if !missingTables.isEmpty {
                out.append("No data in this backup for: \(missingTables.joined(separator: ", ")).")
            }
            if !droppedTables.isEmpty {
                out.append("Skipped tables not in this app: \(droppedTables.joined(separator: ", ")).")
            }
            // A key column the backup didn't carry, filled with a generated id so the rows still import —
            // byte-identical to the Android twin's line, after the dropped-tables note on both platforms.
            for table in synthesizedKeyColumns.keys.sorted() {
                let cols = (synthesizedKeyColumns[table] ?? []).joined(separator: ", ")
                out.append("\(table): generated ids for the key column(s) \(cols) this backup didn't carry.")
            }
            for table in missingColumns.keys.sorted() {
                let cols = (missingColumns[table] ?? []).joined(separator: ", ")
                out.append("\(table) is missing fields \(cols) (imported empty).")
            }
            return out
        }
    }

    /// Errors thrown by `reconcile`. The executor throws on any failure and never swallows, so the
    /// caller's snapshot/rollback path is never handed a half-built file.
    public enum ReconcileError: Error, LocalizedError {
        /// There is no live store to inherit a schema + identity from (a fresh install).
        case noLiveStore
        /// A `.replace` copy of `table` landed fewer rows than the backup held (`got` < `expected`): a
        /// schema constraint silently dropped the rest, so the import was aborted rather than commit a
        /// truncated table. Twin of the Android reconcile's row-count backstop.
        case rowCountShortfall(table: String, expected: Int, got: Int)

        public var errorDescription: String? {
            switch self {
            case .noLiveStore:
                return "There is no live NOOP store to reconcile the backup against."
            case let .rowCountShortfall(table, expected, got):
                return "Importing \"\(table)\" kept only \(got) of \(expected) rows (a schema constraint dropped the rest)."
            }
        }
    }

    // MARK: - Planner (pure)

    /// Plan a version-agnostic row copy from `source` into `target`. `target` is a `table -> ordered
    /// ColumnInfo` map (each column's name + type + NOT NULL / default facts, read at run time from
    /// `PRAGMA table_info`); `source` is a `table -> ordered column names` map (only membership is
    /// consulted). It needs no schema version and never touches GRDB's identity. For every DATA table in
    /// BOTH, copy the columns in TARGET order:
    ///   - a target column PRESENT in the source is copied straight (`SELECT \`col\``);
    ///   - a target column ABSENT from the source that is NOT NULL with NO default is FILLED with a
    ///     typed zero literal (INTEGER/REAL → `0`, TEXT → `''`, BLOB → `x''`), because omitting it would
    ///     let `INSERT OR IGNORE` drop the whole table's rows on the NOT NULL constraint (the #222-family
    ///     row-drop bug the Android twin fixes);
    ///   - EXCEPT when that source-absent NOT NULL-no-default column is a KEY (PK / UNIQUE member): a constant
    ///     fill would collapse the table under `INSERT OR IGNORE`, so it is filled with the source `rowid`
    ///     (per-row-unique) instead, keeping the rows without collapsing (`synthesizedKeyColumns`);
    ///   - a target column ABSENT from the source that is nullable OR carries a default is OMITTED, so
    ///     SQLite fills NULL / the column default and the rows still land.
    /// `.replace` clears each target table first (restore semantics); `.merge` keeps existing rows on a
    /// PK clash. Mismatched tables/columns become `RowCopyPlan` warnings, not failures. Twin of the
    /// Android `planRowCopyImport` — the same decision procedure, so the same schemas yield the same SQL
    /// and (given each platform's real schema) byte-identical stored rows.
    public static func planRowCopyImport(
        target: [String: [ColumnInfo]],
        source: [String: [String]],
        mode: ImportMode
    ) -> RowCopyPlan {
        func dataTables(_ keys: Set<String>) -> Set<String> {
            keys.filter { !housekeepingTables.contains($0) && !$0.hasPrefix("sqlite_") }
        }
        let tgt = dataTables(Set(target.keys))
        let src = dataTables(Set(source.keys))
        var statements: [String] = []
        var copiedTables: [String] = []
        var missingColumns: [String: [String]] = [:]
        var filledColumns: [String: [String]] = [:]
        var synthesizedKeyColumns: [String: [String]] = [:]
        for table in tgt.intersection(src).sorted() {
            let sourceCols = Set(source[table] ?? [])
            // Target column ORDER is preserved so the INSERT and SELECT lists line up 1:1.
            var cols: [String] = []      // the INSERT column list
            var sel: [String] = []       // the SELECT expression list, positionally aligned with `cols`
            var missing: [String] = []   // source-absent nullable/defaulted cols (SQLite fills NULL/default)
            var filled: [String] = []    // source-absent NOT NULL-no-default cols (filled with a typed zero)
            var synthKeys: [String] = [] // source-absent NOT NULL-no-default KEY cols (filled with rowid)
            for column in (target[table] ?? []) {
                if sourceCols.contains(column.name) {
                    cols.append(quoteId(column.name))
                    sel.append(quoteId(column.name))
                } else if column.notNull && !column.hasDefault && column.key {
                    // A KEY column the backup lacks can't be CONSTANT-filled — every row would take the same
                    // value and INSERT OR IGNORE would collapse the table. Fill the source `rowid` (per-row
                    // unique) so the rows import; the executor's row-count backstop still catches any drop.
                    cols.append(quoteId(column.name))
                    sel.append("rowid")
                    synthKeys.append(column.name)
                } else if column.notNull && !column.hasDefault {
                    // Keep the row: emit a typed zero so the NOT NULL constraint is satisfied. Omitting it
                    // would let INSERT OR IGNORE drop every row of this table.
                    cols.append(quoteId(column.name))
                    sel.append(zeroLiteral(forDeclaredType: column.type))
                    filled.append(column.name)
                } else {
                    // Nullable or defaulted: omit it; SQLite fills NULL / the column default. Row lands.
                    missing.append(column.name)
                }
            }
            if !synthKeys.isEmpty { synthesizedKeyColumns[table] = synthKeys }
            if !missing.isEmpty { missingColumns[table] = missing }
            if !filled.isEmpty { filledColumns[table] = filled }
            if cols.isEmpty { continue }
            let colList = cols.joined(separator: ", ")
            let selList = sel.joined(separator: ", ")
            if mode == .replace { statements.append("DELETE FROM main.\(quoteId(table))") }
            statements.append(
                "INSERT OR IGNORE INTO main.\(quoteId(table)) (\(colList)) SELECT \(selList) FROM src.\(quoteId(table))")
            copiedTables.append(table)
        }
        return RowCopyPlan(
            statements: statements,
            missingTables: tgt.subtracting(src).sorted(),
            droppedTables: src.subtracting(tgt).sorted(),
            missingColumns: missingColumns,
            filledColumns: filledColumns,
            synthesizedKeyColumns: synthesizedKeyColumns,
            copiedTables: copiedTables
        )
    }

    /// A backtick-quoted identifier with embedded backticks DOUBLED — twin of the Android `quoteId`, so a
    /// foreign backup's table/column name (interpolated into the PRAGMA / row-copy SQL, which can't bind an
    /// identifier parameter) can't break out of its quoting.
    static func quoteId(_ id: String) -> String {
        "`" + id.replacingOccurrences(of: "`", with: "``") + "`"
    }

    /// The typed zero literal SQLite stores for a source-absent NOT NULL column with no default, chosen
    /// by the column's type affinity so the INSERT never trips the NOT NULL constraint. Follows SQLite's
    /// five affinity rules on the declared type: INTEGER/REAL → `0`, TEXT → `''`, BLOB → `x''`, and
    /// NUMERIC / untyped → `0`. Twin of the Android `zeroLiteral`, so both platforms emit the same
    /// literal for the same declared type.
    static func zeroLiteral(forDeclaredType type: String) -> String {
        let t = type.uppercased()
        if t.contains("INT") { return "0" }
        if t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") { return "''" }
        if t.contains("BLOB") { return "x''" }
        if t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") { return "0" }
        return "0"   // NUMERIC / untyped → numeric zero (SQLite NUMERIC affinity)
    }

    // MARK: - Executor (GRDB)

    /// Reconcile a foreign / cross-platform backup at `stagedBackupURL` into a NEW file at `workURL`
    /// carrying THIS app's exact schema + `grdb_migrations` identity, by CLONING the live GRDB store at
    /// `liveDatabaseURL` and row-copying the backup's data into the clone. Returns the `RowCopyPlan`
    /// warnings; the reconciled file is left at `workURL` for the caller to swap in through the normal
    /// snapshot/rollback path. THROWS on any failure (never a partial file). Twin of the Android
    /// `DataBackup.reconcileForeignBackup` — same resulting DATA; the clone mechanism differs by engine
    /// (Android file-copies the SQLite; here GRDB's page-level backup reads a complete committed
    /// snapshot through a source connection, so nothing still in the live store's `-wal` is missed).
    ///
    /// `.replace` (the cross-fork restore mode) clears every shared table in the clone before inserting,
    /// so the live rows carried over by the clone are dropped and only the backup's rows remain.
    /// `.merge` keeps the clone's live rows and lets `INSERT OR IGNORE` drop a backup row on a PK clash.
    @discardableResult
    public static func reconcile(
        liveDatabaseURL: URL,
        stagedBackupURL: URL,
        workURL: URL,
        mode: ImportMode
    ) throws -> [String] {
        let fm = FileManager.default
        guard fm.fileExists(atPath: liveDatabaseURL.path) else { throw ReconcileError.noLiveStore }

        // Fresh work file: drop any stale copy + its WAL/SHM siblings.
        for suffix in ["", "-wal", "-shm"] { try? fm.removeItem(atPath: workURL.path + suffix) }

        do {
            let warnings = try buildReconciled(
                liveURL: liveDatabaseURL, stagedURL: stagedBackupURL, workURL: workURL, mode: mode)
            // The connections are closed now (their queues went out of scope), and the checkpoint folded
            // the WAL back into the single file — drop the now-empty sidecars so `workURL` is self-contained.
            for suffix in ["-wal", "-shm"] { try? fm.removeItem(atPath: workURL.path + suffix) }
            return warnings
        } catch {
            // A torn reconcile must leave NO scratch behind: drop the half-built work file AND its
            // WAL/SHM sidecars on ANY throw (not just the success path), so a failed cross-fork import
            // can never leave a partial file for the caller's swap. Twin of the Android reconcile's
            // try/finally cleanup.
            for suffix in ["", "-wal", "-shm"] { try? fm.removeItem(atPath: workURL.path + suffix) }
            throw error
        }
    }

    /// Clone the live store into `workURL` (GRDB page-level backup), then ATTACH the staged backup and
    /// row-copy the shared intersection inside one transaction, then checkpoint. Foreign keys are
    /// DISABLED for the clone (matching the Android `SQLiteDatabase` default) so a raw intersection copy
    /// can't trip a constraint the source row order doesn't guarantee; the resulting rows are identical
    /// either way (the schema declares no cross-table foreign keys). The queues are local bindings, so
    /// they close when this function returns — before `reconcile` deletes the WAL/SHM sidecars.
    private static func buildReconciled(liveURL: URL, stagedURL: URL, workURL: URL, mode: ImportMode) throws -> [String] {
        var config = Configuration()
        config.foreignKeysEnabled = false

        // Page-level clone of the live store into the (empty) work file, read READ-ONLY so the live store
        // the app still has open is never mutated. Inherits its exact schema + grdb_migrations identity +
        // committed rows (including any still in the live `-wal`). Both connections are scoped to this
        // block so they close before the reopen below — the clone rewrites the work file's page-1 header
        // (to the source's WAL mode), so the row-copy runs on a FRESH connection that reads that header
        // cleanly rather than a stale journal-mode view left over from the empty-file open.
        do {
            let workQueue = try DatabaseQueue(path: workURL.path, configuration: config)
            var liveConfig = Configuration()
            liveConfig.readonly = true
            let liveQueue = try DatabaseQueue(path: liveURL.path, configuration: liveConfig)
            try liveQueue.backup(to: workQueue)
        }

        let workQueue = try DatabaseQueue(path: workURL.path, configuration: config)
        var warnings: [String] = []
        try workQueue.writeWithoutTransaction { db in
            let target = try readSchema(db, schema: "main")
            // ATTACH / DETACH must sit OUTSIDE a transaction; the copy itself runs inside one.
            try db.execute(sql: "ATTACH DATABASE ? AS src", arguments: [stagedURL.path])
            let source = try readSchema(db, schema: "src")
            // Only column NAMES matter for the source (membership); the fill decision is driven by the
            // TARGET's NOT NULL / default facts, which `readSchema` carries on `target`.
            let plan = planRowCopyImport(
                target: target, source: source.mapValues { $0.map(\.name) }, mode: mode)
            try db.inTransaction {
                for statement in plan.statements { try db.execute(sql: statement) }
                // Row-count backstop (REPLACE restore only). Each copied table was cleared then re-filled
                // from the source, so on a clean import `landed == source`. A shortfall means
                // `INSERT OR IGNORE` silently dropped rows on a constraint the planner didn't model — a
                // CHECK, or a UNIQUE the source didn't enforce — so THROW (rolls this transaction back)
                // rather than commit a quietly-truncated table. The planner already skips the key-column
                // collapse; this catches the rest, before the caller's swap touches the live store. MERGE is
                // exempt (its PK-clash drops are intentional). `src` is still ATTACHed inside the closure.
                if mode == .replace {
                    for table in plan.copiedTables {
                        let sourceRows = try Int.fetchOne(db, sql: "SELECT count(*) FROM src.\(quoteId(table))") ?? 0
                        let landed = try Int.fetchOne(db, sql: "SELECT count(*) FROM main.\(quoteId(table))") ?? 0
                        if landed < sourceRows {
                            throw ReconcileError.rowCountShortfall(table: table, expected: sourceRows, got: landed)
                        }
                    }
                }
                return .commit
            }
            try db.execute(sql: "DETACH DATABASE src")
            // wal_checkpoint returns a row, so it must be FETCHED, not run as a bare statement.
            _ = try Row.fetchAll(db, sql: "PRAGMA wal_checkpoint(TRUNCATE)")
            warnings = plan.warnings()
            // Append the NOT NULL-no-default "filled …" lines the pure planner can't: they carry the
            // kept-row COUNT, read here from the reconciled table so the warning is honest about how many
            // rows were kept (never dropped by the constraint). Sorted by table so the order is
            // deterministic; the Android reconcile appends its twin the same way.
            for table in plan.filledColumns.keys.sorted() {
                let cols = (plan.filledColumns[table] ?? []).joined(separator: ", ")
                let kept = try Int.fetchOne(db, sql: "SELECT count(*) FROM main.`\(table)`") ?? 0
                warnings.append("\(table): filled \(cols) with defaults (kept \(kept) rows).")
            }
        }
        return warnings
    }

    /// `table -> ordered ColumnInfo`, read from `PRAGMA <schema>.table_info` on `schema` (`main` or the
    /// ATTACHed `src` alias). Column order follows the pragma's natural order, and each column carries
    /// its `name`, declared `type`, `notnull` flag, whether `dflt_value` is set, AND whether it is a KEY
    /// (PK / UNIQUE member, via `keyColumns`) — exactly the fields the Android `readSchema` reads, so both
    /// platforms build the same intersection, make the same NOT NULL-no-default fill decision, and skip the
    /// same collapse-prone tables.
    static func readSchema(_ db: Database, schema: String) throws -> [String: [ColumnInfo]] {
        var out: [String: [ColumnInfo]] = [:]
        let tables = try String.fetchAll(
            db, sql: "SELECT name FROM \(schema).sqlite_master WHERE type = 'table'")
        for table in tables {
            let keyCols = try keyColumns(db, schema: schema, table: table)
            let rows = try Row.fetchAll(db, sql: "PRAGMA \(schema).table_info(\(quoteId(table)))")
            out[table] = rows.map { row in
                let name: String = row["name"] ?? ""
                let type: String = row["type"] ?? ""
                let notNull: Int = row["notnull"] ?? 0
                // `dflt_value` holds the default's SQL text, or SQL NULL when the column has no default —
                // so a non-nil decode means "has a schema default".
                let dflt: String? = row["dflt_value"]
                return ColumnInfo(name: name, type: type, notNull: notNull != 0, hasDefault: dflt != nil,
                                  key: keyCols.contains(name))
            }
        }
        return out
    }

    /// Column names of `table` that participate in the PRIMARY KEY or any UNIQUE index — the columns the
    /// planner must never constant-fill. PK members come from `PRAGMA table_info` (`pk` > 0); UNIQUE members
    /// from each `unique` index in `PRAGMA index_list`, expanded via `PRAGMA index_info`. Twin of the
    /// Android `keyColumns`.
    static func keyColumns(_ db: Database, schema: String, table: String) throws -> Set<String> {
        var keys: Set<String> = []
        for row in try Row.fetchAll(db, sql: "PRAGMA \(schema).table_info(\(quoteId(table)))") {
            let name: String = row["name"] ?? ""
            let pk: Int = row["pk"] ?? 0
            if pk != 0 && !name.isEmpty { keys.insert(name) }
        }
        var uniqueIndexes: [String] = []
        for row in try Row.fetchAll(db, sql: "PRAGMA \(schema).index_list(\(quoteId(table)))") {
            let unique: Int = row["unique"] ?? 0
            if unique != 0, let name = row["name"] as String? { uniqueIndexes.append(name) }
        }
        for index in uniqueIndexes {
            for row in try Row.fetchAll(db, sql: "PRAGMA \(schema).index_info(\(quoteId(index)))") {
                if let name = row["name"] as String? { keys.insert(name) }
            }
        }
        return keys
    }
}
