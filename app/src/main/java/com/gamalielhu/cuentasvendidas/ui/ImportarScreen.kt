@file:OptIn(ExperimentalMaterial3Api::class)

package com.gamalielhu.cuentasvendidas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import com.gamalielhu.cuentasvendidas.data.Fechas
import com.gamalielhu.cuentasvendidas.excel.Campo
import com.gamalielhu.cuentasvendidas.excel.Importador
import com.gamalielhu.cuentasvendidas.excel.Mapeo

@Composable
fun ImportarScreen(vm: CuentasViewModel) {
    val imp = vm.importacion ?: return
    BackHandler { vm.volver() }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.mensaje) {
        vm.mensaje?.let { snack.showSnackbar(it); vm.mensaje = null }
    }

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
            item {
                Text(
                    "Columnas detectadas automáticamente. Toca una para cambiarla. " +
                        "Las cuentas que ya existen (mismo servicio + cuenta + perfil) se actualizan sin duplicarse.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fila de encabezados: ${imp.filaEncabezado + 1}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.cambiarEncabezado(imp.filaEncabezado - 1) }) { Text("−") }
                    TextButton(onClick = { vm.cambiarEncabezado(imp.filaEncabezado + 1) }) { Text("+") }
                }
            }
            items(Campo.entries) { c -> SelectorColumna(c, imp, vm) }
            item {
                Text(
                    "Vista previa · ${imp.datos.size} filas",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(imp.datos.take(5)) { fila -> FilaPrevia(fila, imp.mapeo) }
        }
    }
}

@Composable
private fun SelectorColumna(campo: Campo, imp: VistaImportacion, vm: CuentasViewModel) {
    var abierto by remember { mutableStateOf(false) }
    val sel = imp.mapeo[campo]
    fun nombre(i: Int) = "${Mapeo.letra(i)}: ${imp.encabezado.getOrNull(i).orEmpty().ifBlank { "(sin título)" }}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(campo.etiqueta, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { abierto = true }, modifier = Modifier.widthIn(min = 150.dp, max = 210.dp)) {
                Text(sel?.let(::nombre) ?: "— No usar —", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                DropdownMenuItem(text = { Text("— No usar —") }, onClick = { abierto = false; vm.cambiarColumna(campo, null) })
                (0 until imp.columnas).forEach { i ->
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
private fun FilaPrevia(fila: List<String>, mapeo: Map<Campo, Int>) {
    val r = Importador.aRegistro(fila, mapeo)
    fun fecha(campo: Campo, valor: Long?): String {
        val crudo = mapeo[campo]?.let { fila.getOrNull(it) }.orEmpty()
        return Fechas.formato(valor) ?: if (crudo.isBlank()) "—" else "⚠ «$crudo» no es fecha"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(
                listOf(r.serviceName, r.accountEmailOrUser, r.profile).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                fontWeight = FontWeight.SemiBold,
            )
            Text("Cliente: ${r.clientName.ifBlank { "—" }}  ·  Tarjeta: ${r.cardUsed.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
            Text("Vence proveedor: ${fecha(Campo.VENCE_PROVEEDOR, r.supplierExpiryDate)}", style = MaterialTheme.typography.bodySmall)
            Text("Vence cliente: ${fecha(Campo.VENCE_CLIENTE, r.clientExpiryDate)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
