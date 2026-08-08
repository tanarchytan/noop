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
        // The copy moved to string resources; the wording is still pinned, now where it lives.
        val devicesRes = File(app, "src/main/res/values/strings_devices.xml").readText()
        assertTrue(
            "first CTA must begin setup",
            onboarding.contains("Bluetooth(R.string.onboarding_cta_begin)") &&
                devicesRes.contains("<string name=\"onboarding_cta_begin\">Begin setup</string>"),
        )
        mapOf(
            "onboarding_welcome_title" to "Welcome to NOOP",
            "onboarding_welcome_subtitle" to "Your wearables. Your data.",
            "onboarding_bluetooth_card_title" to "Connect your wearable",
            "onboarding_bluetooth_card_body"
                to "NOOP uses Bluetooth to find and connect to your nearby devices.",
        ).forEach { (key, copy) ->
            assertTrue(
                "welcome copy changed: $key = \"$copy\"",
                devicesRes.contains("<string name=\"$key\">$copy</string>"),
            )
            assertTrue("welcome step must render $key", onboarding.contains("R.string.$key"))
        }
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
        // The wording is pinned in the resource file it moved to, and the screen is pinned to the keys,
        // so neither the copy nor the wiring can drift without this failing.
        val onboarding = File(app, "src/main/java/com/noop/ui/OnboardingScreen.kt").readText()
        val res = File(app, "src/main/res/values/strings_devices.xml").readText()
        mapOf(
            "onboarding_notifications_title" to "Notifications",
            "onboarding_notifications_subtitle" to "Get connection status and wrist alerts.",
            "onboarding_notifications_card_title" to "Stay connected",
            "onboarding_notifications_card_body"
                to "A quiet notification keeps NOOP connected. Your data stays current.",
            "onboarding_notifications_check_alerts" to "Strain nudges and smart alarms appear here.",
            "onboarding_notifications_check_allow" to "When asked, allow notifications.",
        ).forEach { (key, copy) ->
            assertTrue(
                "missing approved notification copy: $key = \"$copy\"",
                res.contains("<string name=\"$key\">$copy</string>"),
            )
            assertTrue("notification step must render $key", onboarding.contains("R.string.$key"))
        }

        assertFalse(onboarding.contains("When Android asks"))
        assertFalse(onboarding.contains("Stay in the loop"))
    }
}
