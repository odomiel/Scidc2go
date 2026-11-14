package org.scidc2go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scidc2go.chess.Pgn
import org.scidc2go.db.Archives
import org.scidc2go.db.ChessDatabase
import org.scidc2go.db.JavaFileSet
import org.scidc2go.db.PgnDatabase
import org.scidc2go.db.Scid4Database
import org.scidc2go.db.Scid5Database
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

    @Test(expected = IOException::class)
    fun scidbArchiveWithScid5() {
        // Scidb kennt Scid 5 nicht; solche Archive werden wie dort abgelehnt
        val dir = createTempDirectory().toFile()
        val files = listOf("si5", "sg5", "sn5").map { File(resDir, "s5.$it") to "zlib" }
        Archives.extractScidbArchive(scidbArchive(files).inputStream(), dir)
        Archives.openExtracted(dir, "s5", "Scidb-Archiv", {})
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

    /** Packt Dateien wie die App (`name.si5` usw.); mit SCIDC2GO_OUT=<dir> bleibt das Archiv zum Prüfen mit 7-Zip dort liegen. */
    private fun pack7z(base: String, exts: List<String>, newBase: String): File {
        val out = System.getenv("SCIDC2GO_OUT")?.let { File(it).apply { mkdirs() } } ?: createTempDirectory().toFile()
        val archive = File(out, "$newBase-${exts.first()}.sc7")
        Archives.write7z(archive, exts.map { ext -> "$newBase.$ext" to { File(resDir, "$base.$ext").inputStream() } })
        return archive
    }

    @Test fun sevenZipRoundtrip() {
        val cases = listOf(
            Triple("s5", listOf("si5", "sg5", "sn5")) { Scid5Database.open(JavaFileSet(resDir, "s5"), writable = false) },
            Triple("s4", listOf("si4", "sg4", "sn4")) { Scid4Database(JavaFileSet(resDir, "s4")) },
            Triple("sample", listOf("pgn")) { PgnDatabase("a", RandomAccessFile(File(resDir, "sample.pgn"), "r").channel) },
        )
        for ((base, exts, original) in cases) {
            val archive = pack7z(base, exts, "neu")
            val dir = createTempDirectory().toFile()
            assertEquals(exts.map { "neu.$it" }, Archives.extract7z(archive, dir))
            for (ext in exts) assertTrue(File(resDir, "$base.$ext").readBytes().contentEquals(File(dir, "neu.$ext").readBytes()))
            val expected = original().use { pgns(it) }
            val db = Archives.open7zExtracted(dir, "neu", {})
            assertFalse(db.writable)
            assertTrue(db.formatName.endsWith(", 7z"))
            assertEquals(expected, pgns(db))
            db.close()
            assertFalse("Arbeitsverzeichnis wird beim Schließen gelöscht", dir.exists())
        }
    }

    @Test fun sevenZipFrom7Zip() {
        // Mit 7-Zip 23.01 erzeugt und dann umbenannt: "7zz a s5.7z s5.si5 s5.sg5 s5.sn5" (solid, LZMA2)
        // und "7zz a -mx=9 sample.7z partien" (PGN in einem Unterordner)
        val s5 = Scid5Database.open(JavaFileSet(resDir, "s5"), writable = false).use { pgns(it) }
        val dir = createTempDirectory().toFile()
        Archives.extract7z(File(resDir, "s5.sc7"), dir)
        Archives.open7zExtracted(dir, "s5", {}).use {
            assertEquals("Scid 5, 7z", it.formatName)
            assertEquals(s5, pgns(it))
        }

        val pgn = PgnDatabase("a", RandomAccessFile(File(resDir, "sample.pgn"), "r").channel).use { pgns(it) }
        val dir2 = createTempDirectory().toFile()
        assertEquals(listOf("sample.pgn"), Archives.extract7z(File(resDir, "sample.sc7"), dir2))
        Archives.open7zExtracted(dir2, "sample", {}).use { assertEquals(pgn, pgns(it)) }
    }

    @Test(expected = IOException::class)
    fun sevenZipMissingFile() {
        val dir = createTempDirectory().toFile()
        val archive = File(createTempDirectory().toFile(), "x.sc7")
        Archives.write7z(archive, listOf("x.si5" to { File(resDir, "s5.si5").inputStream() }))
        Archives.extract7z(archive, dir)
        Archives.open7zExtracted(dir, "x", {})
    }

    @Test(expected = LocalizedIOException::class)
    fun sevenZipWithoutDatabase() {
        val dir = createTempDirectory().toFile()
        val archive = File(createTempDirectory().toFile(), "x.sc7")
        Archives.write7z(archive, listOf("liesmich.txt" to { "Hallo".byteInputStream() }))
        Archives.extract7z(archive, dir)
        Archives.open7zExtracted(dir, "x", {})
    }

    @Test(expected = LocalizedIOException::class)
    fun sevenZipCorrupt() {
        val bytes = File(resDir, "s5.sc7").readBytes()
        val broken = File(createTempDirectory().toFile(), "x.sc7")
        broken.writeBytes(bytes.copyOf(bytes.size - 40))
        Archives.extract7z(broken, createTempDirectory().toFile())
    }

    /** Namen von Dokumenten-Anbietern oder aus Archiven dürfen nicht aus dem Arbeitsverzeichnis herausführen. */
    @Test fun fileInStaysInDir() {
        val dir = createTempDirectory().toFile()
        for (name in listOf("../../files/x.pgn", "..\\x.pgn", "/etc/x.pgn", "a/b.pgn", "WM: 2024.pgn")) {
            val f = Archives.fileIn(dir, name)
            assertEquals(name, dir.canonicalFile, f.canonicalFile.parentFile)
        }
        assertEquals("WM: 2024.pgn", Archives.fileIn(dir, "WM: 2024.pgn").name)
        for (name in listOf("..", ".", "", "  ")) {
            try {
                Archives.fileIn(dir, name)
                throw AssertionError("'$name' wurde angenommen")
            } catch (_: LocalizedIOException) {
            }
        }
    }
}
