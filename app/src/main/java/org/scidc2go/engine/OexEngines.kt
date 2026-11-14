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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import org.scidc2go.AppLog
import org.scidc2go.LocalizedIOException
import org.scidc2go.R
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

/** Eine Engine aus einer installierten Engine-App nach dem Open-Exchange-Standard (OEX). */
data class OexEngine(
    val name: String,
    val packageName: String,
    val authority: String,
    val fileName: String,
) {
    /** Kennung für die Einstellungen. */
    val id get() = "oex:$packageName/$fileName"
}

/**
 * Findet Engines in installierten OEX-Apps und bereitet sie zum Start vor.
 *
 * Seit Android 10 darf eine App keine Dateien aus ihrem Datenverzeichnis direkt ausführen.
 * Die Engine wird deshalb kopiert und über den System-Linker gestartet.
 */
object OexEngines {
    private const val ACTION = "intent.chess.provider.ENGINE"
    private const val AUTHORITY = "chess.provider.engine.authority"
    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")

    fun list(ctx: Context): List<OexEngine> {
        val pm = ctx.packageManager
        val abis = Build.SUPPORTED_ABIS.toSet()
        val result = ArrayList<OexEngine>()
        for (ri in pm.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA)) {
            val ai = ri.activityInfo ?: continue
            val authority = ai.metaData?.getString(AUTHORITY) ?: continue
            try {
                val res = pm.getResourcesForApplication(ai.applicationInfo)
                // Der OEX-Standard legt die Engine-Liste als res/xml/enginelist.xml der fremden App fest;
                // ihre Ressourcen-ID kennen wir nicht, nur der Name führt dorthin.
                @SuppressLint("DiscouragedApi")
                val id = res.getIdentifier("enginelist", "xml", ai.packageName)
                if (id == 0) continue
                val parser = res.getXml(id)
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG || parser.name != "engine") continue
                    val name = parser.getAttributeValue(null, "name") ?: continue
                    val file = parser.getAttributeValue(null, "filename") ?: continue
                    // wird Dateiname im eigenen Verzeichnis und Teil der Content-URI; „../“ o.ä. nicht zulassen
                    if (!SAFE_FILE_NAME.matches(file)) {
                        AppLog.w("Engine", "Engine-App ${ai.packageName}: ungültiger Dateiname „$file“")
                        continue
                    }
                    val targets = parser.getAttributeValue(null, "target")?.split('|')?.map { it.trim() } ?: continue
                    if (targets.none { it in abis }) continue
                    if (result.none { it.packageName == ai.packageName && it.fileName == file }) {
                        result.add(OexEngine(name, ai.packageName, authority, file))
                    }
                }
                parser.close()
            } catch (e: Exception) {
                // fehlerhafte Engine-App überspringen
                AppLog.w("Engine", "Engine-App ${ai.packageName} übersprungen", e)
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    /** Kopiert die Engine bei Bedarf und liefert den Startbefehl. */
    fun command(ctx: Context, e: OexEngine): List<String> {
        val stamp = ctx.packageManager.getPackageInfo(e.packageName, 0).lastUpdateTime
        val dir = File(ctx.filesDir, "oex/${e.packageName}")
        val exe = File(dir, e.fileName)
        val marker = File(dir, "${e.fileName}.stamp")
        if (!exe.exists() || marker.takeIf { it.exists() }?.readText() != stamp.toString()) {
            dir.mkdirs()
            exe.setWritable(true)
            val tmp = File(dir, "${e.fileName}.tmp")
            val uri = "content://${e.authority}/${e.fileName}".toUri()
            (ctx.contentResolver.openInputStream(uri) ?: throw LocalizedIOException(R.string.err_engine_unreadable, e.name)).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(exe)) throw LocalizedIOException(R.string.err_engine_copy, e.name)
            exe.setReadOnly()
            marker.writeText(stamp.toString())
        }
        return listOf(linkerFor(exe), exe.absolutePath)
    }

    /** 32- oder 64-Bit-Linker je nach ELF-Klasse der Datei. */
    private fun linkerFor(exe: File): String {
        val header = ByteArray(5)
        exe.inputStream().use { if (it.read(header) < 5) throw LocalizedIOException(R.string.err_not_engine, exe.name) }
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte()) throw LocalizedIOException(R.string.err_not_engine, exe.name)
        return if (header[4].toInt() == 2) "/system/bin/linker64" else "/system/bin/linker"
    }
}
