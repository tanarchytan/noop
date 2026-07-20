# Android Strap Clock Correlation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire decoded WHOOP 4 clock replies into Android backfill timestamp correlation without changing WHOOP 5/MG wire behavior.

**Architecture:** Introduce a small shared `ClockReference` state object. `WhoopBleClient` updates and resets it; `Backfiller` reads it at chunk decode time.

**Tech Stack:** Kotlin, Android BLE, JUnit 4, Gradle.

---

### Task 1: Clock reference lifecycle

**Files:**
- Create: `android/app/src/main/java/com/noop/ble/ClockReference.kt`
- Create: `android/app/src/test/java/com/noop/ble/ClockReferenceTest.kt`
- Modify: `android/app/src/main/java/com/noop/ble/Backfiller.kt`

- [ ] **Step 1: Write failing lifecycle tests**

Cover identity construction, valid reply acceptance, invalid parse/CRC rejection, first-reply retention, and reset.

- [ ] **Step 2: Verify tests fail**

Run: `android\gradlew.bat -p android :app:testFullDebugUnitTest --tests com.noop.ble.ClockReferenceTest`

Expected: compilation failure because `ClockReference` does not exist.

- [ ] **Step 3: Implement minimal shared state**

Create `ClockReference` with synchronized `accept` and `reset` operations plus a volatile current `ClockRef`. Move `ClockRef` from `Backfiller.kt` into the focused new file.

- [ ] **Step 4: Verify focused tests pass**

Run: `android\gradlew.bat -p android :app:testFullDebugUnitTest --tests com.noop.ble.ClockReferenceTest`

Expected: all `ClockReferenceTest` cases pass.

### Task 2: BLE routing

**Files:**
- Modify: `android/app/src/main/java/com/noop/ble/WhoopBleClient.kt`
- Modify: `android/app/src/main/java/com/noop/ble/Backfiller.kt`

- [ ] **Step 1: Share reference state**

Construct one `ClockReference` before `Backfiller`, then pass it into `Backfiller`.

- [ ] **Step 2: Consume command replies**

In the existing `COMMAND_RESPONSE` branch, accept the first valid decoded clock and log its device/wall pair.

Reject notification callbacks from any `BluetoothGatt` instance that is no longer active.

- [ ] **Step 3: Reset connection state**

Reset `ClockReference` inside `WhoopBleClient.reset()` so a previous strap offset cannot leak into another connection.

- [ ] **Step 4: Verify focused tests**

Run: `android\gradlew.bat -p android :app:testFullDebugUnitTest --tests com.noop.ble.ClockReferenceTest`

Expected: all focused tests pass.

### Task 3: Regression verification

**Files:**
- Review all modified files.

- [ ] **Step 1: Remove touched-file cruft**

Delete obsolete `Backfiller.clockRef` comments and duplicated clock state. Keep unrelated code unchanged.

Keep live correlation from applying stale-clock correction to already-banked type-47/event Unix timestamps.

- [ ] **Step 2: Run Android tests**

Run: `android\gradlew.bat -p android :app:testFullDebugUnitTest`

Expected: zero failed tests.

- [ ] **Step 3: Run lint**

Run: `android\gradlew.bat -p android :app:lintFullDebug`

Expected: build succeeds without new lint errors.

- [ ] **Step 4: Build debug APK**

Run: `android\gradlew.bat -p android :app:assembleFullDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Review final diff**

Confirm no WHOOP 5/MG opcode, FFI, timestamp extraction, or unrelated dirty UI file changed.
