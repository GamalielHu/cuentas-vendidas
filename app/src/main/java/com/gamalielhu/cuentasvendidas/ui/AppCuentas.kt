package com.gamalielhu.cuentasvendidas.ui

import androidx.compose.runtime.Composable

@Composable
fun AppCuentas(vm: CuentasViewModel) {
    when (val p = vm.pantalla) {
        Pantalla.Lista -> ListaScreen(vm)
        is Pantalla.Editar -> EditarScreen(vm, p.registro)
        Pantalla.Importar -> ImportarScreen(vm)
    }
}
