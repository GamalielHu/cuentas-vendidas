package com.gamalielhu.cuentasvendidas.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gamalielhu.cuentasvendidas.data.Status

val Rojo = Color(0xFFC62828)
val Naranja = Color(0xFFE65100)
val Verde = Color(0xFF2E7D32)
val Azul = Color(0xFF1565C0)

@Composable
fun TemaCuentas(contenido: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Rojo, secondary = Azul, tertiary = Verde),
        content = contenido,
    )
}

@Composable
fun Etiqueta(texto: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(texto, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun EtiquetaDias(dias: Long) {
    val (texto, color) = when {
        dias < 0 -> "Vencida hace ${-dias} d" to Rojo
        dias == 0L -> "Vence hoy" to Rojo
        dias == 1L -> "Mañana" to Naranja
        dias <= 3 -> "En $dias días" to Naranja
        else -> "En $dias días" to Verde
    }
    Etiqueta(texto, color)
}

@Composable
fun EtiquetaEstado(s: Status) {
    val color = when (s) {
        Status.ACTIVE -> Verde
        Status.EXPIRING_SOON -> Naranja
        Status.EXPIRED -> Rojo
        Status.RENEWED -> Azul
    }
    Etiqueta(s.etiqueta, color)
}
