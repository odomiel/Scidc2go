package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
