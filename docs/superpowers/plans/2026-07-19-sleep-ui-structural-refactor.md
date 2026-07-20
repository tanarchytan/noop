# Sleep UI Structural Refactor Implementation Plan

**Status:** DONE (verified 2026-07-19). All 6 files extracted, structure test green, verification report at `docs/superpowers/reports/2026-07-19-sleep-ui-refactor-verification.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the 3,814-line sleep UI monolith into cohesive Kotlin files without changing runtime behavior.

**Architecture:** Keep `SleepScreen.kt` as orchestration. Extract pure models, timeline rendering, editing, hero presentation, and metric presentation into package-local files. Preserve every existing signature used by tests and neighboring screens.

**Tech Stack:** Kotlin, Jetpack Compose, Android lifecycle Compose, JUnit 4, Gradle.

---

## Constraints

- Preserve current uncommitted sleep changes.
- Move declarations without rewriting behavior.
- Keep package `com.noop.ui`.
- Keep externally referenced `internal` names unchanged.
- Promote `private` to `internal` only when cross-file access requires it.
- Keep comments within the three-line house rule.
- Do not stage production files. Existing working-tree changes predate this refactor.

### Task 1: Pin file boundaries

**Files:**
- Create: `android/app/src/test/java/com/noop/ui/SleepUiStructureTest.kt`

- [ ] **Step 1: Write failing structure test**

```kotlin
package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SleepUiStructureTest {
    private fun uiSourceDir(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(userDir, "src/main/java/com/noop/ui"),
            File(userDir, "app/src/main/java/com/noop/ui"),
            File(userDir, "android/app/src/main/java/com/noop/ui"),
        ).firstOrNull { it.isDirectory }
    }

    @Test
    fun sleepUiRemainsSplitByResponsibility() {
        val dir = uiSourceDir()
        assumeTrue("UI sources unavailable", dir != null)

        val expectedOwners = mapOf(
            "SleepModels.kt" to listOf("internal data class SleepModel", "internal fun selectNight"),
            "SleepTimeline.kt" to listOf("internal fun parsePersistedSegments", "internal fun displaySmoothed"),
            "SleepEditor.kt" to listOf("internal fun NightNavHeader", "internal fun NapRow"),
            "SleepHero.kt" to listOf("internal fun RestHero", "internal fun Hero"),
            "SleepMetrics.kt" to listOf("internal fun MetricGrid", "internal fun SleepConsistencyCard"),
        )

        expectedOwners.forEach { (name, declarations) ->
            val source = File(dir, name)
            assertTrue("missing $name", source.isFile)
            val text = source.readText()
            declarations.forEach { declaration ->
                assertTrue("$declaration must live in $name", declaration in text)
            }
        }

        val screen = File(dir, "SleepScreen.kt").readText()
        assertTrue("SleepScreen.kt must remain orchestration-sized", screen.lineSequence().count() < 700)
        expectedOwners.values.flatten().forEach { declaration ->
            assertFalse("$declaration leaked into SleepScreen.kt", declaration in screen)
        }
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
./gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.SleepUiStructureTest' --console=plain
```

Expected: FAIL because extracted files do not exist.

### Task 2: Extract pure models

**Files:**
- Create: `android/app/src/main/java/com/noop/ui/SleepModels.kt`
- Modify: `android/app/src/main/java/com/noop/ui/SleepScreen.kt`
- Test: existing `android/app/src/test/java/com/noop/ui/*Sleep*Test.kt`

- [ ] **Step 1: Move model declarations**

Move `Stages` through `stageRowSpans` from `SleepScreen.kt` into `SleepModels.kt`, except timeline-owned declarations listed in Task 3. Keep these model-owned declarations together:

```text
Stages, Metric, ImportedSleepSeries, SleepModel, HeroNight, HeroDisplay,
selectNight, mainSleepBlock, mainSleepGroup, mainSleepSpan,
decodedAsleepMinutes, isPreOnsetAwakeStub, sumGroupStages, uiTzOffsetSec,
heroDisplay, stagesFromSegments, StageMins, parseSessionStages,
buildSleepModel, fallbackSleepModel, metricAtDay, consistencySeries,
mean, stageSegments, pctValue, vsTypical, debt formatting helpers,
durationText, shortDayLabel, averageOrNull, and clock/day formatters
```

Leave `PersistedSegment` through `stageRowSpans` for Task 3. Use existing code verbatim. Change only cross-file declarations from `private` to `internal`.

- [ ] **Step 2: Compile models extraction**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
./gradlew.bat compileFullDebugKotlin --console=plain
```

Expected: PASS. Existing unrelated warnings may remain.

- [ ] **Step 3: Run model tests**

Run:

```powershell
./gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.MainSleepSpanTest' --tests 'com.noop.ui.SleepOnsetStubTest' --tests 'com.noop.ui.SleepImportedFiguresTest' --tests 'com.noop.ui.SleepPhantomNightFallbackTest' --console=plain
```

Expected: PASS.

### Task 3: Extract timeline rendering

**Files:**
- Create: `android/app/src/main/java/com/noop/ui/SleepTimeline.kt`
- Modify: `android/app/src/main/java/com/noop/ui/SleepScreen.kt`
- Test: `android/app/src/test/java/com/noop/ui/SleepStageSegmentsTest.kt`
- Test: `android/app/src/test/java/com/noop/ui/StageDisplaySmoothingTest.kt`
- Test: `android/app/src/test/java/com/noop/ui/StageTimelineIntervalsTest.kt`
- Test: `android/app/src/test/java/com/noop/ui/StageRowSpansTest.kt`

- [ ] **Step 1: Move timeline declarations**

Move these declarations verbatim into `SleepTimeline.kt`:

```text
StageBreakdownRows, StageBreakdownRow, HypnogramWithAxis, ClockLabelRow,
STAGE_ROW_SMOOTH_SEC, StageTimeline, StageTimelineRow, StageRowTrack,
StageInsight, stageInsightLine, MotionStrip, stageColorFor,
PersistedSegment, parsePersistedSegments, StageInterval,
stageIntervalsFromWeights, displaySmoothed, canonicalStage, stageRowSpans
```

Promote composables and helpers called from other sleep files to `internal`. Keep file-local helpers `private`.

- [ ] **Step 2: Run timeline tests**

Run:

```powershell
./gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.SleepStageSegmentsTest' --tests 'com.noop.ui.StageDisplaySmoothingTest' --tests 'com.noop.ui.StageTimelineIntervalsTest' --tests 'com.noop.ui.StageRowSpansTest' --console=plain
```

Expected: PASS.

### Task 4: Extract editing

**Files:**
- Create: `android/app/src/main/java/com/noop/ui/SleepEditor.kt`
- Modify: `android/app/src/main/java/com/noop/ui/SleepScreen.kt`
- Test: `android/app/src/test/java/com/noop/ui/SleepEditRescoreTest.kt`

- [ ] **Step 1: Move editor declarations**

Move these declarations verbatim into `SleepEditor.kt`:

```text
SleepMarkCard, SleepUndoBanner, NapRow, NightNavHeader
```

Move picker-related imports with them. Promote `NapRow` and `NightNavHeader` to `internal`; keep card helpers `internal` when called by `SleepScreen.kt`.

- [ ] **Step 2: Apply autoboxing cleanup**

Replace editor-owned long state boxes only:

```kotlin
var pendingStart by remember(nap.startTs) { mutableLongStateOf(0L) }
var napStartTs by remember { mutableLongStateOf(0L) }
```

Add `androidx.compose.runtime.mutableLongStateOf`. Behavior remains unchanged.

- [ ] **Step 3: Compile editor extraction**

Run:

```powershell
./gradlew.bat compileFullDebugKotlin --console=plain
```

Expected: PASS with no `SleepEditor.kt` compiler warnings.

- [ ] **Step 4: Run edit test**

Run:

```powershell
./gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.SleepEditRescoreTest' --console=plain
```

Expected: PASS.

### Task 5: Extract hero presentation

**Files:**
- Create: `android/app/src/main/java/com/noop/ui/SleepHero.kt`
- Modify: `android/app/src/main/java/com/noop/ui/SleepScreen.kt`

- [ ] **Step 1: Move hero declarations**

Move these declarations verbatim into `SleepHero.kt`:

```text
LIQUID_HERO_FILL, LIQUID_HERO_RADIUS, RestHero, SleepHeroVessel,
sleepScoreWord, restHeroSource, Hero, NapsCard, MainSleepFooter,
mainSleepReasonText, NapSummaryCell, SleepWindowRow, SleepTime
```

Promote declarations called from `SleepScreen.kt` to `internal`. Keep remaining helpers `private`.

- [ ] **Step 2: Compile hero extraction**

Run:

```powershell
./gradlew.bat compileFullDebugKotlin --console=plain
```

Expected: PASS.

- [ ] **Step 3: Run hero helper tests**

Run:

```powershell
./gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.MainSleepReasonCopyTest' --tests 'com.noop.ui.MainSleepSpanTest' --console=plain
```

Expected: PASS.

### Task 6: Extract metrics presentation

**Files:**
- Create: `android/app/src/main/java/com/noop/ui/SleepMetrics.kt`
- Modify: `android/app/src/main/java/com/noop/ui/SleepScreen.kt`

- [ ] **Step 1: Move metric declarations**

Move these declarations verbatim into `SleepMetrics.kt`:

```text
MetricGrid, SleepDebtLedgerCard, DebtDeltaBars, StagesVsTypical,
Hairline, StageRow, drawRoundRectFill, DurationTrend, TrendPlaceholder,
DateAxisRow, ChartCard, ChartFooter, SparkTile, SleepEmptyState,
HoursVsNeededCard, LegendDot, SleepNightTiming, SleepConsistencyCard,
SleepMetricSpec, sleepMetricSpec, buildSleepMetricPoints,
filterSleepMetricPoints, SleepMetricDetailSheetContent
```

Promote declarations called from `SleepScreen.kt` to `internal`. Keep drawing and formatting helpers `private`.

- [ ] **Step 2: Compile metrics extraction**

Run:

```powershell
./gradlew.bat compileFullDebugKotlin --console=plain
```

Expected: PASS.

### Task 7: Finish orchestration file

**Files:**
- Modify: `android/app/src/main/java/com/noop/ui/SleepScreen.kt`
- Modify: extracted sleep files
- Test: `android/app/src/test/java/com/noop/ui/SleepUiStructureTest.kt`

- [ ] **Step 1: Remove unused imports**

Keep only imports referenced by each file. Preserve package, annotations, and entry-point signature.

- [ ] **Step 2: Verify GREEN structure test**

Run:

```powershell
./gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.SleepUiStructureTest' --console=plain
```

Expected: PASS. `SleepScreen.kt` remains below 700 lines.

- [ ] **Step 3: Run all sleep UI tests**

Run:

```powershell
./gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.*Sleep*' --tests 'com.noop.ui.Stage*' --console=plain
```

Expected: PASS.

### Task 8: Full verification and review

**Files:**
- Review: all `android/app/src/main/java/com/noop/ui/Sleep*.kt`
- Review: `android/app/src/test/java/com/noop/ui/SleepUiStructureTest.kt`

- [ ] **Step 1: Run full unit suite**

```powershell
./gradlew.bat testFullDebugUnitTest --console=plain
```

Expected: PASS.

- [ ] **Step 2: Compile full app**

```powershell
./gradlew.bat compileFullDebugKotlin --console=plain
```

Expected: PASS.

- [ ] **Step 3: Assemble debug APK**

```powershell
./gradlew.bat assembleFullDebug --console=plain
```

Expected: PASS.

- [ ] **Step 4: Check textual integrity**

```powershell
git diff --check -- android/app/src/main/java/com/noop/ui/SleepScreen.kt android/app/src/main/java/com/noop/ui/SleepModels.kt android/app/src/main/java/com/noop/ui/SleepTimeline.kt android/app/src/main/java/com/noop/ui/SleepEditor.kt android/app/src/main/java/com/noop/ui/SleepHero.kt android/app/src/main/java/com/noop/ui/SleepMetrics.kt android/app/src/test/java/com/noop/ui/SleepUiStructureTest.kt
```

Expected: no output.

- [ ] **Step 5: Review Kotlin boundaries**

Confirm no circular dependency, duplicate declaration, BOM, CRLF corruption, behavior rewrite, new hardcoded design value, or comment longer than three lines.

- [ ] **Step 6: Record baseline lint status**

Run:

```powershell
./gradlew.bat lintFullDebug --console=plain
```

Expected: existing project-wide lint failures remain. No sleep dead-code warning appears. Two autoboxing suggestions disappear.
