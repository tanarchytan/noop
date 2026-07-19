# Remove Terms Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the mandatory terms gate and make Bluetooth onboarding the compact NOOP welcome screen.

**Architecture:** `NoopRoot` will route new users directly into `OnboardingScreen`. A source-structure regression test will pin removal of every runtime terms artifact and the approved first-step copy. Repository `TERMS.md` remains unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, Android resources, JUnit 4, Gradle

---

### Task 1: Pin First-Run Structure

**Files:**
- Create: `android/app/src/test/java/com/noop/ui/FirstRunFlowStructureTest.kt`

- [ ] **Step 1: Write the failing test**

Create a JUnit test that resolves `src/main` from Android, repository-root, or workspace-root execution and asserts:

```kotlin
assertFalse(File(uiDir, "TermsGate.kt").exists())
assertFalse(mainActivity.contains("TermsGateScreen"))
assertFalse(mainActivity.contains("KEY_ACCEPTED_TERMS_VERSION"))
assertFalse(mainActivity.contains("KEY_ACCEPTED_TERMS_AT"))
assertFalse(strings.contains("name=\"terms_"))
assertTrue(onboarding.contains("Bluetooth(\"Begin setup\")"))
assertTrue(onboarding.contains("title = \"Welcome to NOOP\""))
assertTrue(onboarding.contains("subtitle = \"Your wearables. Your data.\""))
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.FirstRunFlowStructureTest' --no-daemon
```

Expected: FAIL because `TermsGate.kt` and root-gate references still exist.

### Task 2: Remove Runtime Gate

**Files:**
- Delete: `android/app/src/main/java/com/noop/ui/TermsGate.kt`
- Modify: `android/app/src/main/java/com/noop/ui/MainActivity.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-de/strings.xml`

- [ ] **Step 1: Delete terms UI**

Delete `TermsGate.kt`, including `Terms` and `TermsGateScreen`.

- [ ] **Step 2: Remove root gating**

Delete both accepted-terms preference keys and the complete `acceptedTerms` state/branch from `NoopRoot`. Update surrounding comments to describe onboarding only.

- [ ] **Step 3: Remove resources**

Delete every `terms_*` string and its terms-gate comments from English and German resources. Keep `TERMS.md` unchanged.

### Task 3: Add Compact Welcome

**Files:**
- Modify: `android/app/src/main/java/com/noop/ui/OnboardingScreen.kt`

- [ ] **Step 1: Update first-step content**

Use the approved copy:

```kotlin
Bluetooth("Begin setup")

StepShell(
    title = "Welcome to NOOP",
    subtitle = "Your wearables. Your data.",
)
```

Replace the Bluetooth checklines with one `InfoCard`:

```kotlin
InfoCard(
    icon = Icons.Filled.Bluetooth,
    tint = Palette.accent,
    title = "Connect your wearable",
    message = "NOOP uses Bluetooth to find and connect to your nearby devices.",
)
```

- [ ] **Step 2: Hide first-step Back**

Render `OutlinedButton` only when `canGoBack`. Give the first-step primary button `Modifier.fillMaxWidth()`; later primary buttons retain weighted layout.

- [ ] **Step 3: Verify GREEN**

Run the focused test from Task 1. Expected: PASS.

### Task 4: Verify Application

**Files:**
- Verify only: all touched production and test files

- [ ] **Step 1: Run full tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat testFullDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build demo release**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDemoRelease --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Audit removal**

Search `android/app/src/main` and `android/app/src/test` for `TermsGateScreen`, `KEY_ACCEPTED_TERMS`, and `name=\"terms_`. Expected: no matches.

- [ ] **Step 4: Review diff**

Confirm `TERMS.md` remains unchanged, line endings remain CRLF without BOM, and no unrelated file changed.
