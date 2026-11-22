package com.gamalielhu.cuentasvendidas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.gamalielhu.cuentasvendidas.notifications.Avisos
import com.gamalielhu.cuentasvendidas.ui.AppCuentas
import com.gamalielhu.cuentasvendidas.ui.CuentasViewModel
import com.gamalielhu.cuentasvendidas.ui.TemaCuentas
import com.gamalielhu.cuentasvendidas.workers.RevisarVencimientosWorker

class MainActivity : ComponentActivity() {
    private val vm: CuentasViewModel by viewModels()

    private val permisoAvisos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) RevisarVencimientosWorker.revisarAhora(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Avisos.crearCanal(this)
        RevisarVencimientosWorker.programar(this)
        RevisarVencimientosWorker.revisarAhora(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permisoAvisos.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) abrirExcelDe(intent)
        setContent { TemaCuentas { AppCuentas(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirExcelDe(intent)
    }

    /** Excel abierto desde WhatsApp o el explorador ("Abrir con…"). */
    private fun abrirExcelDe(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(vm::abrirArchivo)
    }
}
