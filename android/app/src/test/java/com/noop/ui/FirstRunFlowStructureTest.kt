package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FirstRunFlowStructureTest {
    private fun appDir(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            userDir,
            File(userDir, "app"),
            File(userDir, "android/app"),
        ).firstOrNull { File(it, "src/main/java/com/noop/ui").isDirectory }
    }

    @Test
    fun firstRunOpensCompactWelcomeWithoutTermsGate() {
        val app = appDir()
        assumeTrue("Android app sources unavailable", app != null)
        val ui = File(app, "src/main/java/com/noop/ui")

        assertFalse("TermsGate.kt must stay deleted", File(ui, "TermsGate.kt").exists())

        val mainActivity = File(ui, "MainActivity.kt").readText()
        assertFalse("root must not render terms gate", mainActivity.contains("TermsGateScreen"))
        assertFalse("terms version preference must stay deleted", mainActivity.contains("KEY_ACCEPTED_TERMS_VERSION"))
        assertFalse("terms timestamp preference must stay deleted", mainActivity.contains("KEY_ACCEPTED_TERMS_AT"))

        val english = File(app, "src/main/res/values/strings.xml").readText()
        val german = File(app, "src/main/res/values-de/strings.xml").readText()
        assertFalse("English terms resources must stay deleted", english.contains("name=\"terms_"))
        assertFalse("German terms resources must stay deleted", german.contains("name=\"terms_"))

        val onboarding = File(ui, "OnboardingScreen.kt").readText()
        assertFalse("stale terms comments must stay deleted", onboarding.contains("Terms clickwrap"))
        assertTrue("first CTA must begin setup", onboarding.contains("Bluetooth(\"Begin setup\")"))
        assertTrue("welcome title changed", onboarding.contains("title = \"Welcome to NOOP\""))
        assertTrue("ownership line changed", onboarding.contains("subtitle = \"Your wearables. Your data.\""))
        assertTrue("wearable card added", onboarding.contains("title = \"Connect your wearable\""))
        assertTrue(
            "wearable card copy changed",
            onboarding.contains("message = \"NOOP uses Bluetooth to find and connect to your nearby devices.\""),
        )
        assertTrue("first Back action must be hidden", onboarding.contains("if (canGoBack) {"))
        assertTrue(
            "first CTA must span the footer",
            onboarding.contains("if (canGoBack) Modifier.weight(1.4f) else Modifier.fillMaxWidth()"),
        )
    }

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
}
