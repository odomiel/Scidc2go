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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.scidc2go.R
import org.scidc2go.chess.BISHOP
import org.scidc2go.chess.EMPTY
import org.scidc2go.chess.KNIGHT
import org.scidc2go.chess.Move
import org.scidc2go.chess.Position
import org.scidc2go.chess.QUEEN
import org.scidc2go.chess.ROOK
import org.scidc2go.chess.WHITE
import org.scidc2go.chess.colorOf
import org.scidc2go.chess.pieceOf
import org.scidc2go.chess.square
import org.scidc2go.chess.typeOf
import kotlin.math.roundToInt

private val LIGHT = Color(0xFFF0D9B5)
private val DARK = Color(0xFFB58863)
private val HIGHLIGHT = Color(0x99F6F669)
private val SELECT = Color(0xAA7FC97F)

fun pieceRes(piece: Int): Int {
    val white = colorOf(piece) == WHITE
    return when (typeOf(piece)) {
        1 -> if (white) R.drawable.piece_wk else R.drawable.piece_bk
        2 -> if (white) R.drawable.piece_wq else R.drawable.piece_bq
        3 -> if (white) R.drawable.piece_wr else R.drawable.piece_br
        4 -> if (white) R.drawable.piece_wb else R.drawable.piece_bb
        5 -> if (white) R.drawable.piece_wn else R.drawable.piece_bn
        else -> if (white) R.drawable.piece_wp else R.drawable.piece_bp
    }
}

/**
 * Schachbrett. Züge werden durch Ziehen einer Figur auf das Zielfeld eingegeben
 * oder durch Antippen von Start- und Zielfeld.
 * [candidates] liefert die passenden legalen Züge (mehrere bei Umwandlung).
 */
@Composable
fun BoardView(
    pos: Position,
    lastMove: Move?,
    flipped: Boolean,
    legalTargets: (Int) -> Set<Int>,
    candidates: (Int, Int) -> List<Move>,
    onMove: (Move) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(pos) { mutableIntStateOf(-1) }
    var promoChoices by remember { mutableStateOf<List<Move>>(emptyList()) }
    // gerade gezogene Figur: Startfeld und Fingerposition
    var dragFrom by remember(pos) { mutableIntStateOf(-1) }
    var dragPos by remember(pos) { mutableStateOf(Offset.Zero) }
    var dragOver by remember(pos) { mutableIntStateOf(-1) }
    val targets = remember(pos, selected) { if (selected >= 0) legalTargets(selected) else emptySet() }
    val currentCandidates by rememberUpdatedState(candidates)
    val currentOnMove by rememberUpdatedState(onMove)

    fun own(sq: Int) = pos.board[sq] != EMPTY && colorOf(pos.board[sq]) == pos.toMove

    /** Versucht den Zug [from]–[to]; liefert false, wenn er nicht legal ist. */
    fun tryMove(from: Int, to: Int): Boolean {
        val ms = currentCandidates(from, to)
        when {
            ms.size == 1 -> currentOnMove(ms[0])
            ms.size > 1 -> promoChoices = ms
            else -> return false
        }
        selected = -1
        return true
    }

    fun tap(sq: Int) {
        when {
            sq == selected -> selected = -1
            selected >= 0 && tryMove(selected, sq) -> {}
            own(sq) -> selected = sq
            else -> selected = -1
        }
    }

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val squareSize = maxWidth / 8
        Column(
            Modifier.fillMaxSize().pointerInput(pos, flipped) {
                val sqPx = size.width / 8f
                fun squareAt(o: Offset): Int {
                    val col = (o.x / sqPx).toInt()
                    val row = (o.y / sqPx).toInt()
                    if (o.x < 0 || o.y < 0 || col > 7 || row > 7) return -1
                    return square(if (flipped) 7 - col else col, if (flipped) row else 7 - row)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val from = squareAt(down.position)
                    var dragging = false
                    var last = down.position
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        last = change.position
                        if (!dragging && from >= 0 && own(from) &&
                            (last - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            dragging = true
                            selected = from
                            dragFrom = from
                        }
                        if (dragging) {
                            dragPos = last
                            dragOver = squareAt(last)
                            change.consume()
                        }
                    }
                    if (dragging) {
                        dragFrom = -1
                        dragOver = -1
                        val to = squareAt(last)
                        // zurück aufs Startfeld: Figur bleibt ausgewählt
                        if (to != from && (to < 0 || !tryMove(from, to))) selected = -1
                    } else if (from >= 0) {
                        tap(from)
                    }
                }
            },
        ) {
            for (row in 0..7) {
                Row(Modifier.weight(1f)) {
                    for (col in 0..7) {
                        val file = if (flipped) 7 - col else col
                        val rank = if (flipped) row else 7 - row
                        val sq = square(file, rank)
                        val light = (file + rank) % 2 == 1
                        val piece = pos.board[sq]
                        val bg = when {
                            sq == selected || sq == dragOver -> SELECT
                            lastMove != null && !lastMove.isNull && (sq == lastMove.from || sq == lastMove.to) -> HIGHLIGHT
                            else -> Color.Transparent
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(if (light) LIGHT else DARK)
                                .background(bg),
                        ) {
                            if (piece != EMPTY && sq != dragFrom) {
                                Image(
                                    painterResource(pieceRes(piece)), null,
                                    Modifier.fillMaxSize().padding(1.dp),
                                )
                            }
                            if (sq in targets) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val r = size.minDimension / (if (piece == EMPTY) 6.5f else 2.1f)
                                    if (piece == EMPTY) {
                                        drawCircle(Color(0x55000000), r)
                                    } else {
                                        drawCircle(Color(0x55000000), r, style = androidx.compose.ui.graphics.drawscope.Stroke(size.minDimension / 12))
                                    }
                                }
                            }
                            val coordColor = if (light) DARK else LIGHT
                            if (col == 0) {
                                Text("${rank + 1}", Modifier.align(Alignment.TopStart).padding(start = 2.dp),
                                    color = coordColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            if (row == 7) {
                                Text("${'a' + file}", Modifier.align(Alignment.BottomEnd).padding(end = 2.dp),
                                    color = coordColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        // gezogene Figur etwas vergrößert und über dem Finger, damit sie sichtbar bleibt
        if (dragFrom >= 0) {
            val piece = pos.board[dragFrom]
            val dragSize = squareSize * 1.5f
            Image(
                painterResource(pieceRes(piece)), null,
                Modifier
                    .offset {
                        val half = dragSize.toPx() / 2
                        IntOffset((dragPos.x - half).roundToInt(), (dragPos.y - dragSize.toPx() * 0.9f).roundToInt())
                    }
                    .size(dragSize),
            )
        }
    }

    if (promoChoices.isNotEmpty()) {
        val color = pos.toMove
        AlertDialog(
            onDismissRequest = { promoChoices = emptyList() },
            title = { Text(stringResource(R.string.promote_to)) },
            text = {
                Row {
                    for (t in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) {
                        val m = promoChoices.firstOrNull { it.promo == t } ?: continue
                        Image(
                            painterResource(pieceRes(pieceOf(color, t))), null,
                            Modifier.weight(1f).aspectRatio(1f).clickable {
                                promoChoices = emptyList()
                                onMove(m)
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ promoChoices = emptyList() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
