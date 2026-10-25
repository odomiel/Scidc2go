package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scidc2go.chess.Pgn
import org.scidc2go.db.Archives
import org.scidc2go.db.ChessDatabase
import org.scidc2go.db.DbFileSet
import org.scidc2go.db.JavaFileSet
import org.scidc2go.db.PgnDatabase
import org.scidc2go.db.Scid4Database
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class ArchiveTest {
    private val resDir = File(javaClass.getResource("/s4.si4")!!.toURI()).parentFile!!

    private fun pgns(db: ChessDatabase) = (0 until db.gameCount).map { Pgn.write(db.loadGame(it)) }

    /** Schreibt ein Scidb-Archiv so, wie es `archive::packFiles` in Scidbs `tcl/utils/archive.tcl` tut. */
    private fun scidbArchive(files: List<Pair<File, String>>, corrupt: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun puts(s: String, newline: Boolean = true) = out.write((if (newline) "$s\n" else s).toByteArray())
        puts("iveArch")
        puts("<TotalSize> ${files.sumOf { it.first.length() }}")
        puts("<Count> 3")
        puts("<Format> si4")
        puts("<Type> single", newline = false)
        for ((f, compression) in files) {
            val data = f.readBytes()
            val stored = if (compression == "zlib") {
                ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(data) } }.toByteArray()
            } else data
            val crc = CRC32().apply { update(stored) }.value
            puts("")
            puts("<-- H E A D -->")
            puts("<FileName> ${f.name}")
            puts("<FileSize> ${data.size}")
            puts("<Compression> $compression")
            puts("<Modified> 1761379200")
            puts("<Size> ${stored.size}         ")
            puts("<Checksum> ${if (corrupt) crc xor 1 else crc}  ")
            puts("<-- D A T A -->")
            out.write(stored)
        }
        return out.toByteArray()
    }

    @Test fun readScidbArchive() {
        val dir = createTempDirectory().toFile()
        val files = listOf("si4" to "zlib", "sg4" to "zlib", "sn4" to "raw").map { File(resDir, "s4.${it.first}") to it.second }
        val names = Archives.extractScidbArchive(scidbArchive(files).inputStream(), dir)
        assertEquals(listOf("s4.si4", "s4.sg4", "s4.sn4"), names)
        for ((f, _) in files) assertTrue(f.readBytes().contentEquals(File(dir, f.name).readBytes()))

        val expected = Scid4Database(JavaFileSet(resDir, "s4")).use { pgns(it) }
        val db = Archives.openExtracted(dir, "s4", "Scidb-Archiv", {})
        assertEquals("s4", db.name)
        assertFalse(db.writable)
        assertEquals(expected, pgns(db))
        db.close()
        assertFalse("Arbeitsverzeichnis wird beim Schließen gelöscht", dir.exists())
    }

    @Test(expected = IOException::class)
    fun scidbArchiveChecksum() {
        val files = listOf(File(resDir, "s4.si4") to "zlib")
        Archives.extractScidbArchive(scidbArchive(files, corrupt = true).inputStream(), createTempDirectory().toFile())
    }

    @Test fun convertToScidbArchive() {
        val dir = createTempDirectory().toFile()
        val work = createTempDirectory().toFile()
        val expected = Scid4Database(JavaFileSet(resDir, "s4")).use { src ->
            assertEquals(emptyList<Int>(), Archives.convertToScidbArchive(src, JavaFileSet(dir, "arch"), work))
            pgns(src)
        }
        assertEquals(listOf("arch.scv"), dir.list()!!.toList())
        val text = File(dir, "arch.scv").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("iveArch\n<TotalSize> "))
        assertTrue("<Count> 3\n<Format> si5\n<Type> single\n<-- H E A D -->\n<FileName> arch.si5\n" in text)
        // Zielname schon vorhanden
        assertThrows(IOException::class.java) {
            Scid4Database(JavaFileSet(resDir, "s4")).use { Archives.convertToScidbArchive(it, JavaFileSet(dir, "arch"), work) }
        }

        val out = createTempDirectory().toFile()
        assertEquals(listOf("arch.sg5", "arch.si5", "arch.sn5"),
            Archives.extractScidbArchive(File(dir, "arch.scv").inputStream(), out).sorted())
        Archives.openExtracted(out, "arch", "Scidb-Archiv", {}).use { assertEquals(expected, pgns(it)) }
        assertEquals(emptyList<String>(), work.list()!!.toList())
    }

    @Test fun editScidbArchive() {
        val dir = createTempDirectory().toFile()
        val work = createTempDirectory().toFile()
        Scid4Database(JavaFileSet(resDir, "s4")).use { Archives.convertToScidbArchive(it, JavaFileSet(dir, "ed"), work) }
        val extra = PgnDatabase("a", RandomAccessFile(File(resDir, "sample.pgn"), "r").channel).use { it.loadGame(1) }

        fun open(): ChessDatabase {
            val out = java.nio.file.Files.createTempDirectory(work.toPath(), "db").toFile()
            Archives.extractScidbArchive(File(dir, "ed.scv").inputStream(), out)
            var target: DbFileSet = JavaFileSet(dir, "ed")
            return Archives.openExtracted(out, "ed", "Scidb-Archiv", {}, { db ->
                target = Archives.writeScidbArchiveFile(target, out.listFiles()!!.sortedBy { it.name }, db.gameCount, work)
            })
        }

        val before = open().use { db ->
            assertTrue(db.writable)
            val list = pgns(db)
            assertEquals(3, db.addGame(extra))
            db.setDeleted(0, true)
            db.replaceGame(1, extra)
            list
        }
        assertEquals(listOf("ed.scv"), dir.list()!!.toList())
        open().use { db ->
            assertEquals(4, db.gameCount)
            assertTrue(db.header(0).deleted)
            val now = pgns(db)
            assertEquals(before[0], now[0])
            assertEquals(Pgn.write(extra), now[1])
            assertEquals(before[2], now[2])
            assertEquals(Pgn.write(extra), now[3])
            db.deleteGames(listOf(0))
        }
        open().use { assertEquals(3, it.gameCount) }
        assertEquals(listOf("ed.scv"), dir.list()!!.toList())
    }

    @Test fun readPgnGzip() {
        val dir = createTempDirectory().toFile()
        val pgn = File(resDir, "sample.pgn")
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(pgn.readBytes()) } }.toByteArray()
        Archives.extractGzip(gz.inputStream(), File(dir, "sample.pgn"))
        val expected = PgnDatabase("a", RandomAccessFile(pgn, "r").channel).use { pgns(it) }
        Archives.openExtracted(dir, "sample", "PGN gzip", {}).use { assertEquals(expected, pgns(it)) }
    }

    @Test fun readPgnZip() {
        val dir = createTempDirectory().toFile()
        val pgn = File(resDir, "sample.pgn").readBytes()
        val zip = ByteArrayOutputStream().also {
            ZipOutputStream(it).use { z ->
                for (name in listOf("a.pgn", "liesmich.txt", "b/c.PGN")) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(pgn)
                    z.closeEntry()
                }
            }
        }.toByteArray()
        assertEquals(2, Archives.extractZipPgn(zip.inputStream(), File(dir, "z.pgn")))
        val single = PgnDatabase("a", RandomAccessFile(File(resDir, "sample.pgn"), "r").channel).use { pgns(it) }
        Archives.openExtracted(dir, "z", "PGN zip", {}).use { assertEquals(single + single, pgns(it)) }
    }
}
