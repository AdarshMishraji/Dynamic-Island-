package com.akira.island.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    @After fun reset() { SettingsRepo.reset(); ExtrasRepo.update { ExtraSettings() } }

    @Test fun aBackupRestoresSettingsAndExtras() {
        SettingsRepo.update { copy(width = 120, privacyDots = true, swipeLeft = SwipeAction.SHADE, exemptApps = setOf("a.b")) }
        ExtrasRepo.update { copy(weatherCity = "Oslo", apps = listOf("x.y")) }
        val text = SettingsBackup.export()
        SettingsRepo.reset(); ExtrasRepo.update { ExtraSettings() }
        assertTrue(SettingsBackup.import(text))
        assertEquals(120, SettingsRepo.current.width)
        assertTrue(SettingsRepo.current.privacyDots)
        assertEquals(SwipeAction.SHADE, SettingsRepo.current.swipeLeft)
        assertEquals(setOf("a.b"), SettingsRepo.current.exemptApps)
        assertEquals("Oslo", ExtrasRepo.state.value.weatherCity)
        assertEquals(listOf("x.y"), ExtrasRepo.state.value.apps)
    }

    @Test fun foreignFilesAreRejected() {
        assertFalse(SettingsBackup.import("not json"))
        assertFalse(SettingsBackup.import("{}"))
        assertFalse(SettingsBackup.import("""{"format":"something-else","version":1,"settings":{}}"""))
        assertFalse(SettingsBackup.import("""{"format":"dynamic-island-settings","version":99,"settings":{}}"""))
    }

    @Test fun aDamagedFileChangesNothing() {
        SettingsRepo.update { copy(width = 99) }
        ExtrasRepo.update { copy(weatherCity = "Keep") }
        assertFalse(SettingsBackup.import("""{"format":"dynamic-island-settings","version":1,"settings":{"width":"x"},"extras":{}}"""))
        // good settings but damaged extras: neither is applied
        assertFalse(SettingsBackup.import("""{"format":"dynamic-island-settings","version":1,"settings":{"width":150},"extras":{"latitude":"x"}}"""))
        assertEquals(99, SettingsRepo.current.width)
        assertEquals("Keep", ExtrasRepo.state.value.weatherCity)
    }

    @Test fun thingsThatBelongToThisPhoneStay() {
        SettingsRepo.update { copy(skippedVersion = "9.9", msgChannels = mapOf("p" to "c")) }
        val text = SettingsBackup.export().replace("\"skippedVersion\": \"9.9\"", "\"skippedVersion\": \"1.0\"")
        assertTrue(SettingsBackup.import(text))
        assertEquals("9.9", SettingsRepo.current.skippedVersion)
        assertEquals(mapOf("p" to "c"), SettingsRepo.current.msgChannels)
    }

    @Test fun oversizedInputIsRefusedWhileReading() {
        val big = ByteArray(SettingsBackup.MAX_BYTES + 10) { 'a'.code.toByte() }
        assertNull(SettingsBackup.readLimited(big.inputStream()))
        assertEquals("abc", SettingsBackup.readLimited("abc".byteInputStream()))
    }
}
