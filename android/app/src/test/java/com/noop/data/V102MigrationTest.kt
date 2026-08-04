package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the v101 -> v102 step: the four additions, that every statement is additive, and that each
 * CREATE TABLE matches its entity's field order. This environment has no Robolectric / Room-testing,
 * so the SQL is pinned to Room's generated shape and cross-checked against the entity by reflection —
 * a field added or reordered without its column fails here rather than at open on a user's phone.
 */
class V102MigrationTest {

    private val hardwareRev = WhoopDatabase.HARDWARE_REV_MIGRATION_SQL
    private val imu = WhoopDatabase.IMU_FEATURE_MIGRATION_SQL
    private val ecg = WhoopDatabase.ECG_RHYTHM_MIGRATION_SQL
    private val coach = WhoopDatabase.COACH_MESSAGE_MIGRATION_SQL
    private val all = hardwareRev + imu + ecg + coach

    /** The column names of a CREATE TABLE, in the order it declares them. */
    private fun columnsOf(createSql: String): List<String> =
        Regex("`([A-Za-z0-9_]+)` (TEXT|INTEGER|REAL|BLOB)")
            .findAll(createSql.substringAfter("(")).map { it.groupValues[1] }.toList()

    /** An entity's property names in declaration order, as Kotlin lays a data class out on the JVM.
     *  Static fields are dropped: the Compose compiler adds a `$stable` one to every class it sees. */
    private fun fieldsOf(entity: Class<*>): List<String> = entity.declaredFields
        .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.name }

    private fun statementFor(table: String): String =
        all.first { it.startsWith("CREATE TABLE IF NOT EXISTS `$table`") }

    // ── the step itself ──────────────────────────────────────────────────────────────────────────────

    /** A device already carries v101 with real data, so these additions take their own version instead
     *  of changing v101's identity hash under an installed store. */
    @Test
    fun theStepIs101To102AndIsTheCurrentSchema() {
        assertEquals(101, WhoopDatabase.MIGRATION_101_102.startVersion)
        assertEquals(102, WhoopDatabase.MIGRATION_101_102.endVersion)
        assertEquals(102, WhoopDatabase.SCHEMA_VERSION)
        assertTrue(
            "the step must be wired into the migration list",
            WhoopDatabase.ALL_MIGRATIONS.any { it.startVersion == 101 && it.endVersion == 102 },
        )
    }

    /** v100 keeps its own step, so a released install walks 100 -> 101 -> 102 with both steps additive
     *  rather than needing a new one-hop migration written for it. */
    @Test
    fun aV100InstallStillReachesTheCurrentSchema() {
        val steps = WhoopDatabase.ALL_MIGRATIONS.groupBy { it.startVersion }
        var at = 100
        var hops = 0
        while (at < WhoopDatabase.SCHEMA_VERSION && hops < 200) {
            at = steps[at]?.maxOf { it.endVersion } ?: break
            hops++
        }
        assertEquals(WhoopDatabase.SCHEMA_VERSION, at)
        assertEquals("v100 must reach it in exactly two additive hops", 2, hops)
    }

    /** ALTER ADD COLUMN and CREATE are metadata-only in SQLite, so this step costs the same on a
     *  gigabyte-scale store as on an empty one — and nothing it runs can lose a row. */
    @Test
    fun everyStatementIsAdditive() {
        for (stmt in all) {
            val up = stmt.uppercase()
            assertTrue(
                "not an additive statement: $stmt",
                up.startsWith("ALTER TABLE") && up.contains(" ADD COLUMN ") ||
                    up.startsWith("CREATE TABLE IF NOT EXISTS") ||
                    up.startsWith("CREATE INDEX IF NOT EXISTS"),
            )
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", " RENAME ")) {
                assertFalse("the step must stay additive: $banned in $stmt", up.contains(banned))
            }
        }
    }

    /** The four additions are exactly four groups, in the order the migration runs them. */
    @Test
    fun theStepCarriesTheFourAdditionsAndNothingElse() {
        assertEquals("one ADD COLUMN for the device row", 1, hardwareRev.size)
        assertEquals("one CREATE for the feature vector", 1, imu.size)
        assertEquals("three tables and their three indexes", 6, ecg.size)
        assertEquals("one CREATE for the transcript", 1, coach.size)
        assertEquals(9, all.size)
        assertEquals("no statement is run twice", all.size, all.toSet().size)
    }

    // ── 1. hardwareRev on the device row ─────────────────────────────────────────────────────────────

    /** Nullable with no default: a row whose strap was never read stays honestly unknown, so nothing
     *  downstream can mistake an unread device for a classified one. */
    @Test
    fun hardwareRevIsAddedNullable() {
        assertEquals("ALTER TABLE `pairedDevice` ADD COLUMN `hardwareRev` TEXT", hardwareRev.single())
        assertFalse(hardwareRev.single().uppercase().contains("NOT NULL"))
        assertFalse(hardwareRev.single().uppercase().contains("DEFAULT"))
        assertEquals("an unread revision reads back null", null, deviceRow("x").hardwareRev)
    }

    /** The device row's field order carries the new column last, which is the only position an
     *  ALTER ADD COLUMN can produce. */
    @Test
    fun hardwareRevIsTheDeviceRowsLastField() {
        assertEquals("hardwareRev", fieldsOf(PairedDeviceRow::class.java).last())
    }

    // ── 2. the IMU feature vector ────────────────────────────────────────────────────────────────────

    @Test
    fun imuFeatureTableMatchesItsEntity() {
        val sql = statementFor("imuFeatureSample")
        assertEquals(fieldsOf(ImuFeatureSample::class.java), columnsOf(sql))
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `imuFeatureSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `windowS` INTEGER NOT NULL, `sampleRateHz` INTEGER NOT NULL, " +
                "`accelEnergyG` REAL NOT NULL, `gyroEnergyDps` REAL NOT NULL, `jerkRms` REAL NOT NULL, " +
                "`cadenceHz` REAL, `cadenceStrength` REAL NOT NULL, `sampleCount` INTEGER NOT NULL, " +
                "`algoVersion` TEXT NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
            sql,
        )
    }

    /** A window with no rhythmic peak stores no cadence. The column is nullable so an absent cadence
     *  stays absent instead of arriving downstream as a real 0 Hz. */
    @Test
    fun anAbsentCadenceStaysNull() {
        assertTrue(statementFor("imuFeatureSample").contains("`cadenceHz` REAL,"))
        assertFalse(statementFor("imuFeatureSample").contains("`cadenceHz` REAL NOT NULL"))
    }

    /** The rate and window a vector was extracted at are NOT NULL: a feature is only interpretable
     *  against them, and the raw samples it came from are not kept. */
    @Test
    fun theExtractionProvenanceIsRequired() {
        val sql = statementFor("imuFeatureSample")
        for (col in listOf("windowS", "sampleRateHz", "algoVersion")) {
            assertTrue("$col must be required", Regex("`$col` (INTEGER|TEXT) NOT NULL").containsMatchIn(sql))
        }
    }

    // ── 3. ECG + both rhythm paths ───────────────────────────────────────────────────────────────────

    @Test
    fun ecgSessionTableMatchesItsEntity() {
        val sql = statementFor("ecgSession")
        assertEquals(fieldsOf(EcgSessionRow::class.java), columnsOf(sql))
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `ecgSession` (`id` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                "`startUnix` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`sampleCount` INTEGER NOT NULL, `samples` BLOB NOT NULL, `sampleRateHz` REAL NOT NULL, " +
                "`sampleRateSource` TEXT NOT NULL, `countsPerMv` REAL, `countsPerMvSource` TEXT, " +
                "`layoutJson` TEXT, `sweepQuality` REAL, `sweepMargin` REAL, `leadOffJson` TEXT, " +
                "`wrist` TEXT, `firmwareVersion` TEXT, `hardwareRev` TEXT, `strapVariant` TEXT, " +
                "PRIMARY KEY(`id`))",
            sql,
        )
    }

    /**
     * The capture stores RAW COUNTS and a nullable scale, never millivolts. counts-per-mV is unknown, so
     * a stored millivolt would be wrong by an unknown factor and every capture taken before the constant
     * lands would be unusable; stored this way the constant calibrates them all by re-derivation.
     */
    @Test
    fun theCaptureStoresCountsAndNeverMillivolts() {
        val sql = statementFor("ecgSession")
        assertTrue("the sample stream is a required blob", sql.contains("`samples` BLOB NOT NULL"))
        assertTrue("the scale is nullable, and null means uncalibrated", sql.contains("`countsPerMv` REAL,"))
        assertFalse(sql.contains("`countsPerMv` REAL NOT NULL"))
        val mvColumns = columnsOf(sql).filter { it.contains("Mv") }
        assertEquals(listOf("countsPerMv", "countsPerMvSource"), mvColumns)
        for (col in columnsOf(sql)) {
            assertFalse("no column may hold a converted amplitude: $col", col.lowercase().contains("microvolt"))
            assertFalse("no column may hold a converted amplitude: $col", col.endsWith("Uv"))
        }
    }

    /** The rate used and where it came from are both required, so a measured rate and an assumed one
     *  are never confused for one another by a later reader. */
    @Test
    fun theRateAndItsProvenanceAreBothRequired() {
        val sql = statementFor("ecgSession")
        assertTrue(sql.contains("`sampleRateHz` REAL NOT NULL"))
        assertTrue(sql.contains("`sampleRateSource` TEXT NOT NULL"))
    }

    /** The decode's ranking scalar and its margin stay two columns. One fused confidence cannot be
     *  debugged when a decode goes wrong. */
    @Test
    fun theSweepScoreAndItsMarginAreNotFused() {
        val cols = columnsOf(statementFor("ecgSession"))
        assertTrue("sweepQuality" in cols)
        assertTrue("sweepMargin" in cols)
    }

    @Test
    fun rhythmScreenTableMatchesItsEntity() {
        val sql = statementFor("rhythmScreen")
        assertEquals(fieldsOf(RhythmScreenRow::class.java), columnsOf(sql))
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `rhythmScreen` (`deviceId` TEXT NOT NULL, " +
                "`startUnix` INTEGER NOT NULL, `scope` TEXT NOT NULL, `algoVersion` TEXT NOT NULL, " +
                "`durationS` INTEGER NOT NULL, `verdict` TEXT, `refusal` TEXT, " +
                "`windowsAssessed` INTEGER, `windowsIrregular` INTEGER, `episodeWindows` INTEGER, " +
                "`confidence` TEXT, `cosen` REAL, `residualCosen` REAL, `rmssdOverMean` REAL, " +
                "`shannonEntropy` REAL, `turningPointRatio` REAL, `sampleEntropy` REAL, `sd1` REAL, " +
                "`sd2` REAL, `cellOccupancy` REAL, `ectopicFraction` REAL, `meanRrMs` REAL, " +
                "`beatsUsed` INTEGER, `beatsRejected` INTEGER, `duplicateFraction` REAL, " +
                "`rescaledFraction` REAL, `coverage` REAL, " +
                "PRIMARY KEY(`deviceId`, `startUnix`, `scope`, `algoVersion`))",
            sql,
        )
    }

    /** Each index is its own column, so a moved threshold re-judges stored rows instead of needing a
     *  new recording. A single fused score could do neither. */
    @Test
    fun everyRhythmIndexHasItsOwnColumn() {
        val cols = columnsOf(statementFor("rhythmScreen"))
        for (index in listOf(
            "cosen", "residualCosen", "rmssdOverMean", "shannonEntropy", "turningPointRatio",
            "sampleEntropy", "sd1", "sd2", "cellOccupancy", "ectopicFraction",
        )) {
            assertTrue("$index must be stored on its own", index in cols)
        }
        for (fused in cols) {
            assertFalse("a fused score is not storable here: $fused", fused.lowercase().contains("score"))
        }
    }

    /** The algorithm version is part of the key, so re-running a screen adds a row beside the original
     *  instead of silently overwriting what it said. */
    @Test
    fun aReRunLandsBesideTheOriginal() {
        assertTrue(
            statementFor("rhythmScreen")
                .contains("PRIMARY KEY(`deviceId`, `startUnix`, `scope`, `algoVersion`)"),
        )
        assertTrue(statementFor("rhythmMorphology").contains("PRIMARY KEY(`ecgSessionId`, `algoVersion`)"))
    }

    /** The cleaning cost travels with the reading: an index computed over dirty R-R means nothing, and
     *  the duplication shares are what let a stored row be disbelieved later. */
    @Test
    fun theInputQualityIsStoredWithTheReading() {
        val cols = columnsOf(statementFor("rhythmScreen"))
        for (col in listOf("beatsUsed", "beatsRejected", "duplicateFraction", "rescaledFraction", "coverage")) {
            assertTrue("$col must travel with the reading", col in cols)
        }
    }

    @Test
    fun rhythmMorphologyTableMatchesItsEntity() {
        val sql = statementFor("rhythmMorphology")
        assertEquals(fieldsOf(RhythmMorphologyRow::class.java), columnsOf(sql))
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `rhythmMorphology` (`ecgSessionId` TEXT NOT NULL, " +
                "`algoVersion` TEXT NOT NULL, `fsHz` REAL NOT NULL, `beats` INTEGER NOT NULL, " +
                "`pWaveFinding` TEXT NOT NULL, `pWaveLimit` TEXT, `pWaveBeatsExamined` INTEGER, " +
                "`pWaveBeatsExcluded` INTEGER, `pWavePresentFraction` REAL, `pWaveConsistency` REAL, " +
                "`pWaveAmplitudeRatio` REAL, `pWaveNoiseRatio` REAL, `pWaveConfidence` REAL, " +
                "`atrialBandRatio` REAL, `atrialBandSegments` INTEGER, " +
                "`atrialBandMedianSegmentMs` REAL, `atrialBandConfidence` REAL, " +
                "`atrialBandLimit` TEXT, `beatTemplateCorrelation` REAL, `beatTemplateBeats` INTEGER, " +
                "`beatTemplateConfidence` REAL, `bSqi` REAL, `bExcess` REAL, `kSqi` REAL, " +
                "`pSqi` REAL, `basSqi` REAL, PRIMARY KEY(`ecgSessionId`, `algoVersion`))",
            sql,
        )
    }

    /**
     * The P-wave finding is TEXT and required, never a 0/1. At wrist amplitudes a wave under the noise
     * floor is not a wave that is absent, and a boolean column can only store one of those two claims.
     */
    @Test
    fun thePWaveFindingIsThreeWayAndNeverABoolean() {
        val sql = statementFor("rhythmMorphology")
        assertTrue(sql.contains("`pWaveFinding` TEXT NOT NULL"))
        assertFalse("a boolean column cannot hold Indeterminate", sql.contains("`pWaveFinding` INTEGER"))
        assertTrue("the limit behind an Indeterminate stays legible", sql.contains("`pWaveLimit` TEXT"))
    }

    /** The two paths stay two tables. One needs an electrode and one does not, so a combined number
     *  would mean something different depending on which strap produced it. */
    @Test
    fun theRRPathAndTheEcgPathAreNeverFused() {
        val screen = columnsOf(statementFor("rhythmScreen"))
        val morphology = columnsOf(statementFor("rhythmMorphology"))
        val shared = screen.intersect(morphology.toSet()) - "algoVersion"
        assertEquals("the two paths share no measurement column", emptySet<String>(), shared)
    }

    // ── 4. the coach transcript ──────────────────────────────────────────────────────────────────────

    @Test
    fun coachMessageTableMatchesItsEntity() {
        val sql = statementFor("coachMessage")
        assertEquals(fieldsOf(CoachMessageRow::class.java), columnsOf(sql))
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `coachMessage` (`conversationId` TEXT NOT NULL, " +
                "`seq` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `provider` TEXT, `model` TEXT, " +
                "`contextIncluded` INTEGER NOT NULL, `error` TEXT, " +
                "PRIMARY KEY(`conversationId`, `seq`))",
            sql,
        )
    }

    /** A turn has one position in its conversation, so a replay writes the same row rather than a
     *  duplicate. */
    @Test
    fun aTurnsPositionIsItsKey() {
        assertTrue(statementFor("coachMessage").contains("PRIMARY KEY(`conversationId`, `seq`)"))
    }

    // ── indexes ──────────────────────────────────────────────────────────────────────────────────────

    /** Room names an entity index `index_<table>_<cols>`; the migration must create the same names, or
     *  a migrated store and a freshly created one would not compare equal at open. */
    @Test
    fun theIndexesCarryRoomsOwnNames() {
        assertEquals(
            listOf(
                "CREATE INDEX IF NOT EXISTS `index_ecgSession_deviceId_startUnix` " +
                    "ON `ecgSession` (`deviceId`, `startUnix`)",
                "CREATE INDEX IF NOT EXISTS `index_rhythmScreen_deviceId_startUnix` " +
                    "ON `rhythmScreen` (`deviceId`, `startUnix`)",
                "CREATE INDEX IF NOT EXISTS `index_rhythmMorphology_ecgSessionId` " +
                    "ON `rhythmMorphology` (`ecgSessionId`)",
            ),
            ecg.filter { it.startsWith("CREATE INDEX") },
        )
    }

    // Which tables a per-device delete actually reaches is asserted in DeviceRegistryTest, where a fake
    // DAO records the table names the fan-out clears. Room's @Query is BINARY-retained, so a check here
    // could only compare method NAMES and would pass while pointing at the wrong table.
}
