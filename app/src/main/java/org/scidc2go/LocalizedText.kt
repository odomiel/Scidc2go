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

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException

/**
 * Übersetzbarer Text: String-Ressource [res] mit den Platzhaltern [args]. So können auch Klassen ohne
 * Android-Context (Datenbanken, Engine) Meldungen liefern; Argumente vom Typ [Text] werden mitübersetzt.
 */
class Text(@StringRes val res: Int, vararg val args: Any?) {
    fun resolve(ctx: Context): String =
        ctx.getString(res, *args.map { if (it is Text) it.resolve(ctx) else it ?: "" }.toTypedArray())

    override fun toString() = "Text(0x${res.toString(16)}, ${args.toList()})"
}

/** E/A-Fehler mit übersetzbarer Meldung; die Oberfläche zeigt [text] in der eingestellten Sprache. */
open class LocalizedIOException(val text: Text, cause: Throwable? = null) : IOException(text.toString(), cause) {
    constructor(@StringRes res: Int, vararg args: Any?) : this(Text(res, *args))
}

/** Meldung eines Fehlers für die Oberfläche. */
fun Throwable.userMessage(ctx: Context): String =
    (this as? LocalizedIOException)?.text?.resolve(ctx) ?: message ?: toString()
