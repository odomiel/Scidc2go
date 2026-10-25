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
package org.scidc2go

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Protokoll zur Fehleranalyse. Es liegt nur im App-Verzeichnis und verlässt das Gerät nur, wenn der Nutzer
 * es speichert. Fehler, Abstürze und wichtige Ereignisse stehen immer darin, mit [detailed] auch die
 * Kommunikation mit der Engine. Die Datei wird höchstens [MAX_SIZE] groß; danach wird sie zu `.1`
 * (die vorige `.1` entfällt) und eine neue begonnen. Ohne [init] (z.B. in JVM-Tests) wird nichts geschrieben.
 */
object AppLog {
    private const val MAX_SIZE = 512 * 1024L
    private const val TAG = "Scidc2go"

    private var file: File? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** Auch Ausgaben über [d] schreiben (Engine-Kommunikation). */
    @Volatile var detailed = false

    /**
     * Öffnet das Protokoll in [dir], schreibt den Kopf [header] (Version, Gerät) und fängt Abstürze ab.
     * Weitere Aufrufe (z.B. nach Neuerzeugen der Activity) tun nichts.
     */
    @Synchronized
    fun init(dir: File, header: String) {
        if (file != null) return
        file = File(dir, "scidc2go.log")
        write('I', "Start", header)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                write('E', "Absturz", "Thread ${thread.name}", e)
            } catch (_: Throwable) {
                // Protokoll darf den Absturzbericht von Android nicht verhindern
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun i(area: String, msg: String) = write('I', area, msg)
    fun w(area: String, msg: String, e: Throwable? = null) = write('W', area, msg, e)
    fun e(area: String, msg: String, e: Throwable? = null) = write('E', area, msg, e)

    /** Nur bei [detailed]; [msg] wird erst dann erzeugt. */
    fun d(area: String, msg: () -> String) {
        if (detailed) write('D', area, msg())
    }

    @Synchronized
    private fun write(level: Char, area: String, msg: String, e: Throwable? = null) {
        when (level) {
            'E' -> Log.e(TAG, "$area: $msg", e)
            'W' -> Log.w(TAG, "$area: $msg", e)
            else -> Log.i(TAG, "$area: $msg")
        }
        val f = file ?: return
        try {
            if (f.length() > MAX_SIZE) f.renameTo(File(f.path + ".1"))
            val sb = StringBuilder()
            sb.append(time.format(Date())).append(' ').append(level).append(' ').append(area).append(": ").append(msg).append('\n')
            if (e != null) sb.append(e.stackTraceToString().prependIndent("    ")).append('\n')
            f.appendText(sb.toString())
        } catch (_: Exception) {
            // Ohne Protokoll weiterarbeiten (z.B. Speicher voll)
        }
    }

    /** Inhalt des Protokolls, ältere Datei zuerst. */
    @Synchronized
    fun text(): String {
        val f = file ?: return ""
        return listOf(File(f.path + ".1"), f).filter { it.exists() }.joinToString("") { it.readText() }
    }

    @Synchronized
    fun clear() {
        val f = file ?: return
        File(f.path + ".1").delete()
        f.delete()
    }
}
