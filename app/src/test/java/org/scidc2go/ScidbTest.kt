package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Test
import org.scidc2go.chess.Pgn
import org.scidc2go.db.JavaFileSet
import org.scidc2go.db.ScidbDatabase
import org.scidc2go.db.ScidbGameDecoder
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory

/**
 * Partiedatensatz von Hand nach den Regeln von Scidbs sci_encoder.cpp aufgebaut
 * (Standardstellung, Lauf, NAG, Kommentare, Variante mit Vorkommentar, Rochade).
 */
class ScidbTest {
    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8); write(v) }
    private fun ByteArrayOutputStream.u24(v: Int) { write(v shr 16); write(v shr 8); write(v) }
    private fun ByteArrayOutputStream.str(s: String) { write(s.toByteArray()); write(0) }

    private fun record(): ByteArray {
        val moves = byteArrayOf(0x4, 0x4) // keine weiteren Züge
        val head = ByteArrayOutputStream()
        head.u16(518)
        head.u24(2 + 3 + 2 + 2 + moves.size)
        head.u16(2)
        head.write(0x5F); head.write(0xCF)
        head.write(moves)
        return head.toByteArray()
    }

    @Test fun readDatabase() {
        val dir = createTempDirectory().toFile()
        // Index
        val sci = ByteArrayOutputStream()
        sci.write("Scidb.i".toByteArray()); sci.write(0)
        sci.u16(94); sci.u24(1); sci.write(0); sci.write(0); sci.write(0); sci.u16(0); sci.u16(0)
        sci.str("Testdatenbank")
        while (sci.size() < 128) sci.write(0)
        val e = IntArray(14)
        val white = 7; val black = 3; val event = 5
        e[2] = (white shl 8) or (black shr 16)
        e[3] = ((black and 0xFFFF) shl 16) or (2 shl 5) or 17
        e[6] = 10
        e[8] = (event shl 8) or 4
        e[11] = (518 shl 8) or 2
        e[12] = (2650 shl 20) or (6 shl 4)
        e[13] = (2700 shl 20) or ((2024 - 1200) shl 10) or (3 shl 3)
        for (v in e) { sci.u16(v ushr 16); sci.u16(v and 0xFFFF) }
        File(dir, "t.sci").writeBytes(sci.toByteArray())
        // Namen: Spieler, Orte, Turniere, Kommentatoren
        val scn = ByteArrayOutputStream()
        scn.write("Scidb.n".toByteArray()); scn.write(0); scn.u16(94)
        scn.write(0); scn.u24(5)
        scn.write("player\u0000\u0000".toByteArray()); scn.u24(2); scn.u24(1); scn.u24(1); scn.u24(8)
        scn.u24(3); scn.write(8); scn.write("Carlsen,".toByteArray()); scn.write(0)
        scn.u24(7); scn.write(0); scn.write(1); scn.write("K".toByteArray()); scn.write(0)
        scn.write("site\u0000\u0000\u0000\u0000".toByteArray()); scn.u24(1); scn.u24(1); scn.u24(1); scn.u24(1)
        scn.u24(0); scn.write(4); scn.write("Wijk".toByteArray()); scn.u16(0)
        scn.write("event\u0000\u0000\u0000".toByteArray()); scn.u24(1); scn.u24(1); scn.u24(1); scn.u24(6)
        scn.u24(5); scn.write(6); scn.write("Tata S".toByteArray()); scn.u24(0); scn.u16(0)
        scn.write("annota\u0000\u0000".toByteArray()); scn.u24(0); scn.u24(0); scn.u24(0); scn.u24(0)
        File(dir, "t.scn").writeBytes(scn.toByteArray())
        // Partien
        val rec = record()
        val scg = ByteArrayOutputStream()
        scg.write("Scidb.g".toByteArray()); scg.write(0); scg.write(0); scg.write(0)
        scg.u24(rec.size + 3); scg.write(rec)
        File(dir, "t.scg").writeBytes(scg.toByteArray())

        ScidbDatabase(JavaFileSet(dir, "t")).use { db ->
            assertEquals(1, db.gameCount)
            val h = db.header(0)
            assertEquals("K", h.white)
            assertEquals("Carlsen,", h.black)
            assertEquals("Tata S", h.event)
            assertEquals("Wijk", h.site)
            assertEquals("2024.06.17", h.date)
            assertEquals("4.2", h.round)
            assertEquals("1/2-1/2", h.result)
            assertEquals(2700, h.whiteElo)
            val g = db.loadGame(0)
            assertEquals(2, g.plyCount)
        }
    }

    @Test fun decodeRecord() {
        val moves = byteArrayOf(
            0xE7.toByte(), 1, 2,               // 2.Nf3, NAG, Kommentar
            3, 2, 0xA8.toByte(), 4, 4,         // (Kommentar 2.Nc3)
            0x22, 0xDA.toByte(), 0x61, 0x0E,   // 2...Nc6 3.Bc4 Nf6 4.O-O
            4, 2,                              // Ende mit Kommentar
        )
        val text = ByteArrayOutputStream().apply { str("Springer"); str("<xml><:de>Idee</:de></xml>"); str("Ende") }.toByteArray()
        val head = ByteArrayOutputStream()
        head.u16(518 or (1 shl 15) or (1 shl 14))
        val dataOffset = 2 + 3 + 2 + 2 + moves.size
        head.u24(dataOffset)
        head.u16(2)
        head.write(0x5F); head.write(0xCF)    // Lauf: 1.e4 e5
        head.write(moves)
        head.u24(text.size); head.write(text)
        head.write(13); head.str("Italienisch"); head.write(128); head.str("Quelle"); head.str("Test"); head.write(0)
        head.write(byteArrayOf(1, 2, 2, 2))   // NAG-Wert und Kommentarflags
        val game = ScidbGameDecoder(head.toByteArray()).decode()
        val pgn = Pgn.write(game)
        val body = pgn.substringAfter("\n\n").replace(Regex("\\s+"), " ").trim()
        assertEquals("1. e4 e5 2. Nf3 \$1 {Springer} ( {Idee} 2. Nc3 ) 2... Nc6 3. Bc4 Nf6 4. O-O {Ende} *", body)
        assertEquals("Italienisch", game.tags["Opening"])
        assertEquals("Test", game.tags["Quelle"])
    }
}
