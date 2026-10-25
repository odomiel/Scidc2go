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
package org.scidc2go.engine

import org.scidc2go.AppLog
import org.scidc2go.LocalizedIOException
import org.scidc2go.R
import org.scidc2go.Text
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Das NNUE-Netz von Stockfish ist zu groß für die APK und wird beim ersten Start
 * der Engine von stockfishchess.org geladen. Name und Größe trägt tools/build-stockfish.sh ein.
 */
object StockfishNet {
    const val NAME = "nn-1a298aa575a0.nnue"
    const val SIZE = 98_511_183L

    private val urls = listOf(
        "https://tests.stockfishchess.org/api/nn/$NAME",
        "https://github.com/official-stockfish/networks/raw/master/$NAME",
    )

    fun file(dir: File) = File(dir, NAME)

    /** Lädt das Netz nach [dir] und prüft es: Der Name enthält den Anfang seiner SHA-256-Prüfsumme. */
    fun download(dir: File, progress: (Float) -> Unit): File {
        val target = file(dir)
        val tmp = File(dir, "$NAME.tmp")
        var error: Exception? = null
        for (url in urls) {
            try {
                AppLog.i("Netz", "Lade $url")
                fetch(url, tmp, progress)
                tmp.renameTo(target)
                // ältere Netze früherer Versionen entfernen
                dir.listFiles()?.filter { it.name.endsWith(".nnue") && it.name != NAME }?.forEach { it.delete() }
                return target
            } catch (e: Exception) {
                AppLog.w("Netz", "Laden von $url fehlgeschlagen", e)
                tmp.delete()
                error = e
            }
        }
        throw LocalizedIOException(Text(R.string.err_net_download, error?.message), error)
    }

    private fun fetch(url: String, tmp: File, progress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        progress((done.toFloat() / SIZE).coerceAtMost(1f))
                    }
                }
            }
            val hash = sha.digest().joinToString("") { "%02x".format(it) }
            if (done != SIZE || !NAME.startsWith("nn-" + hash.take(12))) throw LocalizedIOException(R.string.err_checksum)
        } finally {
            conn.disconnect()
        }
    }
}
