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

import org.scidc2go.LocalizedIOException
import org.scidc2go.R
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
        if (idx.size % ENTRY_SIZE != 0) throw LocalizedIOException(R.string.err_scid5_index)
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
                if (p >= d.size) throw LocalizedIOException(R.string.err_corrupt_names)
                val b = d[p++].toInt() and 0xFF
                v = v or ((b and 127).toLong() shl shift)
                if (b < 128) break
                shift += 7
                if (shift > 63) throw LocalizedIOException(R.string.err_corrupt_names)
            }
            val type = (v and 7).toInt()
            val len = (v ushr 3).toInt()
            if (type > 4 || p + len > d.size) throw LocalizedIOException(R.string.err_corrupt_names)
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
        if (!canWrite) throw LocalizedIOException(R.string.err_read_only)
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
        val nw = namesWrite!!
        nw.writeFully(nw.size(), encodeName(nt, v))
        val id = names[nt].size
        names[nt].add(v)
        maps[nt][v] = id
        return id
    }

    /** Eintrag der Namensdatei: Länge und Typ als Varint, dann der Text in UTF-8. */
    private fun encodeName(nt: Int, v: String): ByteArray {
        val bytes = v.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var x = (bytes.size.toLong() shl 3) or nt.toLong()
        while (x >= 128) { out.write(((x and 127) or 128).toInt()); x = x ushr 7 }
        out.write(x.toInt())
        out.write(bytes)
        return out.toByteArray()
    }

    /** Speichert Namen und Partiedaten; liefert den fertigen Indexeintrag. */
    private fun store(game: Game): ScidIndexEntry {
        val enc = ScidFormat.encodeGame(game)
        val data = enc.data
        if (data.size >= BLOCK) throw GameTooLargeException()
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

    /** Bei false wird nicht nach jeder Änderung auf den Speicher geschrieben, sondern erst beim Schließen. */
    private var syncEachWrite = true

    private fun sync() {
        if (!syncEachWrite) return
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

    /**
     * Schreibt eine komprimierte Kopie nach [out]: ohne zum Löschen markierte Partien, ohne ungenutzten
     * Platz in der Partiedatei und ohne Namen, die keine Partie mehr verwendet. Die Partiedaten werden
     * unverändert übernommen. Liefert die Indizes der entfernten Partien.
     */
    @Synchronized
    fun compactTo(out: DbFileSet): List<Int> {
        val removed = ArrayList<Int>()
        val newNames = Array(4) { ArrayList<String>() }
        val idMaps = Array(4) { HashMap<Int, Int>() }
        fun remap(nt: Int, id: Int) = idMaps[nt].getOrPut(id) { newNames[nt].add(name(nt, id)); newNames[nt].size - 1 }
        out.openWrite("sg5").use { gw ->
            out.openWrite("si5").use { iw ->
                gw.truncate(0)
                iw.truncate(0)
                var size = 0L
                var n = 0
                for (i in 0 until count) {
                    val ie = entry(i)
                    if (ie.flags and FLAG_DELETE != 0) { removed.add(i); continue }
                    val data = gameRead.readFully(ie.offset, ie.length)
                    val blockSpace = BLOCK - (size % BLOCK)
                    if (blockSpace < data.size) {
                        gw.writeFully(size, ByteArray(blockSpace.toInt()))
                        size += blockSpace
                    }
                    gw.writeFully(size, data)
                    ie.offset = size
                    size += data.size
                    ie.white = remap(NAME_PLAYER, ie.white)
                    ie.black = remap(NAME_PLAYER, ie.black)
                    ie.event = remap(NAME_EVENT, ie.event)
                    ie.site = remap(NAME_SITE, ie.site)
                    ie.round = remap(NAME_ROUND, ie.round)
                    iw.writeFully(n.toLong() * ENTRY_SIZE, encodeEntry(ie))
                    n++
                }
                gw.force(false)
                iw.force(false)
            }
        }
        out.openWrite("sn5").use { nw ->
            nw.truncate(0)
            val buf = ByteArrayOutputStream()
            for (nt in 0 until 4) for (v in newNames[nt]) buf.write(encodeName(nt, v))
            nw.writeFully(0, buf.toByteArray())
            nw.force(false)
        }
        return removed
    }

    override fun close() {
        syncEachWrite = true
        sync()
        gameRead.close()
        gameWrite?.close()
        indexWrite?.close()
        namesWrite?.close()
    }

    companion object {
        const val ENTRY_SIZE = 56
        private const val BLOCK = 1L shl 17

        val EXTS = listOf("si5", "sg5", "sn5")

        /**
         * Schreibt alle Partien aus [src] in die neue Scid-5-Datenbank [target] (deren Dateien dürfen noch nicht
         * existieren); Löschmarkierungen werden übernommen. Die Datenbank entsteht zuerst als „name.neu“ und wird
         * erst am Ende umbenannt. [progress] erhält die Zahl der bearbeiteten Partien. Liefert die Indizes der
         * Partien, die sich nicht übernehmen ließen (z.B. zu groß für das Format).
         */
        fun convert(src: ChessDatabase, target: DbFileSet, progress: (Int) -> Unit = {}): List<Int> {
            if (EXTS.any { target.exists(it) }) throw LocalizedIOException(R.string.err_exists, target.baseName)
            val tmp = target.sibling("${target.baseName}.neu")
            tmp.delete(EXTS)
            val failed = ArrayList<Int>()
            create(tmp).use { db ->
                db.syncEachWrite = false
                for (i in 0 until src.gameCount) {
                    progress(i)
                    // defekte Partien der Quelle und nicht darstellbare überspringen, Schreibfehler abbrechen
                    val game = try { src.loadGame(i) } catch (e: Exception) { failed.add(i); continue }
                    val idx = try {
                        db.addGame(game)
                    } catch (e: GameTooLargeException) {
                        failed.add(i); continue
                    } catch (e: RuntimeException) {
                        failed.add(i); continue
                    }
                    if (src.header(i).deleted) db.setDeleted(idx, true)
                }
                progress(src.gameCount)
            }
            tmp.rename(target.baseName, EXTS)
            return failed
        }

        fun open(files: DbFileSet, writable: Boolean) = Scid5Database(files, writable)

        /**
         * Komprimiert die Datenbank [files] (siehe [compactTo]). Die Datenbank darf dabei nicht geöffnet sein.
         * Die Kopie entsteht als „name.neu“; erst wenn sie vollständig ist, wird das Original zu „name.alt“
         * umbenannt, die Kopie zu „name“ und das Original gelöscht. Bei einem Abbruch liegt also immer
         * eine vollständige Datenbank im Ordner. Liefert die Indizes der entfernten Partien.
         */
        fun compact(files: DbFileSet): List<Int> {
            val base = files.baseName
            val tmp = files.sibling("$base.neu")
            // Reste eines abgebrochenen Laufs; das Original ist vollständig vorhanden
            tmp.delete(EXTS)
            files.sibling("$base.alt").delete(EXTS)
            val removed = open(files, false).use { it.compactTo(tmp) }
            files.rename("$base.alt", EXTS)
            tmp.rename(base, EXTS)
            files.delete(EXTS)
            return removed
        }

        /** Legt eine leere Datenbank an (alle drei Dateien dürfen noch nicht existieren). */
        fun create(files: DbFileSet): Scid5Database {
            for (ext in arrayOf("si5", "sg5", "sn5")) {
                if (files.exists(ext)) throw LocalizedIOException(R.string.err_db_exists)
            }
            for (ext in arrayOf("si5", "sg5", "sn5")) files.openWrite(ext).close()
            return Scid5Database(files, true)
        }
    }
}

/** Die Partie überschreitet die Größengrenze des Scid-5-Formats (128 KB). */
class GameTooLargeException : LocalizedIOException(R.string.err_game_too_large)
