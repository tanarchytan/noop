import XCTest
import GRDB
@testable import WhoopStore

/// Pins the cross-fork row-copy importer (#222 family) — the Swift twin of Android's
/// `DataBackup.reconcileForeignBackup` / `planRowCopyImport`. Two layers:
///   1. the PURE planner (`planRowCopyImport`), asserted exactly against small synthetic schemas, so
///      the shared-table/column intersection, the NOT NULL-no-default typed-zero fill,
///      REPLACE-clears-first, and warnings match Android; and
///   2. the GRDB EXECUTOR (`reconcile`), which copies the live GRDB store and row-copies a foreign
///      (Room) backup into it, asserted on the RESULTING ROWS — the byte-level data parity that
///      matters, incl. `INSERT OR IGNORE` dedup with NO Swift `hashValue` crossing the boundary, and
///      the row-DROP regression (a source-missing NOT NULL column with no default is FILLED, not
///      dropped).
///
/// UNVERIFIED ON macOS: authored on a non-Apple host. Run `swift test --filter
/// ForeignBackupImportTests` on a Mac before merge (WhoopStore is GRDB-linked; it does not build on
/// Linux — `sqlite3.h not found`).
final class ForeignBackupImportTests: XCTestCase {

    /// Terse `ColumnInfo` builder for the planner arrange blocks. Defaults to a nullable, no-default
    /// TEXT column (the common "just a name" case); flip `notNull` / `hasDefault` / `type` where the
    /// fill decision is under test.
    private func col(_ name: String, _ type: String = "TEXT",
                     notNull: Bool = false, hasDefault: Bool = false, key: Bool = false) -> ForeignBackupImport.ColumnInfo {
        ForeignBackupImport.ColumnInfo(name: name, type: type, notNull: notNull, hasDefault: hasDefault, key: key)
    }

    // MARK: - Planner (pure)

    func testPlannerCopiesTargetColumnIntersectionInReplaceMode() {
        // target A has an extra (nullable) column `y` the source lacks, C the source lacks entirely;
        // source has an extra table D with no home in the target.
        let target = ["A": [col("id"), col("x"), col("y")], "B": [col("id")], "C": [col("id")]]
        let source = ["A": ["id", "x"], "B": ["id"], "D": ["id"]]

        let plan = ForeignBackupImport.planRowCopyImport(target: target, source: source, mode: .replace)

        XCTAssertEqual(plan.statements, [
            "DELETE FROM main.`A`",
            "INSERT OR IGNORE INTO main.`A` (`id`, `x`) SELECT `id`, `x` FROM src.`A`",
            "DELETE FROM main.`B`",
            "INSERT OR IGNORE INTO main.`B` (`id`) SELECT `id` FROM src.`B`",
        ])
        XCTAssertEqual(plan.missingTables, ["C"])
        XCTAssertEqual(plan.droppedTables, ["D"])
        XCTAssertEqual(plan.missingColumns, ["A": ["y"]])
        XCTAssertTrue(plan.filledColumns.isEmpty)
        XCTAssertEqual(plan.warnings(), [
            "No data in this backup for: C.",
            "Skipped tables not in this app: D.",
            "A is missing fields y (imported empty).",
        ])
    }

    func testPlannerMergeModeEmitsNoDeletes() {
        let target = ["A": [col("id"), col("x")], "B": [col("id")]]
        let source = ["A": ["id", "x"], "B": ["id"]]

        let plan = ForeignBackupImport.planRowCopyImport(target: target, source: source, mode: .merge)

        XCTAssertEqual(plan.statements, [
            "INSERT OR IGNORE INTO main.`A` (`id`, `x`) SELECT `id`, `x` FROM src.`A`",
            "INSERT OR IGNORE INTO main.`B` (`id`) SELECT `id` FROM src.`B`",
        ])
        XCTAssertTrue(plan.warnings().isEmpty)
    }

    func testPlannerExcludesHousekeepingAndSqlitePrefixedTables() {
        // Every bookkeeping table lives in BOTH sides; none may appear in the plan or the warnings.
        let housekeeping: [String: [ForeignBackupImport.ColumnInfo]] = [
            "android_metadata": [col("a")], "sqlite_sequence": [col("b")],
            "room_master_table": [col("c")], "grdb_migrations": [col("d")],
            "sqlite_stat1": [col("e")],
        ]
        let housekeepingNames = ["android_metadata": ["a"], "sqlite_sequence": ["b"],
                                 "room_master_table": ["c"], "grdb_migrations": ["d"],
                                 "sqlite_stat1": ["e"]]
        let target = housekeeping.merging(["hrSample": [col("deviceId"), col("ts")]]) { a, _ in a }
        let source = housekeepingNames.merging(["hrSample": ["deviceId", "ts"]]) { a, _ in a }

        let plan = ForeignBackupImport.planRowCopyImport(target: target, source: source, mode: .replace)

        XCTAssertEqual(plan.statements, [
            "DELETE FROM main.`hrSample`",
            "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`) SELECT `deviceId`, `ts` FROM src.`hrSample`",
        ])
        XCTAssertTrue(plan.missingTables.isEmpty)
        XCTAssertTrue(plan.droppedTables.isEmpty)
        XCTAssertTrue(plan.warnings().isEmpty)
    }

    func testPlannerFillsNotNullNoDefaultColumnsWithTypedZeroSoRowsAreKept() {
        // `flag` (INTEGER NOT NULL, no default), `label` (TEXT NOT NULL, no default) and `blob`
        // (BLOB NOT NULL, no default) are all ABSENT from the source. Omitting them would let
        // INSERT OR IGNORE drop every row of the table on the NOT NULL constraint (the #222-family
        // row-drop bug). Instead each is FILLED with its type's zero literal (0 / '' / x'') so the rows
        // land. A source-absent NULLABLE column and a source-absent DEFAULTED column are still just
        // OMITTED (SQLite fills NULL / the default) — the existing "imported empty" wording.
        let target = ["t": [
            col("id", "TEXT", notNull: true),
            col("flag", "INTEGER", notNull: true),
            col("label", "TEXT", notNull: true),
            col("blob", "BLOB", notNull: true),
            col("untyped", "", notNull: true),                  // untyped → numeric 0 (parity with Android)
            col("note", "TEXT"),                                // nullable → omitted
            col("count", "INTEGER", notNull: true, hasDefault: true),  // NOT NULL but defaulted → omitted
        ]]
        let source = ["t": ["id"]]

        let plan = ForeignBackupImport.planRowCopyImport(target: target, source: source, mode: .merge)

        // COLS = present ∪ NOT NULL-no-default; SEL fills the absent NOT NULL-no-default cols with a
        // typed zero, positionally aligned. `note` / `count` are omitted from both lists.
        XCTAssertEqual(plan.statements, [
            "INSERT OR IGNORE INTO main.`t` (`id`, `flag`, `label`, `blob`, `untyped`) SELECT `id`, 0, '', x'', 0 FROM src.`t`",
        ])
        XCTAssertEqual(plan.filledColumns, ["t": ["flag", "label", "blob", "untyped"]])
        XCTAssertEqual(plan.missingColumns, ["t": ["note", "count"]])
        // The pure planner reports only the omitted (nullable/defaulted) columns as "imported empty";
        // the FILLED columns are never called empty — their kept-row count line is added by the executor.
        XCTAssertEqual(plan.warnings(), ["t is missing fields note, count (imported empty)."])
    }

    func testPlannerFillsMissingNotNullKeyColumnWithRowidSoRowsImport() {
        // hrSample's PK column `ts` is NOT NULL, no default, and a KEY; the backup renamed it (`stamp`) so
        // it is source-absent. A CONSTANT fill would give every row the same key and INSERT OR IGNORE would
        // collapse the table; filling the source `rowid` (per-row-unique) keeps every row. A sibling whose
        // key is present copies straight. Twin of the Android
        // `sourceMissingNotNullNoDefaultKeyColumnFillsItWithRowidSoRowsImport`.
        let target = [
            "hrSample": [col("deviceId", "TEXT", notNull: true, key: true),
                         col("ts", "INTEGER", notNull: true, key: true),
                         col("bpm", "INTEGER")],
            "sleepSession": [col("deviceId", "TEXT", notNull: true, key: true),
                             col("startTs", "INTEGER", notNull: true, key: true),
                             col("efficiency", "REAL")],
        ]
        let source = ["hrSample": ["deviceId", "stamp", "bpm"],
                      "sleepSession": ["deviceId", "startTs", "efficiency"]]

        let plan = ForeignBackupImport.planRowCopyImport(target: target, source: source, mode: .replace)

        XCTAssertEqual(plan.statements, [
            "DELETE FROM main.`hrSample`",
            "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`) SELECT `deviceId`, rowid, `bpm` FROM src.`hrSample`",
            "DELETE FROM main.`sleepSession`",
            "INSERT OR IGNORE INTO main.`sleepSession` (`deviceId`, `startTs`, `efficiency`) SELECT `deviceId`, `startTs`, `efficiency` FROM src.`sleepSession`",
        ])
        XCTAssertEqual(plan.synthesizedKeyColumns, ["hrSample": ["ts"]])
        XCTAssertEqual(plan.copiedTables, ["hrSample", "sleepSession"])
        XCTAssertTrue(plan.warnings().contains(
            "hrSample: generated ids for the key column(s) ts this backup didn't carry."))
    }

    func testPlannerOmitsNullableKeyColumnRatherThanRowidFillingIt() {
        // Only a NOT NULL-no-default key is rowid-filled; a nullable key column the source lacks is OMITTED
        // (SQLite fills NULL, which never collides in a UNIQUE index), so it needs no synthetic id. Locks
        // that the rowid fill fires on the (NOT NULL ∧ no-default ∧ key) triple, not on `key` alone. Twin of
        // the Android `aSourceMissingKeyColumnThatIsNullableIsOmittedNotRowidFilled`.
        let target = ["t": [col("id", "INTEGER", notNull: true, key: true), col("altKey", "TEXT", key: true)]]
        let source = ["t": ["id"]]

        let plan = ForeignBackupImport.planRowCopyImport(target: target, source: source, mode: .merge)

        XCTAssertEqual(plan.statements, ["INSERT OR IGNORE INTO main.`t` (`id`) SELECT `id` FROM src.`t`"])
        XCTAssertTrue(plan.synthesizedKeyColumns.isEmpty)
        XCTAssertEqual(plan.missingColumns, ["t": ["altKey"]])
    }

    // MARK: - Executor (GRDB, real files)

    func testReconcileReplaceClearsLocalRowsAndImportsForeignRows() throws {
        let live = tempPath()
        let backup = tempPath()
        let work = tempPath()
        defer { [live, backup, work].forEach(remove) }

        try makeLiveStore(at: live) { db in
            try db.execute(sql: "INSERT INTO device (id, name) VALUES ('local', 'WHOOP')")
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm, synced) VALUES ('local', 10, 60, 0)")
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm, synced) VALUES ('local', 11, 61, 0)")
        }
        try makeForeignRoomBackup(at: backup) { db in
            try db.execute(sql: "INSERT INTO device (id, name) VALUES ('foreign', 'WHOOP')")
            // hrSample WITHOUT the `synced` column (an older Room fork) → it imports as the default.
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm) VALUES ('foreign', 10, 70)")
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm) VALUES ('foreign', 20, 72)")
        }

        let warnings = try ForeignBackupImport.reconcile(
            liveDatabaseURL: URL(fileURLWithPath: live),
            stagedBackupURL: URL(fileURLWithPath: backup),
            workURL: URL(fileURLWithPath: work),
            mode: .replace)

        try readWork(work) { db in
            // REPLACE cleared the shared tables, so ONLY foreign rows remain.
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT count(*) FROM hrSample"), 2)
            XCTAssertEqual(try String.fetchAll(db, sql: "SELECT deviceId FROM hrSample ORDER BY ts"),
                           ["foreign", "foreign"])
            XCTAssertEqual(try Int.fetchAll(db, sql: "SELECT ts FROM hrSample ORDER BY ts"), [10, 20])
            XCTAssertEqual(try Int.fetchAll(db, sql: "SELECT bpm FROM hrSample ORDER BY ts"), [70, 72])
            // `synced` was absent from the source → on GRDB it carries a schema default (NOT NULL
            // DEFAULT 0), so the copy omits it and SQLite fills 0, never NULL. (On Android the twin
            // column has no default and is instead filled with a typed 0 — same stored cell.)
            XCTAssertEqual(try Int.fetchAll(db, sql: "SELECT synced FROM hrSample ORDER BY ts"), [0, 0])

            XCTAssertEqual(try String.fetchAll(db, sql: "SELECT id FROM device ORDER BY id"),
                           ["foreign"], "REPLACE dropped the local device row")

            // Identity preserved: the reconciled copy is still a valid GRDB store.
            XCTAssertTrue(try tableExists(db, "grdb_migrations"))
            // The Room-only table was NOT created in the target.
            XCTAssertFalse(try tableExists(db, "roomOnlyTable"))
        }

        // Warnings surface the asymmetry (exact for the deterministic entries; spot-checked otherwise).
        XCTAssertTrue(warnings.contains("Skipped tables not in this app: roomOnlyTable."))
        XCTAssertTrue(warnings.contains("hrSample is missing fields synced (imported empty)."))
        XCTAssertTrue(warnings.contains { $0.hasPrefix("No data in this backup for:") && $0.contains("battery") })
        // Sidecars folded away — the reconciled file is self-contained.
        XCTAssertFalse(FileManager.default.fileExists(atPath: work + "-wal"))
    }

    func testReconcileFillsNotNullNoDefaultColumnAndKeepsRows() throws {
        // The row-DROP regression, mirrored on real files (the Android twin's regression test). A target
        // table carries an INTEGER NOT NULL column with NO schema default; the foreign fork's table
        // LACKS that column. A naive `INSERT OR IGNORE … SELECT` that omitted it would hit the NOT NULL
        // constraint and DROP every row; the planner fills it with a typed zero, so the rows are KEPT
        // with 0.
        let live = tempPath()
        let backup = tempPath()
        let work = tempPath()
        defer { [live, backup, work].forEach(remove) }

        try makeLiveStore(at: live) { db in
            // GRDB emits no SQL default unless `.defaults(to:)` is used, so this is a genuine NOT
            // NULL-no-default column — the same shape as the Room `= 0`-with-no-SQL-default column the
            // Android regression covers.
            try db.execute(sql: """
                CREATE TABLE syncFlags (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, flag INTEGER NOT NULL,
                    PRIMARY KEY (deviceId, ts))
                """)
            try db.execute(sql: "INSERT INTO syncFlags (deviceId, ts, flag) VALUES ('local', 1, 1)")
        }
        try makeForeignRoomBackup(at: backup) { db in
            // The foreign fork's table has no `flag` column.
            try db.execute(sql: """
                CREATE TABLE syncFlags (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (deviceId, ts))
                """)
            try db.execute(sql: "INSERT INTO syncFlags (deviceId, ts) VALUES ('foreign', 10)")
            try db.execute(sql: "INSERT INTO syncFlags (deviceId, ts) VALUES ('foreign', 20)")
        }

        let warnings = try ForeignBackupImport.reconcile(
            liveDatabaseURL: URL(fileURLWithPath: live),
            stagedBackupURL: URL(fileURLWithPath: backup),
            workURL: URL(fileURLWithPath: work),
            mode: .replace)

        try readWork(work) { db in
            // Both foreign rows were KEPT (not dropped by the NOT NULL constraint), `flag` filled with 0.
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT count(*) FROM syncFlags"), 2)
            XCTAssertEqual(try Int.fetchAll(db, sql: "SELECT ts FROM syncFlags ORDER BY ts"), [10, 20])
            XCTAssertEqual(try Int.fetchAll(db, sql: "SELECT flag FROM syncFlags ORDER BY ts"), [0, 0])
        }

        // The fill is surfaced honestly as a KEPT (not a dropped / "imported empty") line, with the count.
        XCTAssertTrue(warnings.contains("syncFlags: filled flag with defaults (kept 2 rows)."))
    }

    func testReconcileMergeKeepsLocalRowsAndDropsPkClash() throws {
        let live = tempPath()
        let backup = tempPath()
        let work = tempPath()
        defer { [live, backup, work].forEach(remove) }

        try makeLiveStore(at: live) { db in
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm, synced) VALUES ('local', 10, 60, 0)")
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm, synced) VALUES ('local', 11, 61, 0)")
        }
        try makeForeignRoomBackup(at: backup) { db in
            // ('local', 10) clashes with a live PK; ('foreign', 20) is new.
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm) VALUES ('local', 10, 999)")
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm) VALUES ('foreign', 20, 72)")
        }

        try ForeignBackupImport.reconcile(
            liveDatabaseURL: URL(fileURLWithPath: live),
            stagedBackupURL: URL(fileURLWithPath: backup),
            workURL: URL(fileURLWithPath: work),
            mode: .merge)

        try readWork(work) { db in
            let hr = try Row.fetchAll(db, sql: "SELECT deviceId, ts, bpm FROM hrSample ORDER BY deviceId, ts")
            XCTAssertEqual(hr.count, 3, "two local rows kept + one new foreign row")
            // INSERT OR IGNORE kept the LOCAL value on the PK clash — the foreign 999 was dropped.
            let clash = try Int.fetchOne(db,
                sql: "SELECT bpm FROM hrSample WHERE deviceId = 'local' AND ts = 10")
            XCTAssertEqual(clash, 60, "the clashing local row must win under INSERT OR IGNORE")
            let added = try Int.fetchOne(db,
                sql: "SELECT bpm FROM hrSample WHERE deviceId = 'foreign' AND ts = 20")
            XCTAssertEqual(added, 72, "the non-clashing foreign row was imported")
        }
    }

    func testReconcileReplaceAbortsWhenAConstraintDropsRows() throws {
        // The row-count backstop. The target `tag` table carries a UNIQUE(label) the foreign fork lacks;
        // the fork's two rows share a label, which the target forbids. A REPLACE copy would DELETE then
        // INSERT OR IGNORE both, silently dropping the second on the UNIQUE constraint — landing 1 of 2
        // rows. The backstop must catch the shortfall and THROW (rolling back), so a quietly-truncated
        // table is never committed, and the torn reconcile leaves no scratch behind. Twin of the Android
        // reconcile's REPLACE row-count check.
        let live = tempPath()
        let backup = tempPath()
        let work = tempPath()
        defer { [live, backup, work].forEach(remove) }

        try makeLiveStore(at: live) { db in
            try db.execute(sql: """
                CREATE TABLE tag (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, label TEXT NOT NULL,
                    PRIMARY KEY (deviceId, ts), UNIQUE (label))
                """)
        }
        try makeForeignRoomBackup(at: backup) { db in
            // Same table, but NO UNIQUE(label) — so two rows can (and do) share a label.
            try db.execute(sql: """
                CREATE TABLE tag (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, label TEXT NOT NULL,
                    PRIMARY KEY (deviceId, ts))
                """)
            try db.execute(sql: "INSERT INTO tag (deviceId, ts, label) VALUES ('foreign', 1, 'dup')")
            try db.execute(sql: "INSERT INTO tag (deviceId, ts, label) VALUES ('foreign', 2, 'dup')")
        }

        XCTAssertThrowsError(try ForeignBackupImport.reconcile(
            liveDatabaseURL: URL(fileURLWithPath: live),
            stagedBackupURL: URL(fileURLWithPath: backup),
            workURL: URL(fileURLWithPath: work),
            mode: .replace)) { error in
            guard case let ForeignBackupImport.ReconcileError.rowCountShortfall(table, expected, got) = error else {
                return XCTFail("expected rowCountShortfall, got \(error)")
            }
            XCTAssertEqual(table, "tag")
            XCTAssertEqual(expected, 2)
            XCTAssertEqual(got, 1)
        }
        // A torn reconcile leaves no scratch file for the caller's swap.
        XCTAssertFalse(FileManager.default.fileExists(atPath: work))
    }

    func testReconcileRejectsMissingLiveStore() {
        let backup = tempPath()
        let work = tempPath()
        defer { [backup, work].forEach(remove) }
        try? makeForeignRoomBackup(at: backup) { _ in }

        XCTAssertThrowsError(try ForeignBackupImport.reconcile(
            liveDatabaseURL: URL(fileURLWithPath: tempPath()),
            stagedBackupURL: URL(fileURLWithPath: backup),
            workURL: URL(fileURLWithPath: work),
            mode: .replace)) { error in
            XCTAssertEqual(error as? ForeignBackupImport.ReconcileError, .noLiveStore)
        }
    }

    // MARK: - Fixtures

    private func tempPath() -> String {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-foreign-\(UUID().uuidString).sqlite").path
    }

    /// Remove a store file and its WAL/SHM siblings.
    private func remove(_ path: String) {
        for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
    }

    /// A real GRDB store at `path` (full NOOP schema + `grdb_migrations`), seeded then CLOSED so
    /// `reconcile` copies a fully-committed, handle-free file.
    private func makeLiveStore(at path: String, seed: (Database) throws -> Void) throws {
        let queue = try DatabaseQueue(path: path)
        try WhoopStore.makeMigrator().migrate(queue)
        try queue.write { db in try seed(db) }
    }

    /// A foreign (Android/Room) backup at `path`: Room's `room_master_table` marker, the shared
    /// `device`/`hrSample` tables (hrSample WITHOUT `synced`, an older fork), and a Room-only table with
    /// no home in the target. Closed on return.
    private func makeForeignRoomBackup(at path: String, seed: (Database) throws -> Void) throws {
        let queue = try DatabaseQueue(path: path)
        try queue.write { db in
            try db.execute(sql: "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            try db.execute(sql: "CREATE TABLE device (id TEXT PRIMARY KEY, mac TEXT, name TEXT, firstSeen INTEGER, lastSeen INTEGER)")
            try db.execute(sql: "CREATE TABLE hrSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm INTEGER NOT NULL, PRIMARY KEY (deviceId, ts))")
            try db.execute(sql: "CREATE TABLE roomOnlyTable (id TEXT PRIMARY KEY, note TEXT)")
            try seed(db)
        }
    }

    private func readWork(_ path: String, _ block: (Database) throws -> Void) throws {
        let queue = try DatabaseQueue(path: path)
        try queue.read { db in try block(db) }
    }

    private func tableExists(_ db: Database, _ name: String) throws -> Bool {
        try Bool.fetchOne(db,
            sql: "SELECT count(*) > 0 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arguments: [name]) ?? false
    }
}
