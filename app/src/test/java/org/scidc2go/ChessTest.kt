package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scidc2go.chess.Game
import org.scidc2go.chess.Pgn
import org.scidc2go.chess.Position

class ChessTest {
    private fun perft(p: Position, depth: Int): Long {
        if (depth == 0) return 1
        var n = 0L
        for (m in p.legalMoves()) {
            val c = p.copy(); c.play(m)
            n += perft(c, depth - 1)
        }
        return n
    }

    @Test fun perftStart() = assertEquals(197281L, perft(Position.startPos(), 4))
    @Test fun perftKiwipete() = assertEquals(97862L,
        perft(Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")!!, 3))
    @Test fun perftPos3() = assertEquals(43238L,
        perft(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")!!, 4))
    @Test fun perftPos5() = assertEquals(62379L,
        perft(Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")!!, 3))

    /** Chess960-Stellungen mit möglichen Rochaden; Sollwerte von Stockfish 19 (UCI_Chess960, go perft 4). */
    @Test fun perftChess960() {
        val expected = listOf(
            "1rk2r2/pppppppp/8/8/8/8/PPPPPPPP/1RK2R2 w KQkq - 0 1" to 316086L,
            "bqnb1rkr/pp3ppp/3ppn2/2p5/5P2/P2P4/NPP1P1PP/BQ1BNRKR w HFhf - 2 9" to 326672L,
            "r3k1r1/8/8/8/8/8/8/R3K1R1 w AGag - 0 1" to 277828L,
            "1r2kr2/pp3ppp/8/8/8/8/PP3PPP/1R2KR2 w BFbf - 0 1" to 160810L,
            "rk5r/pppppppp/8/8/8/8/PPPPPPPP/RK5R w HAha - 0 1" to 317199L,
            "2r3kr/8/8/8/8/8/8/2R3KR w HChc - 0 1" to 214710L,
            "r5kr/pppppppp/8/8/8/8/PPPPPPPP/R5KR w KQkq - 0 1" to 318326L,
        )
        for ((fen, n) in expected) assertEquals(fen, n, perft(Position.fromFen(fen)!!, 4))
    }

    @Test fun chess960Castling() {
        // König c1, Türme b1 und f1; KQkq meint wie in X-FEN die äußeren Türme
        val p = Position.fromFen("1rk2r2/pppppppp/8/8/8/8/PPPPPPPP/1RK2R2 w KQkq - 0 1")!!
        assertTrue(p.chess960)
        val short = p.legalMoves().single { it.castle == 1 }
        val long = p.legalMoves().single { it.castle == -1 }
        // UCI für Chess960: König schlägt eigenen Turm
        assertEquals("c1f1", short.uci(p))
        assertEquals("c1b1", long.uci(p))
        assertEquals(short, p.parseSan("c1f1"))
        assertEquals("O-O", p.toSan(short))
        val a = p.copy().apply { play(short) }
        assertEquals("1rk2r2/pppppppp/8/8/8/8/PPPPPPPP/1R3RK1", a.toFen().substringBefore(' '))
        val b = p.copy().apply { play(long) }
        assertEquals("1rk2r2/pppppppp/8/8/8/8/PPPPPPPP/2KR1R2", b.toFen().substringBefore(' '))
    }

    @Test fun fenRoundtrip() {
        for (fen in listOf(Position.START_FEN, "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")) {
            assertEquals(fen, Position.fromFen(fen)!!.toFen())
        }
    }

    @Test fun pgnRoundtrip() {
        val text = javaClass.getResource("/sample.pgn")!!.readText()
        val g = Pgn.parseGame(text)
        assertEquals("Müller, Hans", g.white)
        assertEquals(59, g.plyCount)
        assertEquals("Vor der Partie", g.root.comment)
        val again = Pgn.parseGame(Pgn.write(g))
        assertEquals(Pgn.write(g), Pgn.write(again))
        assertNotNull(Game.setupPosition("8/P1k5/8/8/8/8/5Kp1/8 w - - 0 1"))
    }

    /** Engine-Varianten: vorhandene Züge werden weiterverfolgt, verzweigt wird erst bei der ersten Abweichung. */
    @Test fun addLine() {
        val g = Pgn.parseGame("1. e4 e5 2. Nf3 Nc6 *")
        fun line(from: org.scidc2go.chess.Node, vararg san: String): List<org.scidc2go.chess.Move> {
            var p = from.pos
            return san.map { sn -> p.parseSan(sn)!!.also { m -> p = p.copy().apply { play(m) } } }
        }
        val e4 = g.root.children[0]
        // 1… e5 2. Nf3 d6: weicht im vierten Halbzug ab
        val (last, created) = e4.addLine(line(e4, "e5", "Nf3", "d6", "d4"))
        assertTrue(created)
        assertEquals("d4", last.san)
        val nf3 = e4.children[0].children[0]
        assertEquals(listOf("Nc6", "d6"), nf3.children.map { it.san })
        assertEquals(1, e4.children.size)
        // dieselbe Folge noch einmal: nichts Neues
        val (again, created2) = e4.addLine(line(e4, "e5", "Nf3", "d6", "d4"))
        assertTrue(!created2)
        assertTrue(again === last)
        // am Partieende wird die Folge zur Fortsetzung
        val end = g.root.mainLine().last()
        end.addLine(line(end, "Bb5"))
        assertEquals("Bb5", end.children.single().san)
        assertEquals("1. e4 e5 2. Nf3 Nc6 ( 2... d6 3. d4 ) 3. Bb5 *", Pgn.write(g).substringAfter("\n\n").trim())
    }

}
