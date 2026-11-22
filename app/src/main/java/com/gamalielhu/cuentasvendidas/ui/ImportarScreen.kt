@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gamalielhu.cuentasvendidas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamalielhu.cuentasvendidas.data.AccountRecord
import com.gamalielhu.cuentasvendidas.data.Fechas
import com.gamalielhu.cuentasvendidas.excel.Campo
import com.gamalielhu.cuentasvendidas.excel.Filas
import com.gamalielhu.cuentasvendidas.excel.Mapeo

@Composable
fun ImportarScreen(vm: CuentasViewModel) {
    val imp = vm.importacion ?: return
    BackHandler { vm.volver() }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.mensaje) {
        vm.mensaje?.let { snack.showSnackbar(it); vm.mensaje = null }
    }
    val hoja = imp.hoja
    val mapeo = remember(imp) { imp.mapeo(hoja) }
    val soloTarjetas = Filas.esSoloTarjetas(mapeo)
    val datos = remember(imp) { Filas.datos(imp.aImportar(hoja)) }
    val registros = remember(imp) { datos.map { Filas.aRegistro(it, mapeo, hoja.nombre) }.filterNot(Filas::vacia) }
    val vendidas = registros.filterNot(Filas::sinVender)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(imp.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::volver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancelar") }
                },
                actions = {
                    TextButton(onClick = vm::confirmarImportacion, enabled = !vm.cargando) { Text("Importar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.cargando) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (imp.hojas.size > 1) {
                item {
                    Text(
                        "Hojas a importar (el nombre de la hoja es el servicio). Toca una para verla; " +
                            "tócala otra vez para quitarla o incluirla.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        imp.hojas.forEachIndexed { i, h ->
                            FilterChip(
                                selected = i in imp.seleccionadas,
                                onClick = { vm.tocarHoja(i) },
                                label = {
                                    Text(h.nombre, fontWeight = if (i == imp.vista) FontWeight.Bold else FontWeight.Normal)
                                },
                                border = if (i == imp.vista) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    if (soloTarjetas) "«${hoja.nombre}» asigna tarjetas por correo: se pondrá la tarjeta a las cuentas con " +
                        "ese correo (también a las que importes después)."
                    else "«${hoja.nombre}»: ${vendidas.size} cuentas vendidas" +
                        (if (registros.size > vendidas.size) " (${registros.size - vendidas.size} sin cliente ni fechas se omiten)" else "") +
                        ". Columnas detectadas solas; toca una para cambiarla. Las que ya existen se actualizan sin duplicarse.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hoja.encabezado < 0) "Sin fila de títulos" else "Fila de títulos: ${hoja.encabezado + 1}",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.cambiarEncabezado(hoja.encabezado - 1) }) { Text("−") }
                    TextButton(onClick = { vm.cambiarEncabezado(hoja.encabezado + 1) }) { Text("+") }
                }
            }
            items(Campo.entries) { c -> SelectorColumna(c, hoja, mapeo, vm) }
            item {
                Text("Vista previa", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items((if (soloTarjetas) registros else vendidas).take(5)) { r -> FilaPrevia(r, soloTarjetas) }
        }
    }
}

@Composable
private fun SelectorColumna(campo: Campo, hoja: HojaImport, mapeo: Map<Campo, Int>, vm: CuentasViewModel) {
    var abierto by remember { mutableStateOf(false) }
    val sel = mapeo[campo]
    fun nombre(i: Int) = "${Mapeo.letra(i)}: ${hoja.enc.getOrNull(i).orEmpty().ifBlank { "(sin título)" }}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(campo.etiqueta, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { abierto = true }, modifier = Modifier.widthIn(min = 150.dp, max = 210.dp)) {
                Text(sel?.let(::nombre) ?: "— No usar —", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                DropdownMenuItem(text = { Text("— No usar —") }, onClick = { abierto = false; vm.cambiarColumna(campo, null) })
                (0 until hoja.columnas).forEach { i ->
                    DropdownMenuItem(
                        text = { Text(nombre(i), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { abierto = false; vm.cambiarColumna(campo, i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilaPrevia(r: AccountRecord, soloTarjetas: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            if (soloTarjetas) {
                Text(r.accountEmailOrUser, fontWeight = FontWeight.SemiBold)
                Text("Tarjeta: ${r.cardUsed.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text(
                listOf(r.serviceName, r.accountEmailOrUser, r.profile).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Cliente: ${listOf(r.clientName, r.clientPhone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }}" +
                    "  ·  Tarjeta: ${r.cardUsed.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Vence proveedor: ${Fechas.formato(r.supplierExpiryDate) ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("Vence cliente: ${Fechas.formato(r.clientExpiryDate) ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
