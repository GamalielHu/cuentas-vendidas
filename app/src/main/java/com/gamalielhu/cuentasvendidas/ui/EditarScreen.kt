@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gamalielhu.cuentasvendidas.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gamalielhu.cuentasvendidas.data.AccountRecord
import com.gamalielhu.cuentasvendidas.data.Fechas
import com.gamalielhu.cuentasvendidas.data.Status
import com.gamalielhu.cuentasvendidas.data.Vencimientos
import java.time.LocalDate

@Composable
fun EditarScreen(vm: CuentasViewModel, inicial: AccountRecord) {
    var r by remember(inicial) { mutableStateOf(inicial) }
    val tarjetas = remember { vm.tarjetasUsadas() }
    BackHandler { vm.volver() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (inicial.id == 0L) "Nueva cuenta" else "Editar cuenta") },
                navigationIcon = {
                    IconButton(onClick = vm::volver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = { TextButton(onClick = { vm.guardar(r) }) { Text("Guardar") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Campo("Servicio (Netflix, Spotify…)", r.serviceName) { r = r.copy(serviceName = it) }
            Campo("Cuenta / correo / usuario", r.accountEmailOrUser, KeyboardType.Email) { r = r.copy(accountEmailOrUser = it) }
            Campo("Contraseña (opcional)", r.password) { r = r.copy(password = it) }
            Campo("Perfil / pantalla (opcional)", r.profile) { r = r.copy(profile = it) }
            Campo("PIN del perfil (opcional)", r.pin, KeyboardType.Number) { r = r.copy(pin = it) }
            Campo("Cliente", r.clientName) { r = r.copy(clientName = it) }
            Campo("Teléfono del cliente (opcional)", r.clientPhone, KeyboardType.Phone) { r = r.copy(clientPhone = it) }
            Campo("Tarjeta usada", r.cardUsed) { r = r.copy(cardUsed = it) }
            if (tarjetas.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tarjetas.take(12).forEach { t -> SuggestionChip(onClick = { r = r.copy(cardUsed = t) }, label = { Text(t) }) }
            }
            Campo("Proveedor (dónde compras/renuevas)", r.supplierName) { r = r.copy(supplierName = it) }
            SelectorFecha("Vence proveedor (real)", r.supplierExpiryDate) { r = r.copy(supplierExpiryDate = it) }
            SelectorFecha("Vence cliente", r.clientExpiryDate) { r = r.copy(clientExpiryDate = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = r.status == Status.RENEWED.name,
                    onCheckedChange = { r = r.copy(status = if (it) Status.RENEWED.name else Status.ACTIVE.name) },
                )
                Text("  Marcar como renovada", Modifier.weight(1f))
                EtiquetaEstado(Vencimientos.estado(r))
            }
            OutlinedTextField(
                value = r.notes,
                onValueChange = { r = r.copy(notes = it) },
                label = { Text("Notas") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Campo(etiqueta: String, valor: String, teclado: KeyboardType = KeyboardType.Text, cambiar: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = cambiar,
        label = { Text(etiqueta) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SelectorFecha(titulo: String, fecha: Long?, cambiar: (Long?) -> Unit) {
    val ctx = LocalContext.current
    Column {
        Text(titulo, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val base = LocalDate.ofEpochDay(fecha ?: Vencimientos.hoy())
                    DatePickerDialog(ctx, { _, a, m, d -> cambiar(LocalDate.of(a, m + 1, d).toEpochDay()) },
                        base.year, base.monthValue - 1, base.dayOfMonth).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text(Fechas.formato(fecha) ?: "Elegir fecha") }
            TextButton(onClick = {
                cambiar(LocalDate.ofEpochDay(fecha ?: Vencimientos.hoy()).plusMonths(1).toEpochDay())
            }) { Text("+1 mes") }
            if (fecha != null) IconButton(onClick = { cambiar(null) }) { Icon(Icons.Default.Clear, "Quitar fecha") }
        }
    }
}
