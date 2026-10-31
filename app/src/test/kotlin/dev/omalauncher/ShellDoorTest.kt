package dev.omalauncher

import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDoorTest {
    @Test fun onlyTheShellUserInAWorkingBuild() {
        assertTrue(shellDoorAnswers(2000, working = true))
        assertFalse("not in the public build", shellDoorAnswers(2000, working = false))
        assertFalse("not root", shellDoorAnswers(0, working = true))
        assertFalse("not another app", shellDoorAnswers(10123, working = true))
        assertFalse("not the app itself, so not its Linux", shellDoorAnswers(10231, working = true))
    }

    @Test fun noPathLeadsOutOfItsFolder() {
        assertTrue(shellDoorName("step-0a1b2c3d.sh"))
        assertTrue(shellDoorName("0123456789abcdef.out"))
        for (bad in listOf(null, "", ".", "..", "../x", "a/b", "/etc/passwd", ".hidden", "a b", "a\nb", "x".repeat(65))) assertFalse("$bad", shellDoorName(bad))
    }

    @Test fun itRunsOnlyWhatOmaMacNames() {
        assertTrue(shellDoorRunId("0123456789abcdef"))
        for (bad in listOf(null, "", "0123456789abcde", "0123456789ABCDEF", "x; rm -rf /", "0123456789abcdef.sh", "0123456789abcdef0")) assertFalse("$bad", shellDoorRunId(bad))
    }

    /** No exported way in but this: the old door took a guest command from any app's intent, behind a key. */
    @Test fun theOldDoorIsGone() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("DebugExecReceiver"))
        assertTrue(manifest.contains("android:enabled=\"\${shellDoor}\""))
        val main = moduleFile("src/main/kotlin/dev/omalauncher/MainActivity.kt").readText()
        assertFalse(main.contains("getStringExtra(\"exec\")"))
    }

    /** The public build has no door, and the working build isn't debuggable unless asked on the command line. */
    @Test fun theBuildFile() {
        val build = moduleFile("build.gradle.kts").readText()
        assertTrue(build.contains("manifestPlaceholders[\"shellDoor\"] = \"false\""))
        assertTrue(build.contains("isDebuggable = providers.gradleProperty(\"somaDebuggable\").isPresent"))
    }
}
