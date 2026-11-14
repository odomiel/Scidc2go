package org.scidc2go

import org.junit.Assert.assertTrue
import org.junit.Test
import org.scidc2go.chess.Pgn
import org.scidc2go.db.Archives
import org.scidc2go.db.ChessDatabase
import org.scidc2go.db.JavaFileSet
import org.scidc2go.db.PgnDatabase
import org.scidc2go.db.Scid4Database
import org.scidc2go.db.Scid5Database
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createTempDirectory
import kotlin.random.Random

/**
 * Lädt zufällig beschädigte Datenbanken (Bytes verändert, Felder auf 0xFF, Datei abgeschnitten). Erlaubt ist nur,
 * dass sie gelesen oder mit einer IOException abgewiesen werden; RuntimeExceptions, Errors und Hänger sind Fehler.
 * Nach dem Audit vom 08.11.2026; der Zufallsgenerator hat einen festen Startwert, die Fälle sind also immer dieselben.
 */
class FuzzTest {
    private val res = File(javaClass.getResource("/s5.si5")!!.toURI()).parentFile!!
    private val pool = Executors.newCachedThreadPool { r -> Thread(null, r, "fuzz", 1L shl 20).apply { isDaemon = true } }
    private val failures = ArrayList<String>()

    private fun exercise(open: () -> ChessDatabase) {
        open().use { db ->
            for (i in 0 until minOf(db.gameCount, 200)) { db.header(i); Pgn.write(db.loadGame(i)) }
        }
    }

    private fun check(label: String, block: () -> Unit) {
        val f = pool.submit<Unit> { block() }
        try {
            f.get(10, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true)
            failures.add("$label: hängt länger als 10 s")
        } catch (e: ExecutionException) {
            val c = e.cause!!
            if (c is IOException) return // auch LocalizedIOException: sauber abgewiesen
            val at = c.stackTrace.firstOrNull { it.className.startsWith("org.scidc2go") }
            failures.add("$label: ${c.javaClass.simpleName} bei ${at?.fileName}:${at?.lineNumber}: ${c.message?.take(80)}")
        }
    }

    private fun mutate(src: File, dst: File, rnd: Random) {
        val b = src.readBytes()
        when (rnd.nextInt(3)) {
            0 -> repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            1 -> { val p = rnd.nextInt(minOf(b.size, 200)); for (k in 0 until 3) if (p + k < b.size) b[p + k] = 0xFF.toByte() }
            else -> { dst.writeBytes(b.copyOf(rnd.nextInt(b.size))); return }
        }
        dst.writeBytes(b)
    }

    /** Beschädigt je Durchgang eine der Dateien [exts] der Datenbank [base] aus den Testdaten. */
    private fun fuzz(base: String, exts: List<String>, iterations: Int, open: (File) -> ChessDatabase) {
        val rnd = Random(4711)
        repeat(iterations) { n ->
            val dir = createTempDirectory("fuzz").toFile()
            try {
                val victim = exts[rnd.nextInt(exts.size)]
                for (e in exts) {
                    val src = File(res, "$base.$e")
                    val dst = File(dir, "$base.$e")
                    if (e == victim) mutate(src, dst, rnd) else src.copyTo(dst)
                }
                check("$base.$victim #$n") { exercise { open(dir) } }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private fun assertNoFailures() =
        assertTrue("${failures.size} Fehler:\n" + failures.take(10).joinToString("\n"), failures.isEmpty())

    @Test fun scid4() {
        fuzz("s4", listOf("si4", "sg4", "sn4"), 300) { Scid4Database(JavaFileSet(it, "s4")) }
        assertNoFailures()
    }

    @Test fun scid5() {
        fuzz("s5", listOf("si5", "sg5", "sn5"), 300) { Scid5Database.open(JavaFileSet(it, "s5"), writable = false) }
        assertNoFailures()
    }

    @Test fun pgn() {
        fuzz("sample", listOf("pgn"), 150) { PgnDatabase("x", RandomAccessFile(File(it, "sample.pgn"), "r").channel) }
        assertNoFailures()
    }

    @Test fun sevenZip() {
        fuzz("sample", listOf("sc7"), 150) { d ->
            val out = File(d, "x").apply { mkdirs() }
            Archives.extract7z(File(d, "sample.sc7"), out)
            Archives.open7zExtracted(out, "sample") {}
        }
        assertNoFailures()
    }

    /** Tiefe Verschachtelung darf den PGN-Leser und -Schreiber nicht über den Stapel laufen lassen. */
    @Test fun deepPgn() {
        val deep = buildString { append("1. e4 e5 "); repeat(20000) { append("(1... c5 ") } }
        check("20000 verschachtelte Klammern") { Pgn.write(Pgn.parseGame(deep)) }
        val nested = buildString { append("1. Nf3 Nf6 "); repeat(4000) { append("2. Ng1 (2. Nc3 Nc6 3. Nb1 Nb8 ") } }
        check("4000 Varianten ineinander") { Pgn.write(Pgn.parseGame(nested)) }
        assertNoFailures()
    }
}
