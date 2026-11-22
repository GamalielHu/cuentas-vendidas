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
import com.gamalielhu.cuentasvendidas.excel.Filas
import com.gamalielhu.cuentasvendidas.excel.HojaAImportar
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

data class HojaImport(val nombre: String, val filas: List<List<String>>, val encabezado: Int) {
    val enc: List<String> get() = if (encabezado < 0) emptyList() else filas.getOrNull(encabezado).orEmpty()
    val encNormal: List<String> get() = enc.map(Mapeo::normalizar)
    val columnas: Int get() = filas.maxOfOrNull { it.size } ?: 0
}

/**
 * @param elecciones lo que el usuario cambió: campo -> clave de columna (null = "no usar"). Se aplica a
 * todas las hojas elegidas; lo que no eligió se detecta solo en cada hoja.
 */
data class VistaImportacion(
    val nombre: String,
    val hojas: List<HojaImport>,
    val seleccionadas: Set<Int>,
    val vista: Int,
    val elecciones: Map<Campo, String?>,
    val guardado: Map<Campo, String>,
) {
    val hoja: HojaImport get() = hojas[vista]

    fun mapeo(h: HojaImport): Map<Campo, Int> {
        val preferidas = guardado.toMutableMap()
        elecciones.forEach { (c, k) -> if (k == null) preferidas.remove(c) else preferidas[c] = k }
        val excluidas = elecciones.filterValues { it == null }.keys
        return Mapeo.detectar(h.filas, h.encabezado, preferidas, excluidas)
    }

    fun aImportar(h: HojaImport) = HojaAImportar(h.nombre, h.filas, h.encabezado, mapeo(h))
}

class CuentasViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.dao()
    private val prefs = app.getSharedPreferences("mapeo", Context.MODE_PRIVATE)
    /** correo -> tarjeta (del Excel de tarjetas); se aplica también a lo que se importe después. */
    private val tarjetas = app.getSharedPreferences("tarjetas", Context.MODE_PRIVATE)

    val todas: StateFlow<List<AccountRecord>> = dao.observar()
        .map { lista -> lista.sortedWith(compareBy<AccountRecord, Long?>(nullsLast()) { Vencimientos.proxima(it) }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val busqueda = MutableStateFlow("")
    val filtro = MutableStateFlow(Filtro.TODAS)

    var pantalla by mutableStateOf<Pantalla>(Pantalla.Lista)
    var importacion by mutableStateOf<VistaImportacion?>(null)
    var mensaje by mutableStateOf<String?>(null)
    var cargando by mutableStateOf(false)

    init {
        cargarSemilla()
    }

    /**
     * Datos de fábrica (solo si el APK trae `assets/semilla/`): la primera vez importa las hojas indicadas en
     * `semilla/hojas.txt` de `registro.xlsx` y las tarjetas de `tarjetas.xlsx`. Los archivos no van en un repo público.
     */
    private fun cargarSemilla() {
        if (prefs.getBoolean("semilla_cargada", false)) return
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val res = withContext(Dispatchers.IO) {
                    val assets = app.assets
                    val archivos = assets.list("semilla").orEmpty().toSet()
                    if ("registro.xlsx" !in archivos) return@withContext null
                    if ("tarjetas.xlsx" in archivos) {
                        val hojas = LectorHojas.leer(assets.open("semilla/tarjetas.xlsx").use { it.readBytes() }, "tarjetas")
                        val e = tarjetas.edit()
                        for (h in hojas) {
                            val enc = Mapeo.detectarEncabezado(h.filas)
                            val hi = HojaAImportar(h.nombre, h.filas, enc, Mapeo.detectar(h.filas, enc, emptyMap()))
                            if (!Filas.esSoloTarjetas(hi.mapeo)) continue
                            Filas.datos(hi).map { Filas.aRegistro(it, hi.mapeo) }
                                .filter { it.accountEmailOrUser.isNotBlank() && it.cardUsed.isNotBlank() }
                                .forEach { e.putString(Filas.claveCorreo(it.accountEmailOrUser), it.cardUsed.trim()) }
                        }
                        e.commit()
                    }
                    val elegidas = if ("hojas.txt" in archivos) {
                        assets.open("semilla/hojas.txt").bufferedReader().readLines().map { Mapeo.normalizar(it) }.filter { it.isNotEmpty() }
                    } else emptyList()
                    val hojas = LectorHojas.leer(assets.open("semilla/registro.xlsx").use { it.readBytes() }, "registro")
                        .filter { elegidas.isEmpty() || Mapeo.normalizar(it.nombre) in elegidas }
                        .map { h ->
                            val enc = Mapeo.detectarEncabezado(h.filas)
                            HojaAImportar(h.nombre, h.filas, enc, Mapeo.detectar(h.filas, enc, emptyMap()))
                        }
                    Importador.importar(db, hojas, tarjetasPorCorreo())
                }
                prefs.edit().putBoolean("semilla_cargada", true).apply()
                if (res != null) {
                    mensaje = "Cuentas cargadas: ${res.nuevas} nuevas, ${res.actualizadas} actualizadas"
                    RevisarVencimientosWorker.revisarAhora(getApplication())
                }
            } catch (e: Exception) {
                mensaje = "No se pudieron cargar las cuentas de fábrica: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

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

    private fun tarjetasPorCorreo(): Map<String, String> =
        tarjetas.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun abrirArchivo(uri: Uri) {
        viewModelScope.launch {
            cargando = true
            try {
                val app = getApplication<Application>()
                val nombre = withContext(Dispatchers.IO) { nombreDe(uri) }
                val leidas = withContext(Dispatchers.IO) { LectorHojas.leer(app, uri, nombre) }
                if (leidas.isEmpty()) {
                    mensaje = "El archivo está vacío"
                } else {
                    val hojas = leidas.map { HojaImport(it.nombre, it.filas, Mapeo.detectarEncabezado(it.filas)) }
                    val guardado = mapeoGuardado()
                    val base = VistaImportacion(nombre, hojas, emptySet(), 0, emptyMap(), guardado)
                    // Hojas elegidas la vez anterior; si no, la que más cuentas vendidas tiene.
                    val antes = prefs.getStringSet("hojas", emptySet()).orEmpty()
                    var sel = hojas.indices.filter { hojas[it].nombre in antes }.toSet()
                    if (sel.isEmpty()) {
                        val utiles = hojas.indices.associateWith { i ->
                            val h = base.aImportar(hojas[i])
                            Filas.datos(h).map { Filas.aRegistro(it, h.mapeo) }
                                .count { !Filas.vacia(it) && (!Filas.sinVender(it) || Filas.esSoloTarjetas(h.mapeo)) }
                        }
                        sel = setOf(utiles.maxByOrNull { it.value }?.key ?: 0)
                    }
                    importacion = base.copy(seleccionadas = sel, vista = sel.min())
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

    /** Tocar una hoja: la muestra; si ya se estaba viendo, la marca o desmarca para importar. */
    fun tocarHoja(i: Int) {
        val imp = importacion ?: return
        importacion = when {
            i != imp.vista && i !in imp.seleccionadas -> imp.copy(vista = i, seleccionadas = imp.seleccionadas + i)
            i != imp.vista -> imp.copy(vista = i)
            i in imp.seleccionadas && imp.seleccionadas.size > 1 -> imp.copy(seleccionadas = imp.seleccionadas - i)
            else -> imp.copy(seleccionadas = imp.seleccionadas + i)
        }
    }

    fun cambiarEncabezado(fila: Int) {
        val imp = importacion ?: return
        val h = imp.hoja
        val f = fila.coerceIn(-1, (h.filas.size - 1).coerceAtLeast(0))
        importacion = imp.copy(hojas = imp.hojas.toMutableList().also { it[imp.vista] = h.copy(encabezado = f) })
    }

    fun cambiarColumna(campo: Campo, columna: Int?) {
        val imp = importacion ?: return
        val clave = columna?.let { Mapeo.claveColumna(imp.hoja.encNormal, it) }
        val e = imp.elecciones.toMutableMap()
        if (columna != null) {
            // una columna, un solo dato
            imp.mapeo(imp.hoja).filter { it.value == columna && it.key != campo }.keys.forEach { e[it] = null }
        }
        e[campo] = clave
        importacion = imp.copy(elecciones = e)
    }

    fun confirmarImportacion() {
        val imp = importacion ?: return
        val hojas = imp.seleccionadas.sorted().map { imp.aImportar(imp.hojas[it]) }
        if (hojas.isEmpty()) { mensaje = "Elige al menos una hoja"; return }
        if (hojas.any { Campo.CUENTA !in it.mapeo && Campo.CLIENTE !in it.mapeo }) {
            mensaje = "Elige al menos la columna de la cuenta o del cliente"
            return
        }
        viewModelScope.launch {
            cargando = true
            try {
                val (deTarjetas, deCuentas) = hojas.partition { Filas.esSoloTarjetas(it.mapeo) }
                val partes = ArrayList<String>()
                if (deTarjetas.isNotEmpty()) {
                    val nuevas = deTarjetas.flatMap { h ->
                        Filas.datos(h).mapNotNull { f ->
                            val r = Filas.aRegistro(f, h.mapeo)
                            if (r.accountEmailOrUser.isBlank() || r.cardUsed.isBlank()) null
                            else Filas.claveCorreo(r.accountEmailOrUser) to r.cardUsed.trim()
                        }
                    }.toMap()
                    tarjetas.edit().apply { nuevas.forEach { (k, v) -> putString(k, v) } }.apply()
                    val n = withContext(Dispatchers.IO) { Importador.asignarTarjetas(db, tarjetasPorCorreo() + nuevas) }
                    partes += "${nuevas.size} correos con tarjeta ($n cuentas actualizadas)"
                }
                if (deCuentas.isNotEmpty()) {
                    val res = withContext(Dispatchers.IO) { Importador.importar(db, deCuentas, tarjetasPorCorreo()) }
                    partes += "${res.nuevas} nuevas, ${res.actualizadas} actualizadas" +
                        if (res.sinVender > 0) ", ${res.sinVender} sin vender omitidas" else ""
                }
                // Recordar hojas y columnas elegidas (por clave de columna) para el próximo Excel.
                val m = imp.mapeo(imp.hoja)
                val e = prefs.edit()
                Campo.entries.forEach { c ->
                    val col = m[c]
                    if (col == null) e.remove(c.name) else e.putString(c.name, Mapeo.claveColumna(imp.hoja.encNormal, col))
                }
                e.putStringSet("hojas", imp.seleccionadas.map { imp.hojas[it].nombre }.toSet())
                e.apply()
                importacion = null
                pantalla = Pantalla.Lista
                mensaje = "Importado: " + partes.joinToString("; ")
                RevisarVencimientosWorker.revisarAhora(getApplication())
            } catch (e: Exception) {
                mensaje = "Error al importar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                cargando = false
            }
        }
    }
}
