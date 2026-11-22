# CLAUDE.md — Cuentas Vendidas

App Android en Kotlin + Jetpack Compose (Material 3) + Room (KSP) + WorkManager, paquete
`com.gamalielhu.cuentasvendidas`, minSdk 26 / target 35. Gestiona cuentas vendidas (Netflix, Spotify…)
con dos vencimientos (proveedor/real y cliente), tarjeta usada y avisos 1 día antes. Textos en español.

## Estructura (`app/src/main/java/com/gamalielhu/cuentasvendidas`)
- `data/`: `AccountRecord` (Room, fechas en epochDay, `notifiedSupplierFor/ClientFor` evitan avisos repetidos),
  `Vencimientos.estado` (estado sale de las fechas; RENEWED se respeta hasta que esté por vencer, ≤3 días),
  `AppDatabase`/`AccountDao` (`@Upsert`), `Fechas` (serie Excel, ISO, dd/mm/aaaa día primero, "15 ene 2026").
- `excel/`: `LectorHojas` (.xlsx a mano con ZIP + XmlPullParser, sin POI; .xls con jxl; .csv),
  `Mapeo` (detección de columnas por encabezado es/en + contenido; recuerda la elección por nombre de
  encabezado en prefs `mapeo`), `Importador` (upsert: servicio + cuenta + perfil; si la cuenta se repite en el
  Excel se distingue por cliente; los vacíos del Excel no borran datos).
- `workers/RevisarVencimientosWorker`: periódico 24 h (primero a las 9:00) + al abrir/importar/guardar.
- `notifications/Avisos` (canal `vencimientos`, acción WhatsApp) y `WhatsApp` (8 dígitos 6/7 → +591).
- `ui/`: `CuentasViewModel` (navegación por `Pantalla`), `ListaScreen`, `EditarScreen`, `ImportarScreen`.

## Compilar
`.github/workflows/android.yml` (ubuntu, JDK 17, AGP 8.9.1, Kotlin 2.0.21, Gradle 8.14.3): `assembleDebug`
firmado con `app/firma.jks` (clave `cuentas`), artefacto + Release `v1.N` con `CuentasVendidas.apk`.
Si falla, errores en la rama `registro`. No hay Android SDK en el contenedor: solo se compila en Actions.
