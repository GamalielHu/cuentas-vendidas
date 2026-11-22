package com.gamalielhu.cuentasvendidas.excel

import androidx.room.withTransaction
import com.gamalielhu.cuentasvendidas.data.AccountRecord
import com.gamalielhu.cuentasvendidas.data.AppDatabase
import com.gamalielhu.cuentasvendidas.data.Fechas
import com.gamalielhu.cuentasvendidas.data.Status
import com.gamalielhu.cuentasvendidas.data.Vencimientos

data class ResultadoImportacion(val nuevas: Int, val actualizadas: Int, val omitidas: Int)

object Importador {

    /** Fila del Excel convertida a registro (sin id). */
    fun aRegistro(fila: List<String>, mapeo: Map<Campo, Int>): AccountRecord {
        fun v(c: Campo) = mapeo[c]?.let { fila.getOrNull(it) }?.trim().orEmpty()
        val estado = Mapeo.normalizar(v(Campo.ESTADO))
        return AccountRecord(
            serviceName = v(Campo.SERVICIO),
            accountEmailOrUser = v(Campo.CUENTA),
            password = v(Campo.CONTRASENA),
            profile = v(Campo.PERFIL),
            clientName = v(Campo.CLIENTE),
            clientPhone = v(Campo.TELEFONO),
            cardUsed = v(Campo.TARJETA),
            supplierExpiryDate = Fechas.leer(v(Campo.VENCE_PROVEEDOR)),
            clientExpiryDate = Fechas.leer(v(Campo.VENCE_CLIENTE)),
            status = if (estado.contains("renov")) Status.RENEWED.name else "",
            notes = v(Campo.NOTAS),
        )
    }

    /** Misma cuenta = mismo servicio + cuenta + perfil (sin cuenta: servicio + cliente). */
    private fun clave(r: AccountRecord): String {
        val n = Mapeo::normalizar
        return if (r.accountEmailOrUser.isBlank()) "s|${n(r.serviceName)}|c|${n(r.clientName)}"
        else "${n(r.serviceName)}|${n(r.accountEmailOrUser)}|${n(r.profile)}"
    }

    suspend fun importar(
        db: AppDatabase,
        filas: List<List<String>>,
        filaEncabezado: Int,
        mapeo: Map<Campo, Int>,
    ): ResultadoImportacion {
        val dao = db.dao()
        val encabezado = filas.getOrNull(filaEncabezado).orEmpty()
        val nuevosDatos = filas.drop(filaEncabezado + 1)
            .filterNot { f -> f.map(Mapeo::normalizar) == encabezado.map(Mapeo::normalizar) } // encabezados repetidos
            .map { aRegistro(it, mapeo) }
        val omitidas = nuevosDatos.count { it.accountEmailOrUser.isBlank() && it.clientName.isBlank() }
        val validos = nuevosDatos.filterNot { it.accountEmailOrUser.isBlank() && it.clientName.isBlank() }
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
        return ResultadoImportacion(nuevas, actualizadas, omitidas)
    }
}
