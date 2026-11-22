package com.gamalielhu.cuentasvendidas.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamalielhu.cuentasvendidas.data.AppDatabase
import com.gamalielhu.cuentasvendidas.data.Vencimientos
import com.gamalielhu.cuentasvendidas.notifications.Avisos
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Revisa todas las cuentas: avisa lo que vence mañana (u hoy si no se avisó) y actualiza el estado. */
class RevisarVencimientosWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        revisar(applicationContext)
        return Result.success()
    }

    companion object {
        private const val DIARIA = "revision-diaria"
        private const val AHORA = "revision-ahora"

        suspend fun revisar(ctx: Context) {
            val dao = AppDatabase.get(ctx).dao()
            val hoy = Vencimientos.hoy()
            val puedeAvisar = Avisos.permitido(ctx)
            for (r in dao.todas()) {
                var c = r
                val prov = r.supplierExpiryDate
                if (puedeAvisar && prov != null && prov - hoy in 0..1 && r.notifiedSupplierFor != prov) {
                    Avisos.avisar(ctx, r, proveedor = true, dias = prov - hoy)
                    c = c.copy(notifiedSupplierFor = prov)
                }
                val cli = r.clientExpiryDate
                if (puedeAvisar && cli != null && cli - hoy in 0..1 && r.notifiedClientFor != cli) {
                    Avisos.avisar(ctx, r, proveedor = false, dias = cli - hoy)
                    c = c.copy(notifiedClientFor = cli)
                }
                c = c.copy(status = Vencimientos.estado(c, hoy).name)
                if (c != r) dao.guardar(c)
            }
        }

        /** Revisión cada 24 h, la primera a las 9:00. */
        fun programar(ctx: Context) {
            val ahora = LocalDateTime.now()
            var proxima = ahora.toLocalDate().atTime(9, 0)
            if (!proxima.isAfter(ahora)) proxima = proxima.plusDays(1)
            val req = PeriodicWorkRequestBuilder<RevisarVencimientosWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(ahora, proxima).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(DIARIA, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Al abrir la app o importar un Excel. */
        fun revisarAhora(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                AHORA, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RevisarVencimientosWorker>().build(),
            )
        }
    }
}
