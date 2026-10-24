package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Test
import org.scidc2go.chess.Game
import org.scidc2go.chess.Move
import org.scidc2go.chess.Node
import org.scidc2go.chess.Pgn
import org.scidc2go.chess.Position
import org.scidc2go.db.ChessDatabase
import org.scidc2go.db.JavaFileSet
import org.scidc2go.db.PgnDatabase
import org.scidc2go.db.Scid4Database
import org.scidc2go.db.Scid5Database
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class ScidTest {
    private val resDir = File(javaClass.getResource("/s5.si5")!!.toURI()).parentFile

    /** Zugtext ohne Zugnummern und Formatierung, zum Vergleich mit Scids Export. */
    private fun movetext(pgn: String): String {
        val body = pgn.lines().filter { !it.startsWith("[") }.joinToString(" ")
        return body.replace(Regex("\\d+\\.(\\.\\.)?"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun games(db: ChessDatabase) = (0 until db.gameCount).map { db.loadGame(it) }

    private fun scidReference(): List<Game> {
        val ref = PgnDatabase("ref", RandomAccessFile(File(resDir, "s5out.pgn"), "r").channel)
        return games(ref)
    }

    private fun compareWithReference(db: ChessDatabase) {
        val ref = scidReference()
        assertEquals(ref.size, db.gameCount)
        for (i in ref.indices) {
            val mine = db.loadGame(i)
            assertEquals(movetext(Pgn.write(ref[i])), movetext(Pgn.write(mine)))
            for (t in listOf("White", "Black", "Event", "Site", "Date", "Round", "Result", "WhiteElo", "ECO", "Annotator", "TimeControl")) {
                assertEquals(t, ref[i].tags[t], mine.tags[t])
            }
            assertEquals(db.header(i).white, ref[i].white)
        }
    }

    @Test fun readScid4() = Scid4Database(JavaFileSet(resDir, "s4")).use { compareWithReference(it) }

    @Test fun readScid5() = Scid5Database.open(JavaFileSet(resDir, "s5"), false).use { compareWithReference(it) }

    /** Erzeugt eine Zufallspartie mit Varianten, Kommentaren und NAGs. */
    private fun randomGame(rnd: Random, fen: String? = null): Game {
        val g = Game(fen)
        g.white = "Weiß ${rnd.nextInt(50)}"; g.black = "Schwarz ${rnd.nextInt(50)}"
        g.event = "Zufall"; g.date = "2026.10.${rnd.nextInt(1, 29).toString().padStart(2, '0')}"
        g.tags["WhiteElo"] = (1500 + rnd.nextInt(1000)).toString()
        g.tags["Annotator"] = "Kotlin"
        fun grow(start: Node, len: Int, depth: Int) {
            var n = start
            for (i in 0 until len) {
                val moves = n.pos.legalMoves()
                if (moves.isEmpty()) break
                // Umwandlungen und Rochaden bevorzugen, damit sie sicher vorkommen
                val special = moves.filter { it.promo != 0 || it.castle != 0 }
                val m = if (special.isNotEmpty() && rnd.nextInt(3) == 0) special.random(rnd) else moves.random(rnd)
                val parent = n
                n = n.addChild(m)
                if (rnd.nextInt(10) == 0) n.comment = "Kommentär $i"
                if (rnd.nextInt(12) == 0) n.nags.add(rnd.nextInt(1, 20))
                if (depth < 3 && rnd.nextInt(15) == 0) {
                    val alt = parent.pos.legalMoves().filter { it != m }
                    if (alt.isNotEmpty()) {
                        val v = parent.addChild(alt.random(rnd))
                        if (rnd.nextInt(3) == 0) v.preComment = "Idee"
                        grow(v, rnd.nextInt(1, 8), depth + 1)
                    }
                }
            }
        }
        if (rnd.nextInt(8) == 0) g.root.comment = "Vorab"
        grow(g.root, rnd.nextInt(0, 160), 0)
        if (rnd.nextInt(30) == 0) {
            // Nullzug
            val last = g.root.mainLine().lastOrNull() ?: g.root
            if (!last.pos.inCheck()) last.addChild(Move.NULL)
        }
        return g
    }

    @Test fun writeScid5Roundtrip() {
        val outDir = System.getenv("SCIDC2GO_OUT")?.let { File(it) } ?: createTempDir()
        outDir.mkdirs()
        listOf("si5", "sg5", "sn5").forEach { File(outDir, "rt.$it").delete() }
        val rnd = Random(42)
        val list = ArrayList<Game>()
        val db = Scid5Database.create(JavaFileSet(outDir, "rt"))
        db.use {
            scidReference().forEach { g -> list.add(g); it.addGame(g) }
            repeat(300) { list.add(randomGame(rnd).also { g -> db.addGame(g) }) }
            list.add(randomGame(rnd, "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1").also { db.addGame(it) })
            list.add(randomGame(rnd, "8/P1k5/8/8/8/8/5Kp1/8 w - - 0 1").also { db.addGame(it) })
            // Ersetzen
            val repl = randomGame(rnd)
            db.replaceGame(5, repl); list[5] = repl
            db.setDeleted(7, true)
        }
        Scid5Database.open(JavaFileSet(outDir, "rt"), false).use { re ->
            assertEquals(list.size, re.gameCount)
            for (i in list.indices) {
                assertEquals("Partie $i", Pgn.write(list[i]), Pgn.write(re.loadGame(i)))
            }
            assertEquals(true, re.header(7).deleted)
        }
        // Referenz-PGN für den Vergleich mit Scid
        File(outDir, "rt-expected.pgn").writeText(list.joinToString("") { Pgn.write(it) })
    }
}
