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

import java.io.InputStream

class Opening(val eco: String, val name: String) {
    override fun toString() = "$eco $name"
}

/**
 * Eröffnungsnamen mit ECO-Code aus der Tabelle von Lichess (`assets/openings.tsv`, Spalten eco, name, pgn).
 * Erkannt wird an der Stellung, nicht an der Zugfolge, damit auch Zugumstellungen ihren Namen bekommen.
 */
class Openings private constructor(private val byPos: Map<String, Opening>, private val maxPly: Int) {
    val size get() = byPos.size

    /** Eröffnung der Stellung selbst oder sonst der letzten benannten Stellung auf dem Weg dorthin. */
    fun lookup(node: Node): Opening? {
        var n: Node? = node
        var ply = node.ply
        while (n != null) {
            if (ply <= maxPly) byPos[key(n.pos)]?.let { return it }
            n = n.parent
            ply--
        }
        return null
    }

    /** Eröffnung der Partie: letzte benannte Stellung der Hauptvariante. */
    fun lookupGame(game: Game): Opening? {
        val line = game.root.mainLine()
        return lookup(line.getOrNull(minOf(maxPly, line.size) - 1) ?: game.root)
    }

    companion object {
        /** Brett, Zugrecht und Rochaderechte; Zugzähler und e.p.-Feld zählen nicht. */
        private fun key(p: Position) = p.toFen().split(' ').take(3).joinToString(" ")

        private val MOVE_NUMBER = Regex("""\d+\.+""")

        /** Liest die Tabelle; Zeilen mit ungültigen Zügen werden übersprungen. Bei doppelten Stellungen gilt die erste. */
        fun load(input: InputStream): Openings {
            val map = HashMap<String, Opening>(8192)
            var maxPly = 0
            input.bufferedReader().useLines { lines ->
                for (line in lines.drop(1)) {
                    val cols = line.split('\t')
                    if (cols.size < 3) continue
                    val pos = Position.startPos()
                    var ply = 0
                    var ok = true
                    for (tok in cols[2].split(' ')) {
                        val san = tok.replace(MOVE_NUMBER, "")
                        if (san.isEmpty()) continue
                        val m = pos.parseSan(san)
                        if (m == null) { ok = false; break }
                        pos.play(m)
                        ply++
                    }
                    if (!ok) continue
                    map.putIfAbsent(key(pos), Opening(cols[0], cols[1]))
                    maxPly = maxOf(maxPly, ply)
                }
            }
            return Openings(map, maxPly)
        }
    }
}
