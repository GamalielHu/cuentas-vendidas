package com.gamalielhu.cuentasvendidas.excel

import android.content.Context
import android.net.Uri
import android.util.Xml
import jxl.Cell
import jxl.DateCell
import jxl.NumberCell
import jxl.Workbook
import jxl.WorkbookSettings
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.floor

/** Lee la primera hoja de un .xlsx, .xls o .csv como tabla de textos. */
object LectorHojas {
    private const val MAX_COLUMNAS = 100

    fun leer(ctx: Context, uri: Uri): List<List<String>> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("No se pudo abrir el archivo")
        return leer(bytes)
    }

    fun leer(bytes: ByteArray): List<List<String>> {
        val filas = when {
            bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> leerXlsx(bytes)
            bytes.size > 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() -> leerXls(bytes)
            else -> leerCsv(bytes)
        }
        return filas.map { f -> f.map { it.trim() } }.filter { f -> f.any { it.isNotEmpty() } }
    }

    // ---------- .xlsx (ZIP con XML) ----------

    private fun leerXlsx(bytes: ByteArray): List<List<String>> {
        val archivos = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name.trimStart('/')
                if (!e.isDirectory && (n.endsWith(".xml") || n.endsWith(".rels"))) archivos[n] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        val compartidas = archivos["xl/sharedStrings.xml"]?.let(::leerCompartidas) ?: emptyList()
        val hoja = rutaPrimeraHoja(archivos)?.takeIf { it in archivos }
            ?: archivos.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
            ?: throw IllegalArgumentException("El Excel no tiene hojas")
        return leerHoja(archivos.getValue(hoja), compartidas)
    }

    private fun parser(b: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(ByteArrayInputStream(b), null) }

    private fun rutaPrimeraHoja(archivos: Map<String, ByteArray>): String? {
        val libro = archivos["xl/workbook.xml"] ?: return null
        val rels = archivos["xl/_rels/workbook.xml.rels"] ?: return null
        var idHoja: String? = null
        val p = parser(libro)
        while (idHoja == null && p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == "sheet") {
                idHoja = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).endsWith(":id") }
                    ?.let { p.getAttributeValue(it) }
            }
        }
        idHoja ?: return null
        val r = parser(rels)
        while (r.next() != XmlPullParser.END_DOCUMENT) {
            if (r.eventType == XmlPullParser.START_TAG && r.name.substringAfter(':') == "Relationship" &&
                r.getAttributeValue(null, "Id") == idHoja
            ) {
                val destino = r.getAttributeValue(null, "Target") ?: return null
                return if (destino.startsWith("/")) destino.trimStart('/') else "xl/$destino"
            }
        }
        return null
    }

    private fun leerCompartidas(b: ByteArray): List<String> {
        val p = parser(b)
        val lista = ArrayList<String>()
        var sb: StringBuilder? = null
        var enT = false
        var enFonetica = false
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "si" -> sb = StringBuilder()
                    "t" -> enT = true
                    "rPh" -> enFonetica = true
                }
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "si" -> { lista.add(sb?.toString() ?: ""); sb = null }
                    "t" -> enT = false
                    "rPh" -> enFonetica = false
                }
                XmlPullParser.TEXT -> if (enT && !enFonetica) sb?.append(p.text)
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return lista
    }

    private fun leerHoja(b: ByteArray, compartidas: List<String>): List<List<String>> {
        val p = parser(b)
        val filas = ArrayList<List<String>>()
        var fila: HashMap<Int, String>? = null
        var col = 0
        var siguiente = 0
        var tipo: String? = null
        val valor = StringBuilder()
        var leyendo = false
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "row" -> { fila = HashMap(); siguiente = 0 }
                    "c" -> {
                        col = p.getAttributeValue(null, "r")?.let(::columna) ?: siguiente
                        tipo = p.getAttributeValue(null, "t")
                        valor.setLength(0)
                    }
                    "v", "t" -> leyendo = true
                }
                XmlPullParser.TEXT -> if (leyendo) valor.append(p.text)
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "v", "t" -> leyendo = false
                    "c" -> {
                        val v = valor.toString()
                        val texto = when (tipo) {
                            "s" -> v.trim().toIntOrNull()?.let { compartidas.getOrNull(it) } ?: ""
                            "b" -> if (v.trim() == "1") "VERDADERO" else "FALSO"
                            "inlineStr", "str", "e" -> v
                            else -> numero(v)
                        }
                        if (col < MAX_COLUMNAS) fila?.put(col, texto)
                        siguiente = col + 1
                    }
                    "row" -> {
                        fila?.let { m ->
                            val max = m.keys.maxOrNull() ?: -1
                            filas.add((0..max).map { m[it] ?: "" })
                        }
                        fila = null
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return filas
    }

    /** "AB12" -> 27 */
    private fun columna(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c !in 'A'..'Z') break
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    /** "7.1234567E7" -> "71234567", "45" -> "45", "12.5" -> "12.5". */
    fun numero(s: String): String {
        val d = s.trim().toDoubleOrNull() ?: return s
        return if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else s.trim()
    }

    // ---------- .xls (Excel 97-2003) ----------

    private fun leerXls(bytes: ByteArray): List<List<String>> {
        val ajustes = WorkbookSettings()
        ajustes.setEncoding("Cp1252")
        ajustes.setSuppressWarnings(true)
        ajustes.setGCDisabled(true)
        val libro = Workbook.getWorkbook(ByteArrayInputStream(bytes), ajustes)
        try {
            val hoja = libro.sheets.firstOrNull { it.rows > 0 } ?: return emptyList()
            return (0 until hoja.rows).map { r -> hoja.getRow(r).take(MAX_COLUMNAS).map(::celdaXls) }
        } finally {
            libro.close()
        }
    }

    private fun celdaXls(c: Cell): String = when (c) {
        is DateCell -> c.date?.let { Instant.ofEpochMilli(it.time).atZone(ZoneOffset.UTC).toLocalDate().toString() }
            ?: c.contents.orEmpty()
        is NumberCell -> numero(c.value.toString())
        else -> c.contents.orEmpty()
    }

    // ---------- .csv ----------

    private fun leerCsv(bytes: ByteArray): List<List<String>> {
        var texto = String(bytes, Charsets.UTF_8)
        if (texto.count { it == '�' } > 0) texto = String(bytes, Charset.forName("windows-1252"))
        texto = texto.removePrefix("﻿")
        val primera = texto.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(';', ',', '\t').maxByOrNull { s -> primera.count { it == s } } ?: ';'
        val filas = ArrayList<List<String>>()
        var fila = ArrayList<String>()
        val campo = StringBuilder()
        var comillas = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            when {
                comillas && c == '"' && texto.getOrNull(i + 1) == '"' -> { campo.append('"'); i++ }
                c == '"' -> comillas = !comillas
                !comillas && c == sep -> { fila.add(campo.toString()); campo.setLength(0) }
                !comillas && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && texto.getOrNull(i + 1) == '\n') i++
                    fila.add(campo.toString()); campo.setLength(0)
                    filas.add(fila); fila = ArrayList()
                }
                else -> campo.append(c)
            }
            i++
        }
        if (campo.isNotEmpty() || fila.isNotEmpty()) { fila.add(campo.toString()); filas.add(fila) }
        return filas
    }
}
