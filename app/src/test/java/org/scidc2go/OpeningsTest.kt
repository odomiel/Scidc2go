package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.scidc2go.chess.Game
import org.scidc2go.chess.Openings
import org.scidc2go.chess.Pgn
import java.io.File

class OpeningsTest {
    private val table = File("src/main/assets/openings.tsv")
    private val openings by lazy { table.inputStream().use { Openings.load(it) } }

    @Test
    fun allLinesParse() {
        // Jede Zeile muss sich spielen lassen; doppelte Stellungen gibt es in der Tabelle kaum
        val lines = table.readLines().size - 1
        assert(openings.size > lines * 0.98) { "${openings.size} von $lines" }
    }

    @Test
    fun lookup() {
        val game = Pgn.parseGame("1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 6. h3 h6 7. g4 *")
        val o = openings.lookupGame(game)!!
        assertEquals("B90", o.eco)
        assertEquals("Sicilian Defense: Najdorf Variation, Adams Attack", o.name)
        // Stellung nach 2...d6: nur der Name dieser Stellung
        assertEquals("B50", openings.lookup(game.root.mainLine()[3])!!.eco)
        assertNull(openings.lookup(game.root))
        assertNull(openings.lookupGame(Game()))
    }

    @Test
    fun transposition() {
        // Damengambit über 1.c4: am Ende dieselbe Stellung wie nach 1.d4 d5 2.c4 e6
        val game = Pgn.parseGame("1. c4 e6 2. d4 d5 *")
        assertEquals("D30", openings.lookupGame(game)!!.eco)
    }
}
