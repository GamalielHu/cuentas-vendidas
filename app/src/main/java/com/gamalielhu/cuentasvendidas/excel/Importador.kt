package com.gamalielhu.cuentasvendidas.excel

import androidx.room.withTransaction
import com.gamalielhu.cuentasvendidas.data.AccountRecord
import com.gamalielhu.cuentasvendidas.data.AppDatabase
import com.gamalielhu.cuentasvendidas.data.Fechas
import com.gamalielhu.cuentasvendidas.data.Status
import com.gamalielhu.cuentasvendidas.data.Vencimientos

data class ResultadoImportacion(val nuevas: Int, val actualizadas: Int, val sinVender: Int, val omitidas: Int)

/** Una hoja a importar con su mapeo; `servicio` es el nombre de la hoja (NETFLIX, IPTV…). */
data class HojaAImportar(val servicio: String, val filas: List<List<String>>, val filaEncabezado: Int, val mapeo: Map<Campo, Int>)

object Filas {
    /** Fila del Excel convertida a registro (sin id). */
    fun aRegistro(fila: List<String>, mapeo: Map<Campo, Int>, servicioHoja: String = ""): AccountRecord {
        fun v(c: Campo) = mapeo[c]?.let { fila.getOrNull(it) }?.trim().orEmpty()
        val estado = Mapeo.normalizar(v(Campo.ESTADO))
        // "1", "4" en la columna nombre son n.º de fila; un "celular" sin dígitos es en realidad un nombre.
        var cliente = v(Campo.CLIENTE).takeUnless { n -> n.all(Char::isDigit) }.orEmpty()
        var telefono = v(Campo.TELEFONO).removePrefix("'")
        if (telefono.isNotEmpty() && telefono.none(Char::isDigit)) {
            if (cliente.isEmpty()) cliente = telefono
            telefono = ""
        }
        return AccountRecord(
            serviceName = v(Campo.SERVICIO).ifBlank { servicioHoja },
            accountEmailOrUser = v(Campo.CUENTA),
            password = v(Campo.CONTRASENA),
            profile = v(Campo.PERFIL),
            pin = v(Campo.PIN),
            clientName = cliente,
            clientPhone = telefono,
            cardUsed = v(Campo.TARJETA),
            supplierName = v(Campo.PROVEEDOR),
            supplierExpiryDate = Fechas.leer(v(Campo.VENCE_PROVEEDOR)),
            clientExpiryDate = Fechas.leer(v(Campo.VENCE_CLIENTE)),
            status = if (estado.contains("renov")) Status.RENEWED.name else "",
            notes = v(Campo.NOTAS),
        )
    }

    /** Fila sin cuenta ni cliente: se ignora. */
    fun vacia(r: AccountRecord) = r.accountEmailOrUser.isBlank() && r.clientName.isBlank()

    /** Cuenta/perfil libre: sin cliente, sin teléfono y sin fechas. */
    fun sinVender(r: AccountRecord) = r.clientName.isBlank() && r.clientPhone.isBlank() &&
        r.supplierExpiryDate == null && r.clientExpiryDate == null

    /** Correo comparable: minúsculas; en Gmail los puntos no cuentan. */
    fun claveCorreo(c: String): String {
        val e = c.trim().lowercase()
        val usuario = e.substringBefore('@')
        val dominio = e.substringAfter('@', "")
        return if (dominio == "gmail.com") usuario.replace(".", "") + "@" + dominio else e
    }

    /** Hoja que solo asigna tarjetas a correos (como TARJETAS USADAS). */
    fun esSoloTarjetas(mapeo: Map<Campo, Int>) = Campo.CUENTA in mapeo && Campo.TARJETA in mapeo &&
        listOf(Campo.CLIENTE, Campo.TELEFONO, Campo.VENCE_CLIENTE, Campo.VENCE_PROVEEDOR).none { it in mapeo }

    fun datos(h: HojaAImportar): List<List<String>> {
        val enc = h.filas.getOrNull(h.filaEncabezado)?.map(Mapeo::normalizar)
        return h.filas.drop(h.filaEncabezado + 1).filterNot { f -> enc != null && f.map(Mapeo::normalizar) == enc }
    }
}

object Importador {

    /** Misma cuenta = mismo servicio + cuenta + perfil (sin cuenta: servicio + cliente). */
    private fun clave(r: AccountRecord): String {
        val n = Mapeo::normalizar
        return if (r.accountEmailOrUser.isBlank()) "s|${n(r.serviceName)}|c|${n(r.clientName)}"
        else "${n(r.serviceName)}|${n(r.accountEmailOrUser)}|${n(r.profile)}"
    }

    suspend fun importar(
        db: AppDatabase,
        hojas: List<HojaAImportar>,
        tarjetasPorCorreo: Map<String, String>,
    ): ResultadoImportacion {
        val dao = db.dao()
        val registros = hojas.flatMap { h -> Filas.datos(h).map { Filas.aRegistro(it, h.mapeo, h.servicio) } }
        val omitidas = registros.count(Filas::vacia)
        val sinVender = registros.count { !Filas.vacia(it) && Filas.sinVender(it) }
        val validos = registros.filterNot { Filas.vacia(it) || Filas.sinVender(it) }.map { r ->
            if (r.cardUsed.isNotBlank()) r
            else r.copy(cardUsed = tarjetasPorCorreo[Filas.claveCorreo(r.accountEmailOrUser)].orEmpty())
        }
        // Si una cuenta aparece varias veces en el Excel (perfiles vendidos a varios clientes), se distingue por cliente.
        val repetidas = validos.groupingBy(::clave).eachCount()
        var nuevas = 0
        var actualizadas = 0
        db.withTransaction {
            val existentes = dao.todas().toMutableList()
            val tocados = HashSet<Long>()
            for (p in validos) {
                val k = clave(p)
                val cliente = Mapeo.normalizar(p.clientName)
                val candidatos = existentes.filter { clave(it) == k && it.id !in tocados }
                val previo = if ((repetidas[k] ?: 0) > 1) {
                    candidatos.firstOrNull { Mapeo.normalizar(it.clientName) == cliente }
                } else {
                    candidatos.firstOrNull { Mapeo.normalizar(it.clientName) == cliente } ?: candidatos.firstOrNull()
                }
                val combinado = if (previo == null) p.copy(status = p.status.ifEmpty { Status.ACTIVE.name })
                else previo.copy(
                    serviceName = p.serviceName.ifBlank { previo.serviceName },
                    accountEmailOrUser = p.accountEmailOrUser.ifBlank { previo.accountEmailOrUser },
                    password = p.password.ifBlank { previo.password },
                    profile = p.profile.ifBlank { previo.profile },
                    clientName = p.clientName.ifBlank { previo.clientName },
                    clientPhone = p.clientPhone.ifBlank { previo.clientPhone },
                    cardUsed = p.cardUsed.ifBlank { previo.cardUsed },
                    pin = p.pin.ifBlank { previo.pin },
                    supplierName = p.supplierName.ifBlank { previo.supplierName },
                    supplierExpiryDate = p.supplierExpiryDate ?: previo.supplierExpiryDate,
                    clientExpiryDate = p.clientExpiryDate ?: previo.clientExpiryDate,
                    status = p.status.ifEmpty { previo.status },
                    notes = p.notes.ifBlank { previo.notes },
                )
                val final = combinado.copy(status = Vencimientos.estado(combinado).name)
                val id = dao.guardar(final)
                val guardado = if (final.id != 0L) final else final.copy(id = id)
                if (previo == null) { nuevas++; existentes += guardado } else {
                    actualizadas++
                    existentes[existentes.indexOfFirst { it.id == previo.id }] = guardado
                }
                tocados += guardado.id
            }
        }
        return ResultadoImportacion(nuevas, actualizadas, sinVender, omitidas)
    }

    /** Pone la tarjeta a todas las cuentas con ese correo. Devuelve cuántas cuentas cambió. */
    suspend fun asignarTarjetas(db: AppDatabase, tarjetasPorCorreo: Map<String, String>): Int {
        val dao = db.dao()
        var n = 0
        db.withTransaction {
            for (r in dao.todas()) {
                val t = tarjetasPorCorreo[Filas.claveCorreo(r.accountEmailOrUser)] ?: continue
                if (t != r.cardUsed) { dao.guardar(r.copy(cardUsed = t)); n++ }
            }
        }
        return n
    }
}
