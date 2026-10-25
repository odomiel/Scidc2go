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
package org.scidc2go.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.scidc2go.chess.Game
import org.scidc2go.chess.Node
import org.scidc2go.chess.Pgn
import org.scidc2go.chess.WHITE

/**
 * Zugliste als fließender Text mit Varianten in Klammern und Kommentaren.
 * Antippen eines Zuges springt zu dieser Stellung. Mit [showComments] bzw. [showVariations] = false
 * werden Kommentare bzw. Varianten ausgeblendet; die Variante, in der [current] liegt, bleibt sichtbar.
 */
@Composable
fun MoveListView(
    game: Game,
    current: Node?,
    treeVersion: Int,
    onClick: (Node) -> Unit,
    modifier: Modifier = Modifier,
    showComments: Boolean = true,
    showVariations: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = colors.primaryContainer
    val varColor = colors.onSurfaceVariant
    val commentColor = Color(0xFF2E7D32)
    val (text, currentOffset) = remember(game, treeVersion, current, colors, showComments, showVariations) {
        var currentOffset = -1
        // Züge auf dem Weg zum aktuellen Zug: deren Varianten werden auch ausgeblendet gezeigt
        val path = HashSet<Node>()
        generateSequence(current) { it.parent }.forEach { path.add(it) }
        val t: AnnotatedString = buildAnnotatedString {
            fun move(n: Node, depth: Int, forceNumber: Boolean) {
                if (showComments && n.preComment.isNotEmpty()) {
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) { append(n.preComment) }
                    append(' ')
                }
                val pp = n.parent!!.pos
                val number = when {
                    pp.toMove == WHITE -> "${pp.fullMove}."
                    forceNumber -> "${pp.fullMove}…"
                    else -> ""
                }
                val style = if (n === current) {
                    SpanStyle(background = highlight, fontWeight = FontWeight.Bold)
                } else if (depth == 0) {
                    SpanStyle(fontWeight = FontWeight.SemiBold)
                } else {
                    SpanStyle(color = varColor)
                }
                if (n === current) currentOffset = length
                withLink(LinkAnnotation.Clickable("m") { onClick(n) }) {
                    withStyle(style) {
                        append(number)
                        append(n.san)
                        for (nag in n.nags) append(Pgn.NAG_SYMBOLS[nag] ?: " $$nag")
                    }
                }
                append(' ')
                if (showComments && n.comment.isNotEmpty()) {
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) { append(n.comment) }
                    append(' ')
                }
            }

            fun line(start: Node, depth: Int, forceFirst: Boolean) {
                var parent = start
                var force = forceFirst
                while (parent.children.isNotEmpty()) {
                    val main = parent.children[0]
                    move(main, depth, force)
                    force = showComments && main.comment.isNotEmpty()
                    val vars = parent.children.drop(1).filter { showVariations || it in path }
                    if (vars.isNotEmpty()) {
                        if (depth == 0) append("\n")
                        for (v in vars) {
                            withStyle(SpanStyle(color = varColor)) { append("( ") }
                            move(v, depth + 1, true)
                            line(v, depth + 1, showComments && v.comment.isNotEmpty())
                            withStyle(SpanStyle(color = varColor)) { append(") ") }
                            if (depth == 0) append("\n")
                        }
                        force = true
                    }
                    parent = main
                }
            }

            if (showComments && game.root.comment.isNotEmpty()) {
                withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) { append(game.root.comment) }
                append("\n")
            }
            line(game.root, 0, true)
            append(game.result)
        }
        t to currentOffset
    }
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val targetOffset = currentOffset
    Text(
        text,
        modifier.verticalScroll(scroll).padding(8.dp),
        style = MaterialTheme.typography.bodyLarge,
        onTextLayout = { layout = it },
    )
    LaunchedEffect(targetOffset, layout) {
        val l = layout ?: return@LaunchedEffect
        if (targetOffset < 0 || targetOffset > l.layoutInput.text.length) return@LaunchedEffect
        val lineTop = l.getLineTop(l.getLineForOffset(targetOffset)).toInt()
        val viewport = scroll.viewportSize
        if (lineTop < scroll.value || lineTop > scroll.value + viewport - 80) {
            scroll.animateScrollTo(maxOf(0, lineTop - viewport / 3))
        }
    }
}
