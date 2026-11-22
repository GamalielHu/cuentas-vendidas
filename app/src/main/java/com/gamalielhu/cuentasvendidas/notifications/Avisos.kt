package com.gamalielhu.cuentasvendidas.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gamalielhu.cuentasvendidas.MainActivity
import com.gamalielhu.cuentasvendidas.R
import com.gamalielhu.cuentasvendidas.data.AccountRecord
import com.gamalielhu.cuentasvendidas.data.Fechas

object Avisos {
    private const val CANAL = "vencimientos"

    fun crearCanal(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Vencimientos", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aviso 1 día antes de que venza una cuenta (cliente o proveedor)"
            }
        )
    }

    fun permitido(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** @param proveedor true = vence la cuenta real (renovar); false = vence la venta al cliente. */
    @SuppressLint("MissingPermission")
    fun avisar(ctx: Context, r: AccountRecord, proveedor: Boolean, dias: Long) {
        if (!permitido(ctx)) return
        val cuando = if (dias <= 0) "HOY" else "mañana"
        val servicio = r.serviceName.ifBlank { "Cuenta" }
        val fecha = Fechas.formato(if (proveedor) r.supplierExpiryDate else r.clientExpiryDate).orEmpty()
        val titulo = if (proveedor) "Renovar con proveedor ($cuando): $servicio"
        else "Vence al cliente ($cuando): $servicio"
        val texto = buildString {
            if (proveedor) {
                append("Vence proveedor $fecha · ${r.accountEmailOrUser}")
                if (r.cardUsed.isNotBlank()) append("\nTarjeta: ${r.cardUsed}")
            } else {
                append("Avisar a ${r.clientName.ifBlank { "cliente" }} · vence $fecha")
                append("\nCuenta: ${r.accountEmailOrUser}")
                if (r.clientPhone.isNotBlank()) append(" · ${r.clientPhone}")
            }
        }
        val abrir = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_aviso)
            .setContentTitle(titulo)
            .setContentText(texto.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (!proveedor && r.clientPhone.isNotBlank()) {
            val wa = PendingIntent.getActivity(
                ctx, r.id.toInt(), WhatsApp.intent(r),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Avisar por WhatsApp", wa)
        }
        try {
            NotificationManagerCompat.from(ctx).notify((r.id * 2 + if (proveedor) 1 else 0).toInt(), b.build())
        } catch (_: SecurityException) {
        }
    }
}

object WhatsApp {
    /** Números bolivianos de 8 dígitos (6xxxxxxx / 7xxxxxxx) reciben el 591 delante. */
    private fun numero(tel: String): String {
        val d = tel.filter(Char::isDigit)
        return if (d.length == 8 && (d[0] == '6' || d[0] == '7')) "591$d" else d
    }

    fun mensaje(r: AccountRecord): String {
        val fecha = Fechas.formato(r.clientExpiryDate)
        val nombre = r.clientName.substringBefore(' ').ifBlank { "" }
        return buildString {
            append("Hola")
            if (nombre.isNotEmpty()) append(" $nombre")
            append(", tu cuenta de ${r.serviceName.ifBlank { "streaming" }}")
            if (fecha != null) append(" vence el $fecha") else append(" está por vencer")
            append(". ¿Deseas renovarla?")
        }
    }

    fun intent(r: AccountRecord): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${numero(r.clientPhone)}?text=${Uri.encode(mensaje(r))}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun abrir(ctx: Context, r: AccountRecord) {
        try {
            ctx.startActivity(intent(r))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No se encontró WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }
}
