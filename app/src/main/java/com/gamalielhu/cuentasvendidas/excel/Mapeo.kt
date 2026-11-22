package com.gamalielhu.cuentasvendidas.excel

import com.gamalielhu.cuentasvendidas.data.Fechas
import java.text.Normalizer

enum class Campo(val etiqueta: String, val esFecha: Boolean = false) {
    SERVICIO("Servicio"),
    CUENTA("Cuenta / correo"),
    CONTRASENA("Contraseña"),
    PERFIL("Perfil / pantalla"),
    PIN("PIN del perfil"),
    CLIENTE("Cliente"),
    TELEFONO("Teléfono cliente"),
    TARJETA("Tarjeta usada"),
    PROVEEDOR("Proveedor"),
    VENCE_PROVEEDOR("Vence proveedor (real)", esFecha = true),
    VENCE_CLIENTE("Vence cliente", esFecha = true),
    ESTADO("Estado"),
    NOTAS("Notas"),
}

/** Detección automática de columnas por nombre (español/inglés) y por contenido. */
object Mapeo {
    private val sinTildes = Regex("\\p{Mn}+")
    private val noAlfanum = Regex("[^a-z0-9]+")

    fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(sinTildes, "").replace(noAlfanum, " ").trim()

    /** ¿Alguna palabra del encabezado empieza con alguno de los prefijos? */
    private fun tiene(h: String, vararg prefijos: String): Boolean {
        val conEspacios = " $h "
        return prefijos.any { conEspacios.contains(" $it") }
    }

    private val palabrasClave = arrayOf("contrasena", "clave", "password", "pass", "pwd", "contra")
    private val palabrasTelefono = arrayOf("telefono", "celular", "cel", "whatsapp", "wsp", "wpp", "phone", "movil", "tel", "contacto")
    private val palabrasTarjeta = arrayOf("tarjeta", "card", "pago", "banco", "metodo", "medio")

    /** @param seccionProveedor la columna está a la derecha de una columna "Proveedor" (datos de la cuenta real). */
    fun puntaje(campo: Campo, h: String, seccionProveedor: Boolean = false): Int {
        if (h.isEmpty()) return 0
        val fecha = tiene(h, "venc", "fecha", "expira", "expir", "caduc", "corte", "renov", "hasta", "fin", "expiry", "due", "date", "termina")
        val fuerte = tiene(h, "venc", "expira", "expir", "caduc", "corte", "hasta", "fin", "expiry", "due", "termina")
        // "Fecha venta", "Fecha de compra", "Fecha de pago" son el día de la operación, no el vencimiento.
        val inicio = tiene(h, "inicio", "alta", "desde", "start", "activacion", "registro") ||
            (!fuerte && tiene(h, "venta", "compra", "pago", "sale", "purchase"))
        val prov = seccionProveedor ||
            tiene(h, "proveedor", "prov", "real", "compra", "supplier", "renov", "origen", "mayorista", "cuenta", "pago")
        val cli = !seccionProveedor && tiene(h, "cliente", "client", "venta", "customer", "comprador", "vendid")
        return when (campo) {
            Campo.SERVICIO -> if (tiene(h, "servicio", "plataforma", "streaming", "producto", "service", "platform", "app", "suscripcion")) 3 else 0
            Campo.CUENTA -> when {
                fecha || tiene(h, *palabrasClave) -> 0
                tiene(h, "correo", "email", "e mail", "mail", "gmail", "usuario", "user", "login", "account") -> 4
                tiene(h, "cuenta") -> 3
                else -> 0
            }
            Campo.CONTRASENA -> if (tiene(h, *palabrasClave)) 3 else 0
            Campo.PERFIL -> if (tiene(h, "perfil", "profile", "pantalla", "screen") && !tiene(h, "perfiles")) 3 else 0
            Campo.PIN -> if (tiene(h, "pin")) 3 else 0
            Campo.PROVEEDOR -> if (!fecha && tiene(h, "proveedor", "supplier", "mayorista", "distribuidor")) 3 else 0
            Campo.CLIENTE -> when {
                fecha || tiene(h, *palabrasTelefono) -> 0
                cli -> 3
                tiene(h, "nombre", "name") -> 2
                else -> 0
            }
            Campo.TELEFONO -> when {
                tiene(h, *palabrasTarjeta) -> 0
                tiene(h, *palabrasTelefono) -> 3
                tiene(h, "numero", "nro") -> 1
                else -> 0
            }
            Campo.TARJETA -> if (!fecha && !seccionProveedor && tiene(h, *palabrasTarjeta)) 3 else 0
            Campo.VENCE_PROVEEDOR -> when {
                inicio -> 0
                fecha && prov && !cli -> 4
                else -> 0
            }
            Campo.VENCE_CLIENTE -> when {
                inicio -> 0
                fecha && cli -> 4
                fecha && !prov -> 2
                else -> 0
            }
            Campo.ESTADO -> if (tiene(h, "estado", "status", "situacion")) 3 else 0
            Campo.NOTAS -> if (tiene(h, "nota", "observ", "obs", "coment", "detalle", "notes", "comment")) 3 else 0
        }
    }

    private fun puntajeFila(fila: List<String>): Int {
        if (fila.any(::esCorreo)) return 0 // una fila con correos son datos, no títulos
        val h = fila.map(::normalizar)
        return Campo.entries.sumOf { c -> h.maxOfOrNull { puntaje(c, it) } ?: 0 }
    }

    /** La fila (de las 15 primeras) que más parece encabezado; -1 si la hoja no tiene encabezados. */
    fun detectarEncabezado(filas: List<List<String>>): Int {
        val mejor = (0 until minOf(15, filas.size)).maxByOrNull { puntajeFila(filas[it]) } ?: return -1
        return if (puntajeFila(filas[mejor]) >= 4) mejor else -1
    }

    /** Clave estable de una columna: encabezado + n.º de repetición ("expiracion#1"), o la letra si no hay título. */
    fun claveColumna(enc: List<String>, i: Int): String {
        val h = enc.getOrNull(i).orEmpty()
        if (h.isEmpty()) return "col:${letra(i)}"
        return "$h#${enc.take(i).count { it == h }}"
    }

    fun indicePorClave(enc: List<String>, clave: String): Int? {
        if (clave.startsWith("col:")) {
            val i = clave.removePrefix("col:").fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
            return i.takeIf { it >= 0 && enc.getOrNull(it).isNullOrEmpty() }
        }
        return enc.indices.firstOrNull { claveColumna(enc, it) == clave }
    }

    private val palabrasBanco = arrayOf(
        "fortaleza", "bnb", "sol", "union", "mercantil", "bcp", "bisa", "economico", "ganadero", "fie", "prodem",
        "visa", "master", "tarjeta", "credito", "debito", "banco", "nacional", "pyme", "interbank", "bbva",
        "scotiabank", "yape", "tigo", "amex", "paypal", "binance",
    )

    private fun valores(filas: List<List<String>>, desde: Int, col: Int) =
        filas.drop(desde).take(60).mapNotNull { it.getOrNull(col)?.takeIf(String::isNotBlank) }

    private fun cuenta(filas: List<List<String>>, desde: Int, col: Int, prueba: (String) -> Boolean) =
        valores(filas, desde, col).count(prueba)

    private fun esCorreo(v: String) = v.contains('@') && v.substringAfter('@').contains('.')
    private fun esBanco(v: String) = !esCorreo(v) && tiene(normalizar(v), *palabrasBanco)

    /** Proporción de valores de la columna que parecen fecha (muestra de 30 filas). */
    private fun proporcionFechas(filas: List<List<String>>, desde: Int, col: Int): Double? {
        val valores = filas.drop(desde).take(60).mapNotNull { it.getOrNull(col)?.takeIf(String::isNotBlank) }
        if (valores.isEmpty()) return null
        return valores.count { v -> Fechas.leer(v) != null && v.toDoubleOrNull().let { it == null || it in 20000.0..80000.0 } }.toDouble() / valores.size
    }

    /**
     * Columna para cada campo. Primero las columnas preferidas (lo que el usuario eligió, por clave de
     * columna), luego la detección automática por encabezado y contenido.
     */
    fun detectar(
        filas: List<List<String>>,
        filaEncabezado: Int,
        preferidas: Map<Campo, String>,
        excluidas: Set<Campo> = emptySet(),
    ): Map<Campo, Int> {
        val enc = if (filaEncabezado < 0) emptyList() else filas.getOrNull(filaEncabezado).orEmpty().map(::normalizar)
        val desde = filaEncabezado + 1
        val ncol = maxOf(enc.size, filas.drop(desde).take(60).maxOfOrNull { it.size } ?: 0)
        val resultado = HashMap<Campo, Int>()
        val usadas = HashSet<Int>()
        for ((campo, clave) in preferidas) {
            if (campo in excluidas) continue
            val i = indicePorClave(enc, clave) ?: continue
            if (i !in usadas) { resultado[campo] = i; usadas += i }
        }
        val seccion = (0 until ncol).map { i -> enc.take(i).any { puntaje(Campo.PROVEEDOR, it) > 0 } }
        val fechas = (0 until ncol).associateWith { proporcionFechas(filas, desde, it) }
        val candidatos = ArrayList<Triple<Int, Campo, Int>>()
        for (c in Campo.entries) {
            if (c in resultado || c in excluidas) continue
            enc.forEachIndexed { i, h ->
                var p = puntaje(c, h, seccion[i])
                val pf = fechas[i]
                if (pf != null && c.esFecha && pf < 0.5) p = 0
                if (pf != null && !c.esFecha && c != Campo.NOTAS && pf >= 0.8) p = 0
                if (p > 0) candidatos += Triple(p, c, i)
            }
        }
        candidatos.sortWith(compareByDescending<Triple<Int, Campo, Int>> { it.first }.thenBy { it.third })
        for ((_, c, i) in candidatos) {
            if (c in resultado || i in usadas) continue
            resultado[c] = i; usadas += i
        }
        // Dos columnas de fecha sin nombre claro: la 1.ª es la del proveedor (real), la 2.ª la del cliente.
        if (Campo.VENCE_PROVEEDOR !in resultado && Campo.VENCE_PROVEEDOR !in excluidas && enc.isNotEmpty()) {
            val otra = enc.indices.firstOrNull { i ->
                i !in usadas && (fechas[i] ?: 0.0) >= 0.5 && puntaje(Campo.VENCE_CLIENTE, enc[i], seccion[i]) > 0
            }
            val cli = resultado[Campo.VENCE_CLIENTE]
            if (otra != null) {
                if (cli != null && puntaje(Campo.VENCE_CLIENTE, enc[cli], seccion[cli]) <= 2) {
                    resultado[Campo.VENCE_PROVEEDOR] = minOf(cli, otra)
                    resultado[Campo.VENCE_CLIENTE] = maxOf(cli, otra)
                } else {
                    resultado[Campo.VENCE_PROVEEDOR] = otra
                }
                usadas += otra
            }
        }
        // Sin títulos (o sin coincidencias): reconocer por contenido correos, tarjetas/bancos y fechas.
        fun porContenido(campo: Campo, prueba: (String) -> Boolean) {
            if (campo in resultado || campo in excluidas) return
            val mejor = (0 until ncol).filter { it !in usadas }
                .map { it to cuenta(filas, desde, it, prueba) }
                .filter { (i, n) -> n >= 2 && n * 2 >= valores(filas, desde, i).size }
                .maxByOrNull { it.second }?.first ?: return
            resultado[campo] = mejor; usadas += mejor
        }
        porContenido(Campo.CUENTA, this::esCorreo)
        porContenido(Campo.TARJETA, this::esBanco)
        if (enc.isEmpty()) {
            porContenido(Campo.VENCE_PROVEEDOR) { Fechas.leer(it) != null }
            porContenido(Campo.VENCE_CLIENTE) { Fechas.leer(it) != null }
        }
        return resultado
    }

    fun letra(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }
}
