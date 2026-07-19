# Notifications Onboarding Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace verbose Android onboarding notification copy with approved concise, OS-generic text.

**Architecture:** Keep `NotificationsStep` structure and behavior unchanged. Protect exact wording with the existing first-run structure test.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4, Gradle

---

### Task 1: Simplify notification onboarding copy

**Files:**
- Modify: `android/app/src/test/java/com/noop/ui/FirstRunFlowStructureTest.kt`
- Modify: `android/app/src/main/java/com/noop/ui/OnboardingScreen.kt:775-794`

- [ ] **Step 1: Write the failing test**

Add a test which reads `OnboardingScreen.kt`, asserts all approved strings, and rejects `When Android asks` plus `Stay in the loop`.

```kotlin
@Test
fun notificationStepUsesConciseGenericCopy() {
    val app = appDir()
    assumeTrue("Android app sources unavailable", app != null)
    val onboarding = File(app, "src/main/java/com/noop/ui/OnboardingScreen.kt").readText()

    listOf(
        "title = \"Notifications\"",
        "subtitle = \"Get connection status and wrist alerts.\"",
        "title = \"Stay connected\"",
        "message = \"A quiet notification keeps NOOP connected. Your data stays current.\"",
        "Checkline(\"Strain nudges and smart alarms appear here.\")",
        "Checkline(\"When asked, allow notifications.\")",
    ).forEach { assertTrue("missing approved notification copy: $it", onboarding.contains(it)) }

    assertFalse(onboarding.contains("When Android asks"))
    assertFalse(onboarding.contains("Stay in the loop"))
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat testFullDebugUnitTest --tests 'com.noop.ui.FirstRunFlowStructureTest.notificationStepUsesConciseGenericCopy' --console=plain --no-daemon
```

Expected: FAIL because old notification copy remains.

- [ ] **Step 3: Apply approved copy**

Keep `NotificationsStep` layout unchanged. Replace only title, subtitle, card title, card message, and two checklines with exact approved strings.

- [ ] **Step 4: Verify GREEN**

Run the focused test again. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify Android build**

Run:

```powershell
.\gradlew.bat testFullDebugUnitTest :app:assembleDemoRelease --console=plain --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Review and preserve workspace**

Run `git diff --check` and review the targeted diff. Do not stage unrelated UI refactors.
