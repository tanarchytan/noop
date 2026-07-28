package com.noop.analytics

// ImportTrace.kt: pure line formatters + the live-readout parser for the Import & Data Ingest test mode
// (TestDomain.IMPORT, wire id "import").
//
// An import run reports: parserVersion (importer + version), fileMeta (kind + ext + size BUCKET, never a
// path/name), perStageRows (rows in vs rows the store reported out), rejectCounts (dropped rows + scrubbed
// XML spans), dayDeltas (days mapped vs persisted), and a REDACTED, capped firstFailingRow/failingFileSample.
//
// HARD privacy rule: firstFailingRow + failingFileSample are user data, masked (digits -> #, letters -> x,
// structure kept) and capped HERE before the line reaches the log sink; the export re-scrubs every line
// again. No clock, no IO, no raw PII, no em-dashes.

object ImportTrace {

    /** Bump when a line shape changes. Stamped into the parser line. */
    const val TRACE_VERSION = 1

    // parserVersion + fileMeta

    /** The parser-identity line: which importer ran (wire [kind], e.g. "whoopExport"), its per-importer
     *  schema version, and the trace version. */
    fun parserVersionLine(kind: String, importerVersion: Int): String =
        "import parser=$kind v=$importerVersion traceV=$TRACE_VERSION"

    /** The file-meta line: the detected kind, the lowercased+sanitized extension, and the size BUCKET
     *  (never the byte-exact size, never the name or path). */
    fun fileMetaLine(kind: String, ext: String, sizeBytes: Long): String =
        "import file kind=$kind ext=${safeExt(ext)} size=${sizeBucket(sizeBytes)}"

    // perStageRows + rejectCounts + dayDeltas

    /** One per-stage line: rows handed to the store ([rowsIn]) vs rows it reported writing ([rowsOut]); a
     *  gap is the "mapped but not saved" / day-owner-collision signal. */
    fun stageLine(category: String, rowsIn: Int, rowsOut: Int): String {
        val note = if (rowsOut < rowsIn) " (${rowsIn - rowsOut} not written)" else " (all written)"
        return "import stage=$category rowsIn=$rowsIn rowsOut=$rowsOut$note"
    }

    /** The reject-counts line: rows dropped as unusable + tolerant XML spans scrubbed. */
    fun rejectLine(droppedRows: Int, skippedSpans: Int): String =
        "import rejects droppedRows=$droppedRows skippedSpans=$skippedSpans"

    /** Per-stage line for the Android seam: Room's @Upsert reports no store-write count (fire-and-forget at
     *  this layer), so this emits [rowsIn] but marks rowsOut UNVERIFIED rather than claiming "(all written)" -
     *  the line never asserts a save it cannot confirm. */
    fun stageLineUnverified(category: String, rowsIn: Int): String =
        "import stage=$category rowsIn=$rowsIn rowsOut=? (store-write not verified on Android)"

    /** Honest day-delta line for the Android seam: the distinct days MAPPED, with daysPersisted marked
     *  UNVERIFIED rather than claiming "(all days persisted)", because Room does not report the persisted
     *  count at this layer. Mirror of [stageLineUnverified] for the day-owner-collision tell. */
    fun dayDeltaLineUnverified(category: String, daysMapped: Int): String =
        "import dayDelta stage=$category daysMapped=$daysMapped daysPersisted=? (store-write not verified on Android)"

    /** The day-delta line: distinct local days MAPPED vs days the store PERSISTED; a gap is the
     *  day-owner-collision / "didn't save" tell. */
    fun dayDeltaLine(category: String, daysMapped: Int, daysPersisted: Int): String {
        val note = if (daysPersisted < daysMapped) " (${daysMapped - daysPersisted} days not persisted)"
        else " (all days persisted)"
        return "import dayDelta stage=$category daysMapped=$daysMapped daysPersisted=$daysPersisted$note"
    }

    // firstFailingRow + failingFileSample (redacted, capped)

    /** The first-failing-row line: a REDACTED, length-capped rendering of the first row the parser could
     *  not use. The [headerKeys] are schema (kept); the [rawCells] are user data and are masked cell-by-cell
     *  before the line is built. null when there is no failing row. */
    fun firstFailingRowLine(category: String, rowIndex: Int,
                            headerKeys: List<String>, rawCells: List<String>): String? {
        if (rawCells.isEmpty()) return null
        val masked = rawCells.take(MAX_SAMPLE_CELLS).joinToString(",") { redactCell(it) }
        val cols = headerKeys.take(MAX_SAMPLE_CELLS).joinToString(",")
        val shape = if (cols.isEmpty()) "" else " cols=[${capped(cols)}]"
        return "import firstFailingRow stage=$category row=$rowIndex$shape masked=[${capped(masked)}]"
    }

    /** The failing-file-sample lines: a REDACTED, capped peek at the start of a file that failed to parse
     *  at all. [rawSample] is masked + hard-capped here. emptyList() when there is nothing to sample. */
    fun failingFileSampleLines(kind: String, rawSample: String): List<String> {
        val masked = redactSample(rawSample)
        if (masked.isEmpty()) return emptyList()
        return listOf(
            "import failingFileSample kind=$kind bytes=${sizeBucket(rawSample.toByteArray(Charsets.UTF_8).size.toLong())} " +
                "sample=[$masked]",
        )
    }

    // Redaction + caps (the privacy floor).

    const val MAX_SAMPLE_CELLS = 12
    const val MAX_SAMPLE_CHARS = 200

    /** Mask one cell's value: any Unicode NUMBER -> "#", any letter -> "x", everything else passes through,
     *  so the SHAPE survives but no real datum does. Uses the whole Unicode Number group (decimal Nd, letter-
     *  number Nl, other-number No), not just isDigit() (Nd only), so a superscript or fraction masks too. */
    fun redactCell(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                isUnicodeNumber(ch) -> sb.append('#')
                ch.isLetter() -> sb.append('x')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** True for the whole Unicode Number group (Nd, Nl, No), NOT just decimal digits (Kotlin's
     *  Char.isDigit), so the mask treats superscripts and fractions the same as ordinary digits. */
    private fun isUnicodeNumber(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** Mask a free-form file sample the SAME way as a cell, after collapsing newlines, then hard-cap it. */
    fun redactSample(s: String): String {
        val oneLine = s.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
        return capped(redactCell(oneLine))
    }

    /** Hard-cap a fragment to MAX_SAMPLE_CHARS, appending "..." when trimmed. */
    fun capped(s: String): String =
        if (s.length <= MAX_SAMPLE_CHARS) s else s.substring(0, MAX_SAMPLE_CHARS) + "..."

    /** A coarse size bucket so the line never carries the byte-exact size. */
    fun sizeBucket(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1_024 -> "<1KB"
        bytes < 10_240 -> "1-10KB"
        bytes < 102_400 -> "10-100KB"
        bytes < 1_048_576 -> "100KB-1MB"
        bytes < 10_485_760 -> "1-10MB"
        bytes < 104_857_600 -> "10-100MB"
        bytes < 1_073_741_824 -> "100MB-1GB"
        else -> ">1GB"
    }

    /** Sanitise an extension to a short alphanumeric token. */
    fun safeExt(ext: String): String {
        val t = ext.lowercase().filter { it.isLetter() || it.isDigit() }
        return if (t.isEmpty()) "none" else t.take(8)
    }

    /** Maps an importer's raw Room-table count key (e.g. "dailyMetric", "sleepSession", "workout") to the
     *  canonical wire CATEGORY name, so every per-stage line keys off the same stage= name regardless of
     *  source: WHOOP's dailyMetric/sleepSession become "cycles"/"sleeps"; unmapped keys pass through. */
    fun categoryWire(source: String, rawKey: String): String = when (source) {
        // WHOOP export (WhoopCsvImporter SOURCE_LABEL "WHOOP").
        "WHOOP" -> when (rawKey) {
            "dailyMetric" -> "cycles"
            "sleepSession" -> "sleeps"
            "workout" -> "workouts"
            else -> rawKey   // journal, metricSeries: unmapped, keep the raw key
        }
        // Apple Health (AppleHealthImporter SOURCE_LABEL).
        "Apple Health" -> when (rawKey) {
            "workout" -> "workouts"
            else -> rawKey   // appleDaily, dailyMetric, metricSeries: already canonical
        }
        // Any other source: only normalise the singular table name to the plural category where it exists.
        else -> when (rawKey) {
            "workout" -> "workouts"
            else -> rawKey
        }
    }

    /** Map an importer's human source label (ImportSummary.source) to the canonical wire-kind string (e.g.
     *  "whoopExport"). An unknown label passes through lowercased + sanitized so a new source never crashes
     *  or leaks free text. */
    fun kindWire(source: String): String = when (source) {
        "WHOOP" -> "whoopExport"
        "Apple Health" -> "appleHealth"
        "Xiaomi Smart Band" -> "xiaomiBand"
        "Oura" -> "ouraImport"
        "Fitbit" -> "fitbitImport"
        "Garmin" -> "garminImport"
        else -> source.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "unknown" }
    }
}
