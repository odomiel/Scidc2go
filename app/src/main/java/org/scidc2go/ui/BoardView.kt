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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
 * Schachbrett. Züge werden durch Antippen von Start- und Zielfeld eingegeben.
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
    var selected by remember(pos) { mutableStateOf(-1) }
    var promoChoices by remember { mutableStateOf<List<Move>>(emptyList()) }
    val targets = remember(pos, selected) { if (selected >= 0) legalTargets(selected) else emptySet() }

    Column(modifier.aspectRatio(1f)) {
        for (row in 0..7) {
            Row(Modifier.weight(1f)) {
                for (col in 0..7) {
                    val file = if (flipped) 7 - col else col
                    val rank = if (flipped) row else 7 - row
                    val sq = square(file, rank)
                    val light = (file + rank) % 2 == 1
                    val piece = pos.board[sq]
                    val bg = when {
                        sq == selected -> SELECT
                        lastMove != null && !lastMove.isNull && (sq == lastMove.from || sq == lastMove.to) -> HIGHLIGHT
                        else -> Color.Transparent
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(if (light) LIGHT else DARK)
                            .background(bg)
                            .clickable {
                                if (selected >= 0 && sq != selected) {
                                    val ms = candidates(selected, sq)
                                    when {
                                        ms.size == 1 -> { onMove(ms[0]); selected = -1 }
                                        ms.size > 1 -> { promoChoices = ms; selected = -1 }
                                        piece != EMPTY && colorOf(piece) == pos.toMove -> selected = sq
                                        else -> selected = -1
                                    }
                                } else if (sq == selected) {
                                    selected = -1
                                } else if (piece != EMPTY && colorOf(piece) == pos.toMove) {
                                    selected = sq
                                }
                            },
                    ) {
                        if (piece != EMPTY) {
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

    if (promoChoices.isNotEmpty()) {
        val color = pos.toMove
        AlertDialog(
            onDismissRequest = { promoChoices = emptyList() },
            title = { Text("Umwandeln in") },
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
            dismissButton = { TextButton({ promoChoices = emptyList() }) { Text("Abbrechen") } },
        )
    }
}
