package com.gamalielhu.cuentasvendidas.excel

import com.gamalielhu.cuentasvendidas.data.Fechas
import java.text.Normalizer

enum class Campo(val etiqueta: String, val esFecha: Boolean = false) {
    SERVICIO("Servicio"),
    CUENTA("Cuenta / correo"),
    CONTRASENA("Contraseña"),
    PERFIL("Perfil / pantalla"),
    CLIENTE("Cliente"),
    TELEFONO("Teléfono cliente"),
    TARJETA("Tarjeta usada"),
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

    fun puntaje(campo: Campo, h: String): Int {
        if (h.isEmpty()) return 0
        val fecha = tiene(h, "venc", "fecha", "expira", "expir", "caduc", "corte", "renov", "hasta", "fin", "expiry", "due", "date", "termina")
        val fuerte = tiene(h, "venc", "expira", "expir", "caduc", "corte", "hasta", "fin", "expiry", "due", "termina")
        // "Fecha venta", "Fecha de compra", "Fecha de pago" son el día de la operación, no el vencimiento.
        val inicio = tiene(h, "inicio", "alta", "desde", "start", "activacion", "registro") ||
            (!fuerte && tiene(h, "venta", "compra", "pago", "sale", "purchase"))
        val prov = tiene(h, "proveedor", "prov", "real", "compra", "supplier", "renov", "origen", "mayorista", "cuenta", "pago")
        val cli = tiene(h, "cliente", "client", "venta", "customer", "comprador", "vendid")
        return when (campo) {
            Campo.SERVICIO -> if (tiene(h, "servicio", "plataforma", "streaming", "producto", "service", "platform", "app", "suscripcion")) 3 else 0
            Campo.CUENTA -> when {
                fecha || tiene(h, *palabrasClave) -> 0
                tiene(h, "correo", "email", "e mail", "mail", "gmail", "usuario", "user", "login", "account") -> 4
                tiene(h, "cuenta") -> 3
                else -> 0
            }
            Campo.CONTRASENA -> if (tiene(h, *palabrasClave)) 3 else 0
            Campo.PERFIL -> if (tiene(h, "perfil", "profile", "pantalla", "screen")) 3 else 0
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
            Campo.TARJETA -> if (!fecha && tiene(h, *palabrasTarjeta)) 3 else 0
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

    /** La fila (de las 10 primeras) que más parece encabezado. */
    fun detectarEncabezado(filas: List<List<String>>): Int =
        (0 until minOf(10, filas.size)).maxByOrNull { i ->
            val h = filas[i].map(::normalizar)
            Campo.entries.sumOf { c -> h.maxOfOrNull { puntaje(c, it) } ?: 0 }
        } ?: 0

    /** Proporción de valores de la columna que parecen fecha (muestra de 30 filas). */
    private fun proporcionFechas(filas: List<List<String>>, desde: Int, col: Int): Double? {
        val valores = filas.drop(desde).take(30).mapNotNull { it.getOrNull(col)?.takeIf(String::isNotBlank) }
        if (valores.isEmpty()) return null
        return valores.count { v -> Fechas.leer(v) != null && v.toDoubleOrNull().let { it == null || it in 20000.0..80000.0 } }.toDouble() / valores.size
    }

    /**
     * Columna para cada campo. Primero lo que el usuario eligió la vez anterior (por nombre
     * de encabezado), luego la detección automática.
     */
    fun detectar(filas: List<List<String>>, filaEncabezado: Int, guardado: Map<Campo, String>): Map<Campo, Int> {
        val enc = filas.getOrNull(filaEncabezado).orEmpty().map(::normalizar)
        val resultado = HashMap<Campo, Int>()
        val usadas = HashSet<Int>()
        for ((campo, nombre) in guardado) {
            val i = enc.indexOf(nombre)
            if (nombre.isNotEmpty() && i >= 0 && i !in usadas) { resultado[campo] = i; usadas += i }
        }
        val fechas = enc.indices.associateWith { proporcionFechas(filas, filaEncabezado + 1, it) }
        val candidatos = ArrayList<Triple<Int, Campo, Int>>()
        for (c in Campo.entries) {
            if (c in resultado) continue
            enc.forEachIndexed { i, h ->
                var p = puntaje(c, h)
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
        if (Campo.VENCE_PROVEEDOR !in resultado) {
            val otra = enc.indices.firstOrNull { i ->
                i !in usadas && (fechas[i] ?: 0.0) >= 0.5 && puntaje(Campo.VENCE_CLIENTE, enc[i]) > 0
            }
            val cli = resultado[Campo.VENCE_CLIENTE]
            if (otra != null) {
                if (cli != null && puntaje(Campo.VENCE_CLIENTE, enc[cli]) <= 2) {
                    resultado[Campo.VENCE_PROVEEDOR] = minOf(cli, otra)
                    resultado[Campo.VENCE_CLIENTE] = maxOf(cli, otra)
                } else {
                    resultado[Campo.VENCE_PROVEEDOR] = otra
                }
                usadas += otra
            }
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
