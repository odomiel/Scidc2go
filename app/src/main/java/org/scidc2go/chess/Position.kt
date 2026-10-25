/*
 * Scidc2go - Schachdatenbanken unterwegs
 * Copyright (C) 2026  Scidc2go-Autoren
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.scidc2go.chess

// Felder: a1 = 0, b1 = 1, ... h8 = 63 (wie in Scid).
// Figuren: Typ (KING..PAWN) | Farbe << 3, leeres Feld = 0.
const val WHITE = 0
const val BLACK = 1

const val KING = 1
const val QUEEN = 2
const val ROOK = 3
const val BISHOP = 4
const val KNIGHT = 5
const val PAWN = 6

const val EMPTY = 0

fun pieceOf(color: Int, type: Int) = type or (color shl 3)
fun typeOf(piece: Int) = piece and 7
fun colorOf(piece: Int) = piece shr 3
fun fileOf(sq: Int) = sq and 7
fun rankOf(sq: Int) = sq shr 3
fun square(file: Int, rank: Int) = rank * 8 + file
fun squareName(sq: Int) = "${'a' + fileOf(sq)}${'1' + rankOf(sq)}"
fun parseSquare(s: String): Int {
    if (s.length != 2) return -1
    val f = s[0] - 'a'
    val r = s[1] - '1'
    return if (f in 0..7 && r in 0..7) square(f, r) else -1
}

/**
 * Ein Zug. Rochaden: [castle] = 1 (kurz) bzw. -1 (lang), [from] ist das Königsfeld,
 * [to] das Zielfeld des Königs (g1/c1 bzw. g8/c8).
 */
data class Move(
    val from: Int,
    val to: Int,
    val promo: Int = EMPTY,
    val castle: Int = 0,
    val isNull: Boolean = false,
) {
    fun uci(pos: Position? = null): String {
        if (isNull) return "0000"
        if (castle != 0 && pos != null && pos.chess960) {
            // Chess960: König schlägt eigenen Turm
            return squareName(from) + squareName(pos.castleRookSq(pos.toMove, castle > 0))
        }
        val p = when (promo) {
            QUEEN -> "q"; ROOK -> "r"; BISHOP -> "b"; KNIGHT -> "n"; else -> ""
        }
        return squareName(from) + squareName(to) + p
    }

    companion object {
        val NULL = Move(0, 0, isNull = true)
    }
}

class Position {
    val board = IntArray(64)
    var toMove = WHITE
    /** Startfeld der Rochadetürme [Farbe*2 + (kurz?1:0)], -1 = kein Rochaderecht. */
    val castleRook = IntArray(4) { -1 }
    var ep = -1
    var halfMoveClock = 0
    var fullMove = 1
    var chess960 = false

    // Figurenlisten exakt wie in Scid (König immer Index 0); werden für die
    // Zugkodierung der Scid-Formate benötigt.
    val list = Array(2) { IntArray(16) }
    val listPos = IntArray(64)
    val count = IntArray(2)

    fun copy(): Position {
        val p = Position()
        board.copyInto(p.board)
        p.toMove = toMove
        castleRook.copyInto(p.castleRook)
        p.ep = ep
        p.halfMoveClock = halfMoveClock
        p.fullMove = fullMove
        p.chess960 = chess960
        list[0].copyInto(p.list[0]); list[1].copyInto(p.list[1])
        listPos.copyInto(p.listPos)
        count.copyInto(p.count)
        return p
    }

    fun kingSq(color: Int) = list[color][0]
    fun castleRookSq(color: Int, kingSide: Boolean) = castleRook[color * 2 + if (kingSide) 1 else 0]

    /** Fügt eine Figur hinzu (Scid: Position::AddPiece). */
    fun addPiece(piece: Int, sq: Int): Boolean {
        val c = colorOf(piece)
        if (count[c] > 15) return false
        if (typeOf(piece) == KING) {
            if (count[c] > 0) {
                val old = list[c][0]
                list[c][count[c]] = old
                listPos[old] = count[c]
            }
            list[c][0] = sq
            listPos[sq] = 0
        } else {
            listPos[sq] = count[c]
            list[c][count[c]] = sq
        }
        count[c]++
        board[sq] = piece
        return true
    }

    // ------------------------------------------------------------------
    // Angriffe

    fun isAttacked(sq: Int, by: Int): Boolean {
        val f = fileOf(sq)
        val r = rankOf(sq)
        // Bauern
        val pr = if (by == WHITE) r - 1 else r + 1
        if (pr in 0..7) {
            val pawn = pieceOf(by, PAWN)
            if (f > 0 && board[square(f - 1, pr)] == pawn) return true
            if (f < 7 && board[square(f + 1, pr)] == pawn) return true
        }
        val knight = pieceOf(by, KNIGHT)
        for (d in KNIGHT_DELTAS) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf in 0..7 && nr in 0..7 && board[square(nf, nr)] == knight) return true
        }
        val king = pieceOf(by, KING)
        for (d in KING_DELTAS) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf in 0..7 && nr in 0..7 && board[square(nf, nr)] == king) return true
        }
        val queen = pieceOf(by, QUEEN)
        val rook = pieceOf(by, ROOK)
        val bishop = pieceOf(by, BISHOP)
        for (d in ROOK_DIRS) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val p = board[square(nf, nr)]
                if (p != EMPTY) {
                    if (p == rook || p == queen) return true
                    break
                }
                nf += d[0]; nr += d[1]
            }
        }
        for (d in BISHOP_DIRS) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val p = board[square(nf, nr)]
                if (p != EMPTY) {
                    if (p == bishop || p == queen) return true
                    break
                }
                nf += d[0]; nr += d[1]
            }
        }
        return false
    }

    fun inCheck(color: Int = toMove) = isAttacked(kingSq(color), 1 - color)

    // ------------------------------------------------------------------
    // Zuggenerierung

    fun pseudoMoves(): List<Move> {
        val moves = ArrayList<Move>(48)
        val c = toMove
        for (i in 0 until count[c]) {
            val from = list[c][i]
            val f = fileOf(from); val r = rankOf(from)
            when (typeOf(board[from])) {
                PAWN -> {
                    val dir = if (c == WHITE) 1 else -1
                    val nr = r + dir
                    val lastRank = if (c == WHITE) 7 else 0
                    if (nr in 0..7) {
                        val to = square(f, nr)
                        if (board[to] == EMPTY) {
                            addPawnMove(moves, from, to, nr == lastRank)
                            val startRank = if (c == WHITE) 1 else 6
                            val to2 = square(f, r + 2 * dir)
                            if (r == startRank && board[to2] == EMPTY) moves.add(Move(from, to2))
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val to2 = square(nf, nr)
                            val t = board[to2]
                            if ((t != EMPTY && colorOf(t) != c) || to2 == ep) {
                                addPawnMove(moves, from, to2, nr == lastRank)
                            }
                        }
                    }
                }
                KNIGHT -> addSteps(moves, from, KNIGHT_DELTAS)
                KING -> addSteps(moves, from, KING_DELTAS)
                BISHOP -> addSlides(moves, from, BISHOP_DIRS)
                ROOK -> addSlides(moves, from, ROOK_DIRS)
                QUEEN -> { addSlides(moves, from, ROOK_DIRS); addSlides(moves, from, BISHOP_DIRS) }
            }
        }
        for (kingSide in booleanArrayOf(true, false)) {
            if (canCastle(kingSide)) {
                val k = kingSq(c)
                val kTo = square(if (kingSide) 6 else 2, if (c == WHITE) 0 else 7)
                moves.add(Move(k, kTo, castle = if (kingSide) 1 else -1))
            }
        }
        return moves
    }

    private fun addPawnMove(moves: MutableList<Move>, from: Int, to: Int, promo: Boolean) {
        if (promo) {
            for (p in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) moves.add(Move(from, to, p))
        } else {
            moves.add(Move(from, to))
        }
    }

    private fun addSteps(moves: MutableList<Move>, from: Int, deltas: Array<IntArray>) {
        val f = fileOf(from); val r = rankOf(from)
        for (d in deltas) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf !in 0..7 || nr !in 0..7) continue
            val to = square(nf, nr)
            val t = board[to]
            if (t == EMPTY || colorOf(t) != toMove) moves.add(Move(from, to))
        }
    }

    private fun addSlides(moves: MutableList<Move>, from: Int, dirs: Array<IntArray>) {
        val f = fileOf(from); val r = rankOf(from)
        for (d in dirs) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val to = square(nf, nr)
                val t = board[to]
                if (t == EMPTY) {
                    moves.add(Move(from, to))
                } else {
                    if (colorOf(t) != toMove) moves.add(Move(from, to))
                    break
                }
                nf += d[0]; nr += d[1]
            }
        }
    }

    /** Prüft eine Rochade inkl. Schach- und Durchgangsfeldern (auch Chess960). */
    fun canCastle(kingSide: Boolean): Boolean {
        val c = toMove
        val rookFrom = castleRookSq(c, kingSide)
        if (rookFrom < 0) return false
        val k = kingSq(c)
        if (board[rookFrom] != pieceOf(c, ROOK)) return false
        val rank = if (c == WHITE) 0 else 7
        if (rankOf(k) != rank) return false
        val kTo = square(if (kingSide) 6 else 2, rank)
        val rTo = square(if (kingSide) 5 else 3, rank)
        // Alle Felder zwischen König/Turm und ihren Zielen müssen frei sein
        val lo = minOf(k, kTo, rookFrom, rTo)
        val hi = maxOf(k, kTo, rookFrom, rTo)
        for (sq in lo..hi) {
            if (sq != k && sq != rookFrom && board[sq] != EMPTY) return false
        }
        val enemy = 1 - c
        val step = if (kTo >= k) 1 else -1
        var sq = k
        while (true) {
            if (isAttacked(sq, enemy)) return false
            if (sq == kTo) break
            sq += step
        }
        return true
    }

    fun isLegal(m: Move): Boolean {
        if (m.isNull) return !inCheck()
        if (m.castle != 0) return true // bereits in canCastle geprüft
        val p = copy()
        p.play(m)
        return !p.inCheck(toMove)
    }

    fun legalMoves(): List<Move> = pseudoMoves().filter { isLegal(it) }

    fun isCheckmate() = inCheck() && legalMoves().isEmpty()
    fun isStalemate() = !inCheck() && legalMoves().isEmpty()

    // ------------------------------------------------------------------
    // Zug ausführen (Scid: Position::DoSimpleMove, inkl. Pflege der Figurenlisten)

    fun play(m: Move) {
        val c = toMove
        val enemy = 1 - c
        halfMoveClock++
        if (c == BLACK) fullMove++
        ep = -1
        toMove = enemy
        if (m.isNull) return

        val from = m.from
        val piece = board[from]
        val ptype = typeOf(piece)
        val pieceNum = listPos[from]

        if (ptype == KING) {
            // Turmfeld vor dem Löschen der Rochaderechte bestimmen (bei Chess960 nicht a/h)
            val rank = if (c == WHITE) 0 else 7
            val rookFrom = if (m.castle != 0) rookFromForCastle(c, m.castle > 0, rank) else -1
            castleRook[c * 2] = -1
            castleRook[c * 2 + 1] = -1
            if (m.castle != 0) {
                val kingSide = m.castle > 0
                val kTo = square(if (kingSide) 6 else 2, rank)
                val rTo = square(if (kingSide) 5 else 3, rank)
                val rookIdx = listPos[rookFrom]
                board[rookFrom] = EMPTY
                board[from] = EMPTY
                list[c][0] = kTo; listPos[kTo] = 0; board[kTo] = pieceOf(c, KING)
                list[c][rookIdx] = rTo; listPos[rTo] = rookIdx; board[rTo] = pieceOf(c, ROOK)
                return
            }
        }

        val to = m.to
        var capSq = to
        var captured = board[to]
        if (ptype == PAWN && captured == EMPTY && fileOf(from) != fileOf(to)) {
            // en passant
            capSq = if (c == WHITE) to - 8 else to + 8
            captured = board[capSq]
        }
        if (captured != EMPTY) {
            val capNum = listPos[capSq]
            count[enemy]--
            val last = list[enemy][count[enemy]]
            listPos[last] = capNum
            list[enemy][capNum] = last
            board[capSq] = EMPTY
            halfMoveClock = 0
        }

        board[from] = EMPTY
        list[c][pieceNum] = to
        listPos[to] = pieceNum
        board[to] = if (m.promo != EMPTY) pieceOf(c, m.promo) else piece

        if (castleRook.any { it >= 0 }) {
            for (i in 0..3) {
                if (castleRook[i] == from || castleRook[i] == to) {
                    // Turm gezogen oder geschlagen (nur wenn die Farbe passt)
                    val col = i / 2
                    if ((castleRook[i] == from && col == c) || (castleRook[i] == to && col == enemy)) {
                        castleRook[i] = -1
                    }
                }
            }
        }

        if (ptype == PAWN) {
            halfMoveClock = 0
            val fr = rankOf(from); val tr = rankOf(to)
            if (fr == 1 && tr == 3) setEpIfCapturable(from + 8, to, BLACK)
            if (fr == 6 && tr == 4) setEpIfCapturable(from - 8, to, WHITE)
        }
    }

    private fun rookFromForCastle(c: Int, kingSide: Boolean, rank: Int): Int {
        val sq = castleRookSq(c, kingSide)
        if (sq >= 0) return sq
        return square(if (kingSide) 7 else 0, rank)
    }

    private fun setEpIfCapturable(epSq: Int, to: Int, capturer: Int) {
        val pawn = pieceOf(capturer, PAWN)
        val f = fileOf(to)
        if ((f > 0 && board[to - 1] == pawn) || (f < 7 && board[to + 1] == pawn)) ep = epSq
    }

    // ------------------------------------------------------------------
    // SAN

    fun toSan(m: Move): String {
        if (m.isNull) return "--"
        val sb = StringBuilder()
        if (m.castle != 0) {
            sb.append(if (m.castle > 0) "O-O" else "O-O-O")
        } else {
            val piece = board[m.from]
            val type = typeOf(piece)
            val capture = board[m.to] != EMPTY || (type == PAWN && fileOf(m.from) != fileOf(m.to))
            if (type == PAWN) {
                if (capture) sb.append('a' + fileOf(m.from)).append('x')
                sb.append(squareName(m.to))
                if (m.promo != EMPTY) sb.append('=').append(PIECE_LETTERS[m.promo])
            } else {
                sb.append(PIECE_LETTERS[type])
                val others = legalMoves().filter {
                    it.to == m.to && it.from != m.from && it.castle == 0 && board[it.from] == piece
                }
                if (others.isNotEmpty()) {
                    val sameFile = others.any { fileOf(it.from) == fileOf(m.from) }
                    val sameRank = others.any { rankOf(it.from) == rankOf(m.from) }
                    if (!sameFile) sb.append('a' + fileOf(m.from))
                    else if (!sameRank) sb.append('1' + rankOf(m.from))
                    else sb.append(squareName(m.from))
                }
                if (capture) sb.append('x')
                sb.append(squareName(m.to))
            }
        }
        val after = copy()
        after.play(m)
        if (after.inCheck()) sb.append(if (after.legalMoves().isEmpty()) '#' else '+')
        return sb.toString()
    }

    /** Liest einen Zug in SAN (tolerant: auch "e2e4", "0-0", "e8Q", "Pe4"). */
    fun parseSan(input: String): Move? {
        var s = input.trim().trimEnd('+', '#', '!', '?').replace("0", "O")
        if (s == "--" || s == "Z0" || s == "OOOO") return Move.NULL
        if (s.isEmpty()) return null
        val legal = legalMoves()
        if (s == "O-O" || s == "OO") return legal.firstOrNull { it.castle == 1 }
        if (s == "O-O-O" || s == "OOO") return legal.firstOrNull { it.castle == -1 }

        // Langschrift / UCI
        if (s.length in 4..5 && s[0] in 'a'..'h' && s[1] in '1'..'8' && s[2] in 'a'..'h' && s[3] in '1'..'8') {
            val from = parseSquare(s.substring(0, 2))
            val to = parseSquare(s.substring(2, 4))
            val promo = if (s.length == 5) PIECE_LETTERS.indexOf(s[4].uppercaseChar()) else EMPTY
            legal.firstOrNull { it.from == from && it.to == to && it.promo == maxOf(promo, 0) && it.castle == 0 }
                ?.let { return it }
            legal.firstOrNull { it.castle != 0 && it.from == from && (it.to == to || castleRook.contains(to)) }
                ?.let { return it }
        }

        s = s.replace("=", "").replace("x", "").replace(":", "").replace("-", "")
        var promo = EMPTY
        val lastCh = s.last()
        if (s.length >= 3 && lastCh.uppercaseChar() in "QRBN" && s[s.length - 2] in '1'..'8') {
            promo = PIECE_LETTERS.indexOf(lastCh.uppercaseChar())
            s = s.dropLast(1)
        }
        var type = PAWN
        if (s.isNotEmpty() && s[0] in "KQRBN") {
            type = PIECE_LETTERS.indexOf(s[0]); s = s.substring(1)
        } else if (s.isNotEmpty() && s[0] == 'P') {
            s = s.substring(1)
        }
        if (s.length < 2) return null
        val to = parseSquare(s.takeLast(2))
        if (to < 0) return null
        val disamb = s.dropLast(2)
        var dFile = -1; var dRank = -1
        for (ch in disamb) {
            if (ch in 'a'..'h') dFile = ch - 'a'
            else if (ch in '1'..'8') dRank = ch - '1'
        }
        val candidates = legal.filter {
            it.castle == 0 && it.to == to && typeOf(board[it.from]) == type && it.promo == promo &&
                (dFile < 0 || fileOf(it.from) == dFile) && (dRank < 0 || rankOf(it.from) == dRank)
        }
        if (candidates.size == 1) return candidates[0]
        // Bauernzug ohne Umwandlungsangabe: Dame annehmen
        if (candidates.isEmpty() && type == PAWN && promo == EMPTY) {
            return legal.firstOrNull {
                it.to == to && typeOf(board[it.from]) == PAWN && it.promo == QUEEN &&
                    (dFile < 0 || fileOf(it.from) == dFile)
            }
        }
        return candidates.firstOrNull()
    }

    // ------------------------------------------------------------------
    // FEN

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[square(f, r)]
                if (p == EMPTY) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                val ch = PIECE_LETTERS[typeOf(p)].let { if (it == ' ') 'P' else it }
                sb.append(if (colorOf(p) == WHITE) ch else ch.lowercaseChar())
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (toMove == WHITE) " w " else " b ")
        val cs = StringBuilder()
        for ((idx, color) in listOf(1 to WHITE, 0 to WHITE, 3 to BLACK, 2 to BLACK)) {
            val sq = castleRook[idx]
            if (sq < 0) continue
            val kingSide = idx % 2 == 1
            val ch = if (!chess960 && fileOf(sq) == (if (kingSide) 7 else 0)) {
                if (kingSide) 'K' else 'Q'
            } else {
                'A' + fileOf(sq)
            }
            cs.append(if (color == WHITE) ch else ch.lowercaseChar())
        }
        sb.append(if (cs.isEmpty()) "-" else cs)
        sb.append(' ').append(if (ep >= 0) squareName(ep) else "-")
        sb.append(' ').append(halfMoveClock).append(' ').append(fullMove)
        return sb.toString()
    }

    fun isStandardStart(): Boolean = toFen() == START_FEN

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val PIECE_LETTERS = charArrayOf(' ', 'K', 'Q', 'R', 'B', 'N', ' ')
        val KNIGHT_DELTAS = arrayOf(
            intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
        )
        val KING_DELTAS = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        )
        val ROOK_DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        val BISHOP_DIRS = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))

        /** Grundstellung mit Scids Figurenlisten-Reihenfolge (Position::StdStart). */
        fun startPos(): Position {
            val p = Position()
            val order = intArrayOf(KING, ROOK, KNIGHT, BISHOP, QUEEN, BISHOP, KNIGHT, ROOK)
            val files = intArrayOf(4, 0, 1, 2, 3, 5, 6, 7)
            for (c in 0..1) {
                val back = if (c == WHITE) 0 else 7
                val pawnRank = if (c == WHITE) 1 else 6
                for (i in 0..7) {
                    val sq = square(files[i], back)
                    p.board[sq] = pieceOf(c, order[i]); p.list[c][i] = sq; p.listPos[sq] = i
                }
                for (i in 0..7) {
                    val sq = square(i, pawnRank)
                    p.board[sq] = pieceOf(c, PAWN); p.list[c][i + 8] = sq; p.listPos[sq] = i + 8
                }
                p.count[c] = 16
            }
            p.castleRook[0] = 0; p.castleRook[1] = 7; p.castleRook[2] = 56; p.castleRook[3] = 63
            return p
        }

        /** Liest eine FEN; Figuren werden wie in Scid von a8 nach h1 eingefügt. */
        fun fromFen(fen: String): Position? {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) return null
            val p = Position()
            var rank = 7; var file = 0
            for (ch in parts[0]) {
                when {
                    ch == '/' -> { if (file != 0 && file != 8) return null; if (file == 8) { rank--; file = 0 } }
                    ch in '1'..'8' -> file += ch - '0'
                    else -> {
                        if (file >= 8) { rank--; file = 0 }
                        val t = PIECE_LETTERS.indexOf(ch.uppercaseChar()).let { if (ch.uppercaseChar() == 'P') PAWN else it }
                        if (t <= 0 || rank < 0) return null
                        val color = if (ch.isUpperCase()) WHITE else BLACK
                        if (!p.addPiece(pieceOf(color, t), square(file, rank))) return null
                        file++
                    }
                }
            }
            if ((0..1).any { c -> p.count[c] == 0 || typeOf(p.board[p.list[c][0]]) != KING }) return null
            p.toMove = if (parts.getOrNull(1) == "b") BLACK else WHITE
            val castling = parts.getOrNull(2)
            val wk = p.kingSq(WHITE); val bk = p.kingSq(BLACK)
            if (castling == null) {
                if (wk == 4) { if (p.board[0] == pieceOf(WHITE, ROOK)) p.castleRook[0] = 0; if (p.board[7] == pieceOf(WHITE, ROOK)) p.castleRook[1] = 7 }
                if (bk == 60) { if (p.board[56] == pieceOf(BLACK, ROOK)) p.castleRook[2] = 56; if (p.board[63] == pieceOf(BLACK, ROOK)) p.castleRook[3] = 63 }
            } else if (castling != "-") {
                for (ch in castling) {
                    val color = if (ch.isUpperCase()) WHITE else BLACK
                    val rank = if (color == WHITE) 0 else 7
                    val king = p.kingSq(color)
                    val rook = pieceOf(color, ROOK)
                    val rsq: Int = when (ch.uppercaseChar()) {
                        'K' -> (7 downTo 0).map { square(it, rank) }.firstOrNull { p.board[it] == rook && it > king } ?: -1
                        'Q' -> (0..7).map { square(it, rank) }.firstOrNull { p.board[it] == rook && it < king } ?: -1
                        in 'A'..'H' -> square(ch.uppercaseChar() - 'A', rank)
                        else -> -1
                    }
                    if (rsq < 0 || rankOf(king) != rank) continue
                    val kingSide = rsq > king
                    p.castleRook[color * 2 + if (kingSide) 1 else 0] = rsq
                    if (ch.uppercaseChar() in 'A'..'H' || fileOf(king) != 4 || fileOf(rsq) !in intArrayOf(0, 7)) {
                        p.chess960 = true
                    }
                }
            }
            parts.getOrNull(3)?.let { if (it != "-") p.ep = parseSquare(it) }
            parts.getOrNull(4)?.toIntOrNull()?.let { p.halfMoveClock = it }
            parts.getOrNull(5)?.toIntOrNull()?.let { p.fullMove = maxOf(1, it) }
            if (p.inCheck(1 - p.toMove)) return null
            return p
        }
    }
}
