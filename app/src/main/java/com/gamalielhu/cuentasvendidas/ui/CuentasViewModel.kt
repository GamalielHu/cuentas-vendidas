package com.gamalielhu.cuentasvendidas.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamalielhu.cuentasvendidas.data.AccountRecord
import com.gamalielhu.cuentasvendidas.data.AppDatabase
import com.gamalielhu.cuentasvendidas.data.Status
import com.gamalielhu.cuentasvendidas.data.Vencimientos
import com.gamalielhu.cuentasvendidas.excel.Campo
import com.gamalielhu.cuentasvendidas.excel.Importador
import com.gamalielhu.cuentasvendidas.excel.LectorHojas
import com.gamalielhu.cuentasvendidas.excel.Mapeo
import com.gamalielhu.cuentasvendidas.workers.RevisarVencimientosWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class Filtro(val etiqueta: String) {
    TODAS("Todas"),
    POR_VENCER("Por vencer"),
    VENCIDAS("Vencidas"),
    RENOVADAS("Renovadas"),
}

sealed interface Pantalla {
    data object Lista : Pantalla
    data class Editar(val registro: AccountRecord) : Pantalla
    data object Importar : Pantalla
}

data class VistaImportacion(
    val nombre: String,
    val filas: List<List<String>>,
    val filaEncabezado: Int,
    val mapeo: Map<Campo, Int>,
) {
    val columnas: Int get() = filas.maxOfOrNull { it.size } ?: 0
    val encabezado: List<String> get() = filas.getOrNull(filaEncabezado).orEmpty()
    val datos: List<List<String>> get() = filas.drop(filaEncabezado + 1)
}

class CuentasViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.dao()
    private val prefs = app.getSharedPreferences("mapeo", Context.MODE_PRIVATE)

    val todas: StateFlow<List<AccountRecord>> = dao.observar()
        .map { lista -> lista.sortedWith(compareBy<AccountRecord, Long?>(nullsLast()) { Vencimientos.proxima(it) }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val busqueda = MutableStateFlow("")
    val filtro = MutableStateFlow(Filtro.TODAS)

    var pantalla by mutableStateOf<Pantalla>(Pantalla.Lista)
    var importacion by mutableStateOf<VistaImportacion?>(null)
    var mensaje by mutableStateOf<String?>(null)
    var cargando by mutableStateOf(false)

    fun cumple(r: AccountRecord, f: Filtro, hoy: Long): Boolean = when (f) {
        Filtro.TODAS -> true
        Filtro.POR_VENCER -> Vencimientos.estado(r, hoy) == Status.EXPIRING_SOON
        Filtro.VENCIDAS -> Vencimientos.estado(r, hoy) == Status.EXPIRED
        Filtro.RENOVADAS -> Vencimientos.estado(r, hoy) == Status.RENEWED
    }

    fun filtrar(lista: List<AccountRecord>, q: String, f: Filtro, hoy: Long): List<AccountRecord> {
        val n = Mapeo.normalizar(q)
        return lista.filter { r ->
            cumple(r, f, hoy) && (n.isEmpty() || listOf(
                r.clientName, r.accountEmailOrUser, r.cardUsed, r.serviceName, r.clientPhone, r.profile,
            ).any { Mapeo.normalizar(it).contains(n) })
        }
    }

    fun tarjetasUsadas(): List<String> =
        todas.value.map { it.cardUsed.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    // ---------- edición ----------

    fun nueva() { pantalla = Pantalla.Editar(AccountRecord()) }
    fun editar(r: AccountRecord) { pantalla = Pantalla.Editar(r) }
    fun volver() { pantalla = Pantalla.Lista; importacion = null }

    fun guardar(r: AccountRecord) {
        if (r.accountEmailOrUser.isBlank() && r.clientName.isBlank()) {
            mensaje = "Escribe al menos la cuenta o el cliente"
            return
        }
        viewModelScope.launch {
            dao.guardar(r.copy(status = Vencimientos.estado(r).name))
            pantalla = Pantalla.Lista
            RevisarVencimientosWorker.revisarAhora(getApplication())
        }
    }

    fun borrar(r: AccountRecord) {
        viewModelScope.launch { dao.borrar(r); mensaje = "Cuenta eliminada" }
    }

    /** Renovar +1 mes: la fecha del cliente o la del proveedor. */
    fun renovar(r: AccountRecord, proveedor: Boolean) {
        fun mas1(f: Long?) = LocalDate.ofEpochDay(f ?: Vencimientos.hoy()).plusMonths(1).toEpochDay()
        val nuevo = if (proveedor) r.copy(supplierExpiryDate = mas1(r.supplierExpiryDate))
        else r.copy(clientExpiryDate = mas1(r.clientExpiryDate))
        val marcado = nuevo.copy(status = Status.RENEWED.name)
        viewModelScope.launch {
            dao.guardar(marcado.copy(status = Vencimientos.estado(marcado).name))
            mensaje = if (proveedor) "Proveedor renovado +1 mes" else "Cliente renovado +1 mes"
        }
    }

    // ---------- importación ----------

    private fun mapeoGuardado(): Map<Campo, String> =
        Campo.entries.mapNotNull { c -> prefs.getString(c.name, null)?.let { c to it } }.toMap()

    fun abrirArchivo(uri: Uri) {
        viewModelScope.launch {
            cargando = true
            try {
                val app = getApplication<Application>()
                val (nombre, filas) = withContext(Dispatchers.IO) { nombreDe(uri) to LectorHojas.leer(app, uri) }
                if (filas.isEmpty()) {
                    mensaje = "El archivo está vacío"
                } else {
                    val enc = Mapeo.detectarEncabezado(filas)
                    importacion = VistaImportacion(nombre, filas, enc, Mapeo.detectar(filas, enc, mapeoGuardado()))
                    pantalla = Pantalla.Importar
                }
            } catch (e: Exception) {
                mensaje = "No se pudo leer el archivo: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                cargando = false
            }
        }
    }

    private fun nombreDe(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Excel"

    fun cambiarEncabezado(fila: Int) {
        val imp = importacion ?: return
        val f = fila.coerceIn(0, (imp.filas.size - 1).coerceAtLeast(0))
        importacion = imp.copy(filaEncabezado = f, mapeo = Mapeo.detectar(imp.filas, f, emptyMap()))
    }

    fun cambiarColumna(campo: Campo, columna: Int?) {
        val imp = importacion ?: return
        val m = imp.mapeo.toMutableMap()
        if (columna == null) m.remove(campo) else {
            m.entries.removeAll { it.value == columna } // una columna, un solo dato
            m[campo] = columna
        }
        importacion = imp.copy(mapeo = m)
    }

    fun confirmarImportacion() {
        val imp = importacion ?: return
        if (Campo.CUENTA !in imp.mapeo && Campo.CLIENTE !in imp.mapeo) {
            mensaje = "Elige al menos la columna de la cuenta o del cliente"
            return
        }
        viewModelScope.launch {
            cargando = true
            try {
                val res = withContext(Dispatchers.IO) {
                    Importador.importar(db, imp.filas, imp.filaEncabezado, imp.mapeo)
                }
                // Recordar las columnas elegidas (por nombre de encabezado) para el próximo Excel.
                val e = prefs.edit()
                Campo.entries.forEach { c ->
                    val col = imp.mapeo[c]
                    if (col == null) e.remove(c.name) else e.putString(c.name, Mapeo.normalizar(imp.encabezado.getOrNull(col).orEmpty()))
                }
                e.apply()
                importacion = null
                pantalla = Pantalla.Lista
                mensaje = "Importado: ${res.nuevas} nuevas, ${res.actualizadas} actualizadas" +
                    if (res.omitidas > 0) ", ${res.omitidas} filas sin cuenta ni cliente omitidas" else ""
                RevisarVencimientosWorker.revisarAhora(getApplication())
            } catch (e: Exception) {
                mensaje = "Error al importar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                cargando = false
            }
        }
    }
}
