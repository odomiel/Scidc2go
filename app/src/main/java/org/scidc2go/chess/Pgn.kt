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

object Pgn {

    /** Liest nur die Tags am Anfang eines Partietextes. */
    fun parseTags(text: CharSequence): LinkedHashMap<String, String> {
        val tags = LinkedHashMap<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n || text[i] != '[') break
            val end = findTagEnd(text, i)
            parseTagLine(text.substring(i + 1, end), tags)
            i = end + 1
        }
        return tags
    }

    private fun findTagEnd(text: CharSequence, start: Int): Int {
        var i = start + 1
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (c == '\\' && i + 1 < text.length) i++
                else if (c == '"') inStr = false
            } else {
                if (c == '"') inStr = true
                else if (c == ']') return i
                else if (c == '\n' && !inStr) return i
            }
            i++
        }
        return text.length - 1
    }

    private fun parseTagLine(line: String, tags: MutableMap<String, String>) {
        val l = line.trim()
        val sp = l.indexOfFirst { it.isWhitespace() || it == '"' }
        if (sp <= 0) return
        val name = l.substring(0, sp)
        val q1 = l.indexOf('"', sp)
        val q2 = l.lastIndexOf('"')
        val value = if (q1 >= 0 && q2 > q1) {
            l.substring(q1 + 1, q2).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            l.substring(sp).trim()
        }
        tags[name] = value
    }

    /** Liest eine vollständige Partie. Fehlerhafte Züge beenden die Zugliste. */
    fun parseGame(text: CharSequence): Game {
        val tags = parseTags(text)
        val fen = tags["FEN"]
        val game = Game(fen?.takeIf { Game.setupPosition(it) != null })
        for ((k, v) in tags) {
            if (k == "FEN" || k == "SetUp") continue
            game.tags[k] = v
        }
        // Zugtext beginnt nach dem letzten Tag
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i < n && text[i] == '[') i = findTagEnd(text, i) + 1 else break
        }
        parseMoves(text, i, game)
        return game
    }

    private fun parseMoves(text: CharSequence, start: Int, game: Game) {
        var i = start
        val n = text.length
        var cur: Node = game.root
        // Stapel: Knoten, an dem die Variante begann (der ersetzte Zug)
        val stack = ArrayList<Node>()
        var pendingPre = ""
        // Nach einem ungültigen Zug wird der Rest der Variante übersprungen
        var broken = false
        var skipDepth = 0

        var atVariationStart = false
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '{' -> {
                    val end = text.indexOf('}', i + 1).let { if (it < 0) n else it }
                    val comment = text.substring(i + 1, end).replace(Regex("\\s+"), " ")
                    if (!broken) {
                        if (atVariationStart) pendingPre = join(pendingPre, comment.trim())
                        else if (cur.isRoot && cur.children.isEmpty()) cur.comment = join(cur.comment, comment.trim())
                        else cur.comment = join(cur.comment, comment.trim())
                    }
                    i = end + 1
                }
                c == ';' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    if (!broken) {
                        val comment = text.substring(i + 1, end).trim()
                        if (atVariationStart) pendingPre = join(pendingPre, comment) else cur.comment = join(cur.comment, comment)
                    }
                    i = end + 1
                }
                c == '(' -> {
                    i++
                    if (broken) { skipDepth++; continue }
                    if (cur.isRoot) { broken = true; skipDepth = 0; stack.add(cur); continue }
                    stack.add(cur)
                    cur = cur.parent!!
                    atVariationStart = true
                    pendingPre = ""
                }
                c == ')' -> {
                    i++
                    if (broken && skipDepth > 0) { skipDepth--; continue }
                    broken = false
                    if (stack.isNotEmpty()) cur = stack.removeAt(stack.size - 1)
                    atVariationStart = false
                    pendingPre = ""
                }
                c == '$' -> {
                    var j = i + 1
                    while (j < n && text[j].isDigit()) j++
                    val nag = text.substring(i + 1, j).toIntOrNull()
                    if (!broken && nag != null && !cur.isRoot && !atVariationStart) cur.nags.add(nag)
                    i = j
                }
                c == '!' || c == '?' -> {
                    var j = i
                    while (j < n && (text[j] == '!' || text[j] == '?')) j++
                    val nag = SYMBOL_NAGS[text.substring(i, j)]
                    if (!broken && nag != null && !cur.isRoot && !atVariationStart) cur.nags.add(nag)
                    i = j
                }
                c == '[' && i > 0 && text[i - 1] == '\n' -> return // nächste Partie
                else -> {
                    var j = i
                    while (j < n && !text[j].isWhitespace() && text[j] !in "{}();$[]") {
                        if ((text[j] == '!' || text[j] == '?') && j > i) break
                        j++
                    }
                    if (j == i) { i++; continue }
                    var token = text.substring(i, j)
                    i = j
                    if (token == "1-0" || token == "0-1" || token == "1/2-1/2" || token == "*") {
                        if (stack.isEmpty()) return else continue
                    }
                    // Zugnummern entfernen ("12.", "12...", "12.e4")
                    val m = MOVE_NUMBER.find(token)
                    if (m != null) token = token.substring(m.value.length)
                    if (token.isEmpty() || broken) continue
                    val textNag = TEXT_NAGS[token]
                    if (textNag != null) {
                        if (!cur.isRoot && !atVariationStart) cur.nags.add(textNag)
                        continue
                    }
                    if (token in IGNORED_TOKENS) continue
                    val mv = cur.pos.parseSan(token)
                    if (mv == null || (!mv.isNull && !cur.pos.isLegal(mv))) {
                        // Ungültiger Zug: Rest dieser Variante ignorieren
                        broken = true
                        skipDepth = 0
                        if (stack.isEmpty()) return
                        continue
                    }
                    val node = cur.addChild(mv)
                    if (atVariationStart && pendingPre.isNotEmpty()) {
                        node.preComment = join(node.preComment, pendingPre)
                    }
                    pendingPre = ""
                    atVariationStart = false
                    cur = node
                }
            }
        }
    }

    private fun join(a: String, b: String) = if (a.isEmpty()) b else if (b.isEmpty()) a else "$a $b"

    private val MOVE_NUMBER = Regex("^\\d+\\.*")
    private val TEXT_NAGS = mapOf(
        "=" to 10, "~" to 13, "+/=" to 14, "=/+" to 15, "+/-" to 16, "-/+" to 17, "+-" to 18, "-+" to 19,
        "N" to 146,
    )
    private val IGNORED_TOKENS = setOf("e.p.", "ep", "+", "#", "..", "...")
    val SYMBOL_NAGS = mapOf("!" to 1, "?" to 2, "!!" to 3, "??" to 4, "!?" to 5, "?!" to 6)
    val NAG_SYMBOLS = mapOf(
        1 to "!", 2 to "?", 3 to "!!", 4 to "??", 5 to "!?", 6 to "?!",
        7 to "□", 10 to "=", 13 to "∞", 14 to "⩲", 15 to "⩱", 16 to "±", 17 to "∓",
        18 to "+−", 19 to "−+", 22 to "⨀", 23 to "⨀", 32 to "⟳", 33 to "⟳",
        36 to "→", 37 to "→", 40 to "↑", 41 to "↑", 132 to "⇆", 133 to "⇆",
        140 to "∆", 142 to "⌓", 145 to "RR", 146 to "N",
    )

    // ------------------------------------------------------------------
    // Schreiben

    fun write(game: Game): String {
        val sb = StringBuilder()
        val tags = LinkedHashMap<String, String>()
        for (t in Game.STR_TAGS) tags[t] = game.tags[t] ?: "?"
        for ((k, v) in game.tags) if (k !in tags && k != "FEN" && k != "SetUp") tags[k] = v
        if (game.hasCustomStart) {
            tags["SetUp"] = "1"
            tags["FEN"] = game.startPosition.toFen()
        }
        for ((k, v) in tags) {
            sb.append('[').append(k).append(" \"")
                .append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]\n")
        }
        sb.append('\n')
        val movetext = StringBuilder()
        if (game.root.comment.isNotEmpty()) movetext.append('{').append(game.root.comment).append("} ")
        writeLine(game.root, movetext, true)
        movetext.append(game.result)
        sb.append(wrap(movetext.toString(), 80)).append("\n\n")
        return sb.toString()
    }

    private fun writeLine(start: Node, out: StringBuilder, forceNumber: Boolean) {
        var node = start
        var needNumber = forceNumber
        while (node.children.isNotEmpty()) {
            val main = node.children[0]
            writeMove(main, out, needNumber)
            needNumber = false
            if (main.comment.isNotEmpty()) needNumber = true
            for (v in node.children.drop(1)) {
                out.append("( ")
                writeMove(v, out, true)
                if (v.comment.isNotEmpty() || v.children.size > 1) {
                    writeLine(v, out, true)
                } else {
                    writeLine(v, out, false)
                }
                out.append(") ")
                needNumber = true
            }
            node = main
        }
    }

    private fun writeMove(n: Node, out: StringBuilder, forceNumber: Boolean) {
        if (n.preComment.isNotEmpty()) out.append('{').append(n.preComment).append("} ")
        val parentPos = n.parent!!.pos
        if (parentPos.toMove == WHITE) {
            out.append(parentPos.fullMove).append(". ")
        } else if (forceNumber || n.preComment.isNotEmpty()) {
            out.append(parentPos.fullMove).append("... ")
        }
        out.append(n.san)
        for (nag in n.nags) out.append(" $").append(nag)
        out.append(' ')
        if (n.comment.isNotEmpty()) out.append('{').append(n.comment).append("} ")
    }

    private fun wrap(s: String, width: Int): String {
        val sb = StringBuilder()
        var lineLen = 0
        for (word in s.split(' ')) {
            if (word.isEmpty()) continue
            if (lineLen > 0 && lineLen + 1 + word.length > width) {
                sb.append('\n'); lineLen = 0
            } else if (lineLen > 0) {
                sb.append(' '); lineLen++
            }
            sb.append(word); lineLen += word.length
        }
        return sb.toString()
    }
}
