package dev.omalauncher.herd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdTestModeTest {
    @Test fun onlyAnEmulatorWithThePropSetShowsMadeUpFiles() {
        assertTrue(herdTestMode("ranchu", "google/sdk_gphone64_arm64/emu64a:15", "1\n"))
        assertTrue(herdTestMode("qcom", "generic/sdk/generic:14", "1"))
        // The real Fold never, whatever the prop says.
        assertFalse(herdTestMode("qcom", "samsung/q8qksx/q8q:16/BP2A/F971USQS3AZIL:user/release-keys", "1"))
        // An emulator without the prop shows the real pocket.
        assertFalse(herdTestMode("ranchu", "google/sdk_gphone64_arm64/emu64a:15", ""))
        assertFalse(herdTestMode("ranchu", "google/sdk_gphone64_arm64/emu64a:15", null))
    }

    @Test fun walkLinesAndHapticAreCommandsWalkLinesAreChecked() {
        assertEquals(PetCommand.Haptic, petCommand("""{"cmd":"haptic"}"""))
        assertEquals(PetCommand.Walk("herd select 3"), petCommand("""{"cmd":"walk","line":"herd select 3"}"""))
        assertEquals(PetCommand.Unknown, petCommand("""{"cmd":"walk","line":"x; rm -rf /"}"""))
    }
}
