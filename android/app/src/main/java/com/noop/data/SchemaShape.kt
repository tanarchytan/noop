package com.noop.data

import android.content.Context
import android.database.Cursor
import androidx.room.Room

/**
 * A store's ACTUAL shape, read from the file itself, against the shape the generated Room delegate
 * creates on a fresh install — never a version number and never a hand-kept list of columns.
 * Drives the repair in [DataBackup.migrateBackupIfNeeded].
 */
internal object SchemaShape {

    /** Tables SQLite and Room own; no entity declares one, so neither is part of a shape. */
    private val HOUSEKEEPING = setOf("android_metadata", "room_master_table")

    /** One column as PRAGMA table_info reports it: the facts Room's own TableInfo compares. */
    data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pk: Int,
    )

    /** One table: its columns, the statement that recreates it, and its explicitly created
     *  indexes keyed by lower-case name. */
    data class Table(
        val name: String,
        val columns: List<Column>,
        val createSql: String,
        val indexes: Map<String, String>,
    )

    /** What a fresh store carries: the shape the delegate creates and the identity it stamps. */
    data class Target(val shape: Map<String, Table>, val identityHash: String)

    /** [statements] bring a store up to the target; [refusals] name what no additive statement can
     *  fix. A plan carrying either is not at the target, and must never be stamped as if it were. */
    data class RepairPlan(val statements: List<String>, val refusals: List<String>) {
        val isClean: Boolean get() = statements.isEmpty() && refusals.isEmpty()
    }

    /**
     * The additive statements that bring [actual] up to [target], plus the differences no additive
     * statement can fix. Compares what Room compares at open — column type, nullability, key
     * position, a declared default — so a clean plan means Room will accept the store.
     */
    fun plan(target: Map<String, Table>, actual: Map<String, Table>): RepairPlan {
        val statements = ArrayList<String>()
        val refusals = ArrayList<String>()
        for ((key, want) in target) {
            val have = actual[key]
            if (have == null) {
                statements.add(want.createSql)
                statements.addAll(want.indexes.values)
                continue
            }
            val haveColumns = have.columns.associateBy { it.name.lowercase() }
            val wantColumns = want.columns.map { it.name.lowercase() }.toSet()
            for (col in want.columns) {
                val got = haveColumns[col.name.lowercase()]
                when {
                    got != null -> mismatch(want.name, col, got)?.let(refusals::add)
                    // SQLite can only append a nullable-or-defaulted, non-key column to a live table.
                    col.pk != 0 -> refusals.add("${want.name}.${col.name} is missing and is part of the primary key")
                    col.notNull && col.defaultValue == null ->
                        refusals.add("${want.name}.${col.name} is missing and is required with no default")
                    else -> statements.add("ALTER TABLE ${quote(want.name)} ADD COLUMN ${definitionOf(col)}")
                }
            }
            for (col in have.columns) {
                if (col.name.lowercase() !in wantColumns) {
                    refusals.add("${want.name} carries a column this version does not have: ${col.name}")
                }
            }
            for ((name, createSql) in want.indexes) if (name !in have.indexes) statements.add(createSql)
            for (name in have.indexes.keys) {
                if (name !in want.indexes) {
                    refusals.add("${want.name} carries an index this version does not have: $name")
                }
            }
        }
        return RepairPlan(statements, refusals)
    }

    /** A one-line account of why a plan is not at the target, for the caller's failure message. */
    fun describe(plan: RepairPlan): String {
        val reasons = plan.refusals + plan.statements.map { "could not apply: $it" }
        return reasons.take(4).joinToString("; ") +
            if (reasons.size > 4) " (and ${reasons.size - 4} more)" else ""
    }

    /**
     * The shape behind [query] — a `PRAGMA`/`SELECT` runner, so the same reader serves both an open
     * Room store and a read-only probe of a staged file. Entity tables only, in creation order.
     */
    fun read(query: (String) -> Cursor): Map<String, Table> {
        val tableSql = LinkedHashMap<String, String>()
        val indexSql = HashMap<String, String>()
        query("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val sql = (if (c.isNull(2)) null else c.getString(2)) ?: continue
                if (type == "table" && name.lowercase() !in HOUSEKEEPING) tableSql[name] = sql
                if (type == "index") indexSql[name.lowercase()] = sql
            }
        }
        val out = LinkedHashMap<String, Table>()
        for ((name, createSql) in tableSql) {
            out[name.lowercase()] = Table(name, columnsOf(query, name), createSql, indexesOf(query, name, indexSql))
        }
        return out
    }

    /**
     * The shape and identity a fresh store carries, read off a throwaway in-memory one built by the
     * generated delegate — the same source a new install gets. Null when it cannot be built.
     */
    fun readTarget(appContext: Context): Target? = runCatching {
        val probe = Room.inMemoryDatabaseBuilder(appContext, WhoopDatabase::class.java).build()
        try {
            val db = probe.openHelper.writableDatabase
            val hash = db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            Target(read { db.query(it) }, requireNotNull(hash))
        } finally {
            probe.close()
        }
    }.getOrNull()

    private fun columnsOf(query: (String) -> Cursor, table: String): List<Column> {
        val out = ArrayList<Column>()
        query("PRAGMA table_info(${quote(table)})").use { c ->
            val ni = c.getColumnIndex("name")
            val ti = c.getColumnIndex("type")
            val nn = c.getColumnIndex("notnull")
            val dv = c.getColumnIndex("dflt_value")
            val pk = c.getColumnIndex("pk")
            while (c.moveToNext()) {
                val name = (if (ni >= 0) c.getString(ni) else null) ?: continue
                out.add(
                    Column(
                        name = name,
                        type = (if (ti >= 0) c.getString(ti) else null).orEmpty().uppercase(),
                        notNull = nn >= 0 && c.getInt(nn) != 0,
                        defaultValue = if (dv >= 0 && !c.isNull(dv)) c.getString(dv) else null,
                        pk = if (pk >= 0) c.getInt(pk) else 0,
                    ),
                )
            }
        }
        return out
    }

    /** Only `origin = 'c'` indexes: an auto PK/UNIQUE index belongs to its table's CREATE, not beside it. */
    private fun indexesOf(query: (String) -> Cursor, table: String, indexSql: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        query("PRAGMA index_list(${quote(table)})").use { c ->
            val ni = c.getColumnIndex("name")
            val oi = c.getColumnIndex("origin")
            while (c.moveToNext()) {
                val name = (if (ni >= 0) c.getString(ni) else null) ?: continue
                if (oi >= 0 && c.getString(oi) != "c") continue
                indexSql[name.lowercase()]?.let { out[name.lowercase()] = it }
            }
        }
        return out
    }

    /** Null when Room would accept [got] for [want]; a default is only compared when one is declared. */
    private fun mismatch(table: String, want: Column, got: Column): String? {
        val why = when {
            got.type != want.type -> "type ${got.type} where this version declares ${want.type}"
            got.notNull != want.notNull -> "notNull ${got.notNull} where this version declares ${want.notNull}"
            got.pk != want.pk -> "key position ${got.pk} where this version declares ${want.pk}"
            want.defaultValue != null && got.defaultValue != want.defaultValue ->
                "default ${got.defaultValue} where this version declares ${want.defaultValue}"
            else -> return null
        }
        return "$table.${want.name} has $why"
    }

    /** A column's ADD COLUMN definition, rebuilt from the target's own PRAGMA row. */
    private fun definitionOf(col: Column): String = buildString {
        append(quote(col.name))
        if (col.type.isNotEmpty()) append(' ').append(col.type)
        if (col.notNull) append(" NOT NULL")
        if (col.defaultValue != null) append(" DEFAULT ").append(col.defaultValue)
    }

    private fun quote(id: String): String = "`" + id.replace("`", "``") + "`"
}
