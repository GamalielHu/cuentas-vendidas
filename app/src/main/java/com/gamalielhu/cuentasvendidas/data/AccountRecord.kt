package com.gamalielhu.cuentasvendidas.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Fechas guardadas como epochDay (días desde 1970-01-01). */
@Entity(tableName = "cuentas")
data class AccountRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serviceName: String = "",
    val accountEmailOrUser: String = "",
    val password: String = "",
    val profile: String = "",
    val clientName: String = "",
    val clientPhone: String = "",
    val cardUsed: String = "",
    /** Vencimiento real: donde compras o renuevas la cuenta. */
    val supplierExpiryDate: Long? = null,
    /** Vencimiento de la venta al cliente. */
    val clientExpiryDate: Long? = null,
    val status: String = Status.ACTIVE.name,
    val notes: String = "",
    /** Fecha (epochDay) para la que ya se avisó; evita avisos repetidos. */
    val notifiedSupplierFor: Long? = null,
    val notifiedClientFor: Long? = null,
)

enum class Status(val etiqueta: String) {
    ACTIVE("Activa"),
    EXPIRING_SOON("Por vencer"),
    EXPIRED("Vencida"),
    RENEWED("Renovada"),
}

object Vencimientos {
    const val DIAS_POR_VENCER = 3

    fun hoy(): Long = LocalDate.now().toEpochDay()

    fun proxima(r: AccountRecord): Long? =
        listOfNotNull(r.supplierExpiryDate, r.clientExpiryDate).minOrNull()

    /** El estado sale de las fechas; "Renovada" se respeta mientras no esté por vencer. */
    fun estado(r: AccountRecord, hoy: Long = hoy()): Status {
        val dias = proxima(r)?.let { it - hoy }
        val renovada = r.status == Status.RENEWED.name
        return when {
            dias == null -> if (renovada) Status.RENEWED else Status.ACTIVE
            dias < 0 -> Status.EXPIRED
            dias <= DIAS_POR_VENCER -> Status.EXPIRING_SOON
            renovada -> Status.RENEWED
            else -> Status.ACTIVE
        }
    }
}
