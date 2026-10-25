package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class AppLogTest {
    @Test fun writeRotateClear() {
        val dir = createTempDirectory().toFile()
        AppLog.init(dir, "Kopf")
        AppLog.clear()
        AppLog.detailed = false
        AppLog.i("Test", "eins")
        AppLog.d("UCI") { "nicht im Protokoll" }
        AppLog.detailed = true
        AppLog.d("UCI") { "> uci" }
        AppLog.e("Fehler", "kaputt", IOException("Grund"))
        val text = AppLog.text()
        assertTrue(text.contains(" I Test: eins\n"))
        assertFalse(text.contains("nicht im Protokoll"))
        assertTrue(text.contains(" D UCI: > uci\n"))
        assertTrue(text.contains(" E Fehler: kaputt\n    java.io.IOException: Grund"))

        // Über 512 KB wird die Datei zu .1; text() liefert beide, die ältere zuerst
        val big = "x".repeat(1000)
        repeat(600) { AppLog.i("Füllen", big) }
        AppLog.i("Test", "zuletzt")
        val log = File(dir, "scidc2go.log")
        assertTrue(File(dir, "scidc2go.log.1").exists())
        assertTrue(log.length() < 512 * 1024)
        val all = AppLog.text()
        assertTrue(all.indexOf("eins") < all.indexOf("zuletzt"))

        AppLog.clear()
        assertEquals("", AppLog.text())
        AppLog.detailed = false
    }
}
