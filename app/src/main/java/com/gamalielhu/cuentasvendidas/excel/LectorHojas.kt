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

data class Hoja(val nombre: String, val filas: List<List<String>>)

/** Lee todas las hojas con datos de un .xlsx, .xls o .csv como tablas de textos. */
object LectorHojas {
    private const val MAX_COLUMNAS = 100

    fun leer(ctx: Context, uri: Uri, nombreArchivo: String): List<Hoja> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("No se pudo abrir el archivo")
        return leer(bytes, nombreArchivo)
    }

    fun leer(bytes: ByteArray, nombreArchivo: String): List<Hoja> {
        val hojas = when {
            bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> leerXlsx(bytes)
            bytes.size > 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() -> leerXls(bytes)
            else -> listOf(Hoja(nombreArchivo.substringBeforeLast('.'), leerCsv(bytes)))
        }
        return hojas.map { h ->
            Hoja(h.nombre.trim(), h.filas.map { f -> f.map { it.trim() } }.filter { f -> f.any { it.isNotEmpty() } })
        }.filter { it.filas.isNotEmpty() }
    }

    // ---------- .xlsx (ZIP con XML) ----------

    private fun leerXlsx(bytes: ByteArray): List<Hoja> {
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
        val hojas = hojasDelLibro(archivos).filter { it.second in archivos }.ifEmpty {
            archivos.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.sorted()
                .map { it.substringAfterLast('/').removeSuffix(".xml") to it }
        }
        if (hojas.isEmpty()) throw IllegalArgumentException("El Excel no tiene hojas")
        return hojas.map { (nombre, ruta) -> Hoja(nombre, leerHoja(archivos.getValue(ruta), compartidas)) }
    }

    private fun parser(b: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(ByteArrayInputStream(b), null) }

    private val reRelacion = Regex("<(?:\\w+:)?Relationship\\b([^>]*)>")
    private val reHoja = Regex("<(?:\\w+:)?sheet\\b([^>]*)>")
    private val reAtributo = Regex("([\\w:]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    private fun atributos(texto: String): Map<String, String> =
        reAtributo.findAll(texto).associate { m ->
            m.groupValues[1] to xmlTexto(m.groupValues[3].ifEmpty { m.groupValues[4] })
        }

    private fun xmlTexto(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    /** (nombre, ruta) de cada hoja en el orden del libro. Con regex: no depende del parser XML del celular. */
    private fun hojasDelLibro(archivos: Map<String, ByteArray>): List<Pair<String, String>> {
        val libro = archivos["xl/workbook.xml"]?.toString(Charsets.UTF_8) ?: return emptyList()
        val rels = archivos["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8) ?: return emptyList()
        val destinos = reRelacion.findAll(rels).mapNotNull { m ->
            val a = atributos(m.groupValues[1])
            val id = a["Id"] ?: return@mapNotNull null
            val destino = a["Target"] ?: return@mapNotNull null
            id to (if (destino.startsWith("/")) destino.trimStart('/') else "xl/" + destino.removePrefix("./"))
        }.toMap()
        return reHoja.findAll(libro).mapNotNull { m ->
            val a = atributos(m.groupValues[1])
            val id = a.entries.firstOrNull { it.key.endsWith(":id") }?.value ?: return@mapNotNull null
            val nombre = a["name"] ?: return@mapNotNull null
            destinos[id]?.let { nombre to it }
        }.toList()
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

    private fun leerXls(bytes: ByteArray): List<Hoja> {
        val ajustes = WorkbookSettings()
        ajustes.setEncoding("Cp1252")
        ajustes.setSuppressWarnings(true)
        ajustes.setGCDisabled(true)
        val libro = Workbook.getWorkbook(ByteArrayInputStream(bytes), ajustes)
        try {
            return libro.sheets.map { hoja ->
                Hoja(hoja.name, (0 until hoja.rows).map { r -> hoja.getRow(r).take(MAX_COLUMNAS).map(::celdaXls) })
            }
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
