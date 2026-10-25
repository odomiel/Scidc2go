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
package org.scidc2go.db

import org.scidc2go.chess.Game
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Datenbank im Format Scid 5 (.si5/.sg5/.sn5), lesend und schreibend.
 * Aufbau siehe Scid, codec_scid5.h.
 */
class Scid5Database private constructor(
    private val files: DbFileSet,
    private val canWrite: Boolean,
) : ChessDatabase {

    override val name get() = files.baseName
    override val formatName get() = "Scid 5"
    override val writable get() = canWrite

    private var index = ByteArray(0)
    private var count = 0
    override val gameCount get() = count

    private val names = Array(4) { ArrayList<String>() }
    private var lookup: Array<HashMap<String, Int>>? = null

    private var gameRead: FileChannel = files.openRead("sg5")
    private var gameWrite: FileChannel? = null
    private var indexWrite: FileChannel? = null
    private var namesWrite: FileChannel? = null

    init {
        val idx = files.openRead("si5").use { it.readAll() }
        if (idx.size % ENTRY_SIZE != 0) throw IOException("Defekte Scid-5-Indexdatei")
        index = idx
        count = idx.size / ENTRY_SIZE
        readNames(files.openRead("sn5").use { it.readAll() })
    }

    private fun readNames(d: ByteArray) {
        var p = 0
        while (p < d.size) {
            var v = 0L
            var shift = 0
            while (true) {
                if (p >= d.size) throw IOException("Defekte Namensdatei")
                val b = d[p++].toInt() and 0xFF
                v = v or ((b and 127).toLong() shl shift)
                if (b < 128) break
                shift += 7
                if (shift > 63) throw IOException("Defekte Namensdatei")
            }
            val type = (v and 7).toInt()
            val len = (v ushr 3).toInt()
            if (type > 4 || p + len > d.size) throw IOException("Defekte Namensdatei")
            if (type < 4) names[type].add(decodeText(d, p, len))
            p += len
        }
    }

    private fun name(nt: Int, id: Int) = names[nt].getOrNull(id) ?: "?"

    private fun u32(p: Int): Int =
        (index[p].toInt() and 0xFF) or ((index[p + 1].toInt() and 0xFF) shl 8) or
            ((index[p + 2].toInt() and 0xFF) shl 16) or ((index[p + 3].toInt() and 0xFF) shl 24)

    private fun entry(i: Int): ScidIndexEntry {
        if (i !in 0 until count) throw IndexOutOfBoundsException()
        val b = i * ENTRY_SIZE
        val ie = ScidIndexEntry()
        var v = u32(b); ie.rawComments = v ushr 28; ie.white = v and 0x0FFFFFFF
        v = u32(b + 4); ie.rawVariations = v ushr 28; ie.black = v and 0x0FFFFFFF
        v = u32(b + 8); ie.rawNags = v ushr 28; ie.event = v and 0x0FFFFFFF
        ie.site = u32(b + 12)
        v = u32(b + 16); ie.chess960 = (v ushr 31) != 0; ie.round = v and 0x7FFFFFFF
        v = u32(b + 20); ie.whiteElo = v ushr 20; ie.date = v and 0xFFFFF
        v = u32(b + 24); ie.blackElo = v ushr 20; ie.eventDate = v and 0xFFFFF
        v = u32(b + 28); ie.numHalfMoves = v ushr 22; ie.flags = v and 0x3FFFFF
        v = u32(b + 32); ie.length = v ushr 15
        ie.offset = ((v and 0x7FFF).toLong() shl 32) or (u32(b + 36).toLong() and 0xFFFFFFFFL)
        v = u32(b + 40); ie.storedLineCode = v ushr 24; ie.finalMatSig = v and 0xFFFFFF
        v = u32(b + 44)
        val hi = v ushr 16
        ie.homePawnCount = hi ushr 8
        ie.whiteRatingType = (hi shr 5) and 7
        ie.blackRatingType = (hi shr 2) and 7
        ie.result = hi and 3
        ie.eco = v and 0xFFFF
        System.arraycopy(index, b + 48, ie.homePawnData, 0, 8)
        return ie
    }

    private fun encodeEntry(ie: ScidIndexEntry): ByteArray {
        val buf = ByteArray(ENTRY_SIZE)
        fun put(p: Int, v: Int) {
            buf[p] = v.toByte(); buf[p + 1] = (v shr 8).toByte()
            buf[p + 2] = (v shr 16).toByte(); buf[p + 3] = (v shr 24).toByte()
        }
        fun pack(a: Int, aBits: Int, b: Int): Int {
            val bBits = 32 - aBits
            val maskB = if (bBits == 32) -1 else (1 shl bBits) - 1
            return (a shl bBits) or (b and maskB)
        }
        put(0, pack(ie.rawComments, 4, ie.white))
        put(4, pack(ie.rawVariations, 4, ie.black))
        put(8, pack(ie.rawNags, 4, ie.event))
        put(12, ie.site)
        put(16, pack(if (ie.chess960) 1 else 0, 1, ie.round))
        put(20, pack(minOf(ie.whiteElo, 4000), 12, ie.date))
        put(24, pack(minOf(ie.blackElo, 4000), 12, ie.eventDate))
        put(28, pack(ie.numHalfMoves, 10, ie.flags))
        put(32, pack(ie.length, 17, (ie.offset ushr 32).toInt()))
        put(36, ie.offset.toInt())
        put(40, pack(ie.storedLineCode, 8, ie.finalMatSig))
        val hi = (ie.homePawnCount shl 8) or (ie.whiteRatingType shl 5) or (ie.blackRatingType shl 2) or ie.result
        put(44, pack(hi, 16, ie.eco))
        System.arraycopy(ie.homePawnData, 0, buf, 48, 8)
        return buf
    }

    override fun header(index: Int): GameHeader {
        val ie = entry(index)
        return GameHeader(
            white = name(NAME_PLAYER, ie.white),
            black = name(NAME_PLAYER, ie.black),
            event = name(NAME_EVENT, ie.event),
            site = name(NAME_SITE, ie.site),
            round = name(NAME_ROUND, ie.round),
            date = ScidFormat.dateToString(ie.date),
            result = ScidFormat.resultToString(ie.result),
            whiteElo = ie.whiteElo,
            blackElo = ie.blackElo,
            eco = ScidFormat.ecoToString(ie.eco),
            plies = ie.numHalfMoves,
            deleted = ie.flags and FLAG_DELETE != 0,
        )
    }

    override fun loadGame(index: Int): Game {
        val ie = entry(index)
        val data = synchronized(this) { gameRead.readFully(ie.offset, ie.length) }
        return ScidFormat.decodeGame(data, ie, ::name)
    }

    // ------------------------------------------------------------------
    // Schreiben

    private fun ensureWritable() {
        if (!canWrite) throw IOException("Datenbank ist schreibgeschützt")
        if (gameWrite == null) {
            gameWrite = files.openWrite("sg5")
            indexWrite = files.openWrite("si5")
            namesWrite = files.openWrite("sn5")
        }
    }

    private fun nameId(nt: Int, value: String): Int {
        val v = value.ifEmpty { "?" }.take(255)
        val maps = lookup ?: Array(4) { t ->
            HashMap<String, Int>(names[t].size * 2).also { m ->
                names[t].forEachIndexed { i, s -> m.putIfAbsent(s, i) }
            }
        }.also { lookup = it }
        maps[nt][v]?.let { return it }
        val bytes = v.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var x = (bytes.size.toLong() shl 3) or nt.toLong()
        while (x >= 128) { out.write(((x and 127) or 128).toInt()); x = x ushr 7 }
        out.write(x.toInt())
        out.write(bytes)
        val nw = namesWrite!!
        nw.writeFully(nw.size(), out.toByteArray())
        val id = names[nt].size
        names[nt].add(v)
        maps[nt][v] = id
        return id
    }

    /** Speichert Namen und Partiedaten; liefert den fertigen Indexeintrag. */
    private fun store(game: Game): ScidIndexEntry {
        val enc = ScidFormat.encodeGame(game)
        val data = enc.data
        if (data.size >= BLOCK) throw IOException("Partie zu groß für das Scid-5-Format")
        val ie = enc.entry
        ie.event = nameId(NAME_EVENT, enc.names[0])
        ie.site = nameId(NAME_SITE, enc.names[1])
        ie.white = nameId(NAME_PLAYER, enc.names[2])
        ie.black = nameId(NAME_PLAYER, enc.names[3])
        ie.round = nameId(NAME_ROUND, enc.names[4])

        val gw = gameWrite!!
        var size = gw.size()
        // Partien dürfen eine 128-KB-Blockgrenze nicht überschreiten
        val blockSpace = BLOCK - (size % BLOCK)
        if (blockSpace < data.size) {
            gw.writeFully(size, ByteArray(blockSpace.toInt()))
            size += blockSpace
        }
        gw.writeFully(size, data)
        ie.offset = size
        ie.length = data.size
        return ie
    }

    private fun writeEntry(i: Int, ie: ScidIndexEntry) {
        val buf = encodeEntry(ie)
        indexWrite!!.writeFully(i.toLong() * ENTRY_SIZE, buf)
        val needed = (i + 1) * ENTRY_SIZE
        if (index.size < needed) index = index.copyOf(maxOf(needed, index.size + index.size / 2 + ENTRY_SIZE * 16))
        System.arraycopy(buf, 0, index, i * ENTRY_SIZE, ENTRY_SIZE)
    }

    private fun sync() {
        gameWrite?.force(false)
        namesWrite?.force(false)
        indexWrite?.force(false)
    }

    @Synchronized
    override fun addGame(game: Game): Int {
        ensureWritable()
        val ie = store(game)
        val i = count
        writeEntry(i, ie)
        count++
        sync()
        return i
    }

    @Synchronized
    override fun replaceGame(index: Int, game: Game) {
        ensureWritable()
        val old = entry(index)
        val ie = store(game)
        // Benutzermarkierungen (Löschen, Kategorien) übernehmen
        ie.flags = ie.flags or (old.flags and (FLAG_START or FLAG_PROMO or FLAG_UPROMO).inv())
        writeEntry(index, ie)
        sync()
    }

    @Synchronized
    override fun setDeleted(index: Int, deleted: Boolean) {
        ensureWritable()
        val ie = entry(index)
        ie.flags = if (deleted) ie.flags or FLAG_DELETE else ie.flags and FLAG_DELETE.inv()
        writeEntry(index, ie)
        sync()
    }

    /**
     * Entfernt die Indexeinträge der Partien. Ihre Daten bleiben ungenutzt in der .sg5-Datei,
     * bis die Datenbank (z.B. in Scid) komprimiert wird; so werden die Partiedaten nie umgeschrieben.
     */
    @Synchronized
    override fun deleteGames(indices: Collection<Int>) {
        ensureWritable()
        val remove = indices.toHashSet()
        if (remove.isEmpty()) return
        if (remove.any { it !in 0 until count }) throw IndexOutOfBoundsException()
        val kept = ByteArray((count - remove.size) * ENTRY_SIZE)
        var n = 0
        for (i in 0 until count) {
            if (i in remove) continue
            System.arraycopy(index, i * ENTRY_SIZE, kept, n * ENTRY_SIZE, ENTRY_SIZE)
            n++
        }
        val iw = indexWrite!!
        iw.writeFully(0, kept)
        iw.truncate(kept.size.toLong())
        index = kept
        count = n
        sync()
    }

    override fun close() {
        gameRead.close()
        gameWrite?.close()
        indexWrite?.close()
        namesWrite?.close()
    }

    companion object {
        const val ENTRY_SIZE = 56
        private const val BLOCK = 1L shl 17

        fun open(files: DbFileSet, writable: Boolean) = Scid5Database(files, writable)

        /** Legt eine leere Datenbank an (alle drei Dateien dürfen noch nicht existieren). */
        fun create(files: DbFileSet): Scid5Database {
            for (ext in arrayOf("si5", "sg5", "sn5")) {
                if (files.exists(ext)) throw IOException("Datenbank existiert bereits")
            }
            for (ext in arrayOf("si5", "sg5", "sn5")) files.openWrite(ext).close()
            return Scid5Database(files, true)
        }
    }
}
