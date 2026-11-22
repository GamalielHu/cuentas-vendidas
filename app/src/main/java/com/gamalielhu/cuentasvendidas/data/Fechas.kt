package com.gamalielhu.cuentasvendidas.data

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Fechas {
    private val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val iso = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})")
    private val dma = Regex("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})")
    private val texto = Regex("^(\\d{1,2})\\s*(?:de\\s+)?([a-z]{3,})\\.?\\s*(?:de\\s+|del\\s+)?(\\d{2,4})")
    private val sinTildes = Regex("\\p{Mn}+")
    private val meses = mapOf(
        "ene" to 1, "jan" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "apr" to 4, "may" to 5,
        "jun" to 6, "jul" to 7, "ago" to 8, "aug" to 8, "sep" to 9, "set" to 9, "oct" to 10,
        "nov" to 11, "dic" to 12, "dec" to 12,
    )

    fun formato(epochDay: Long?): String? = epochDay?.let { LocalDate.ofEpochDay(it).format(formato) }

    /** Lee una fecha de Excel: número de serie, ISO, dd/mm/aaaa (día primero) o "15 ene 2026". */
    fun leer(valor: String): Long? {
        val s = Normalizer.normalize(valor.trim().lowercase(), Normalizer.Form.NFD).replace(sinTildes, "")
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { d ->
            return if (d in 20000.0..80000.0) LocalDate.of(1899, 12, 30).plusDays(d.toLong()).toEpochDay() else null
        }
        iso.find(s)?.let { m ->
            val (a, me, d) = m.destructured
            return crear(a.toInt(), me.toInt(), d.toInt())
        }
        dma.find(s)?.let { m ->
            var d = m.groupValues[1].toInt()
            var me = m.groupValues[2].toInt()
            var a = m.groupValues[3].toInt()
            if (a < 100) a += 2000
            if (me > 12 && d <= 12) { val t = d; d = me; me = t }
            return crear(a, me, d)
        }
        texto.find(s)?.let { m ->
            val me = meses[m.groupValues[2].take(3)] ?: return null
            var a = m.groupValues[3].toInt()
            if (a < 100) a += 2000
            return crear(a, me, m.groupValues[1].toInt())
        }
        return null
    }

    private fun crear(a: Int, m: Int, d: Int): Long? =
        runCatching { LocalDate.of(a, m, d).toEpochDay() }.getOrNull()
}
