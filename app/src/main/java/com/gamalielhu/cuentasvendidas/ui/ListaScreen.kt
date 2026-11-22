@file:OptIn(ExperimentalMaterial3Api::class)

package com.gamalielhu.cuentasvendidas.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamalielhu.cuentasvendidas.data.AccountRecord
import com.gamalielhu.cuentasvendidas.data.Fechas
import com.gamalielhu.cuentasvendidas.data.Vencimientos
import com.gamalielhu.cuentasvendidas.notifications.WhatsApp

private val TIPOS_EXCEL = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "text/csv",
    "text/comma-separated-values",
    "application/octet-stream",
)

@Composable
fun ListaScreen(vm: CuentasViewModel) {
    val todas by vm.todas.collectAsStateWithLifecycle()
    val busqueda by vm.busqueda.collectAsStateWithLifecycle()
    val filtro by vm.filtro.collectAsStateWithLifecycle()
    val hoy = remember { Vencimientos.hoy() }
    val visibles = remember(todas, busqueda, filtro) { vm.filtrar(todas, busqueda, filtro, hoy) }
    val selector = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::abrirArchivo)
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.mensaje) {
        vm.mensaje?.let { snack.showSnackbar(it); vm.mensaje = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cuentas vendidas") },
                actions = { TextButton(onClick = { selector.launch(TIPOS_EXCEL) }) { Text("Importar Excel") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::nueva) { Icon(Icons.Default.Add, "Nueva cuenta") }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = busqueda,
                onValueChange = { vm.busqueda.value = it },
                placeholder = { Text("Buscar cliente, cuenta o tarjeta") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (busqueda.isNotEmpty()) IconButton(onClick = { vm.busqueda.value = "" }) {
                        Icon(Icons.Default.Clear, "Borrar búsqueda")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filtro.entries.forEach { f ->
                    val n = todas.count { vm.cumple(it, f, hoy) }
                    FilterChip(
                        selected = filtro == f,
                        onClick = { vm.filtro.value = f },
                        label = { Text("${f.etiqueta} ($n)") },
                    )
                }
            }
            if (vm.cargando) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (visibles.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (todas.isEmpty()) "Aún no hay cuentas.\nToca «Importar Excel» o el botón +."
                        else "Ninguna cuenta con este filtro.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibles, key = { it.id }) { r -> TarjetaCuenta(r, hoy, vm) }
                }
            }
        }
    }
}

@Composable
private fun TarjetaCuenta(r: AccountRecord, hoy: Long, vm: CuentasViewModel) {
    val ctx = LocalContext.current
    val portapapeles = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var confirmarBorrar by remember { mutableStateOf(false) }

    ElevatedCard(onClick = { vm.editar(r) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.serviceName.ifBlank { "Cuenta" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                EtiquetaEstado(Vencimientos.estado(r, hoy))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Renovar cliente +1 mes") },
                            onClick = { menu = false; vm.renovar(r, proveedor = false) },
                        )
                        DropdownMenuItem(
                            text = { Text("Renovar proveedor +1 mes") },
                            onClick = { menu = false; vm.renovar(r, proveedor = true) },
                        )
                        if (r.clientPhone.isNotBlank()) DropdownMenuItem(
                            text = { Text("Avisar al cliente por WhatsApp") },
                            onClick = { menu = false; WhatsApp.abrir(ctx, r) },
                        )
                        DropdownMenuItem(
                            text = { Text("Copiar cuenta y contraseña") },
                            onClick = {
                                menu = false
                                portapapeles.setText(AnnotatedString(buildString {
                                    append(r.accountEmailOrUser)
                                    if (r.password.isNotBlank()) append("\n${r.password}")
                                    if (r.profile.isNotBlank()) append("\nPerfil: ${r.profile}")
                                }))
                                vm.mensaje = "Copiado"
                            },
                        )
                        DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menu = false; confirmarBorrar = true })
                    }
                }
            }
            val cuenta = r.accountEmailOrUser + if (r.profile.isNotBlank()) " · ${r.profile}" else ""
            if (cuenta.isNotBlank()) Text(cuenta, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.clientName.isNotBlank() || r.clientPhone.isNotBlank()) Text(
                "Cliente: " + listOf(r.clientName, r.clientPhone).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Tarjeta: " + r.cardUsed.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(6.dp))
            FilaFecha("Vence proveedor", r.supplierExpiryDate, hoy)
            Spacer(Modifier.height(4.dp))
            FilaFecha("Vence cliente", r.clientExpiryDate, hoy)
        }
    }

    if (confirmarBorrar) AlertDialog(
        onDismissRequest = { confirmarBorrar = false },
        title = { Text("¿Eliminar cuenta?") },
        text = { Text("${r.serviceName} · ${r.accountEmailOrUser} · ${r.clientName}") },
        confirmButton = { TextButton(onClick = { confirmarBorrar = false; vm.borrar(r) }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { confirmarBorrar = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun FilaFecha(titulo: String, fecha: Long?, hoy: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Text("$titulo:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(122.dp))
        Text(
            Fechas.formato(fecha) ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (fecha != null) EtiquetaDias(fecha - hoy)
    }
}
