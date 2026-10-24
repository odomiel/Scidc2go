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

/**
 * Knoten im Zugbaum. Die Wurzel hat keinen Zug. children[0] ist die Hauptfortsetzung,
 * weitere Kinder sind Varianten.
 */
class Node(val move: Move?, val parent: Node?, val pos: Position, val san: String) {
    val children = ArrayList<Node>(1)
    /** Kommentar nach dem Zug (bei der Wurzel: Kommentar vor der Partie). */
    var comment: String = ""
    /** Kommentar vor dem Zug (nur am Variantenanfang sinnvoll). */
    var preComment: String = ""
    val nags = ArrayList<Int>(0)

    val isRoot get() = parent == null

    /** Halbzugnummer dieses Knotens (Wurzel = 0 bezogen auf die Startstellung). */
    val ply: Int get() {
        var n = 0
        var p = this
        while (p.parent != null) { n++; p = p.parent }
        return n
    }

    /** Hängt einen Zug an; existiert er schon als Kind, wird dieses Kind geliefert. */
    fun addChild(m: Move, reuseExisting: Boolean = true): Node {
        if (reuseExisting) children.firstOrNull { it.move == m }?.let { return it }
        val newPos = pos.copy()
        val san = pos.toSan(m)
        newPos.play(m)
        val n = Node(m, this, newPos, san)
        children.add(n)
        return n
    }

    /** Ist dieser Knoten der Beginn einer Variante (nicht Hauptfortsetzung)? */
    val isVariationStart get() = parent != null && parent.children.indexOf(this) > 0

    fun mainLine(): List<Node> {
        val res = ArrayList<Node>()
        var n = this
        while (n.children.isNotEmpty()) { n = n.children[0]; res.add(n) }
        return res
    }
}

class Game(startFen: String? = null) {
    /** Tags in PGN-Reihenfolge; die sieben Standardtags sind immer vorhanden. */
    val tags = LinkedHashMap<String, String>()
    val root: Node

    init {
        for (t in STR_TAGS) tags[t] = DEFAULT_TAG_VALUES[t] ?: "?"
        val pos = startFen?.let { setupPosition(it) } ?: Position.startPos()
        root = Node(null, null, pos, "")
        if (!pos.isStandardStart()) {
            tags["SetUp"] = "1"
            tags["FEN"] = pos.toFen()
        }
    }

    val startPosition get() = root.pos
    val hasCustomStart get() = !root.pos.isStandardStart()

    var white: String
        get() = tags["White"] ?: "?"
        set(v) { tags["White"] = v }
    var black: String
        get() = tags["Black"] ?: "?"
        set(v) { tags["Black"] = v }
    var event: String
        get() = tags["Event"] ?: "?"
        set(v) { tags["Event"] = v }
    var site: String
        get() = tags["Site"] ?: "?"
        set(v) { tags["Site"] = v }
    var date: String
        get() = tags["Date"] ?: "????.??.??"
        set(v) { tags["Date"] = v }
    var round: String
        get() = tags["Round"] ?: "?"
        set(v) { tags["Round"] = v }
    var result: String
        get() = tags["Result"] ?: "*"
        set(v) { tags["Result"] = v }

    /** Anzahl der Halbzüge der Hauptvariante. */
    val plyCount get() = root.mainLine().size

    companion object {
        val STR_TAGS = listOf("Event", "Site", "Date", "Round", "White", "Black", "Result")
        private val DEFAULT_TAG_VALUES = mapOf("Date" to "????.??.??", "Result" to "*")

        /**
         * Erzeugt die Startstellung. Entspricht die FEN der normalen Grundstellung,
         * wird Scids Standard-Figurenliste verwendet.
         */
        fun setupPosition(fen: String): Position? {
            val p = Position.fromFen(fen) ?: return null
            return if (p.isStandardStart()) Position.startPos() else p
        }
    }
}
