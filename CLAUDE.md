# CLAUDE.md — Cuentas Vendidas

App Android en Kotlin + Jetpack Compose (Material 3) + Room (KSP) + WorkManager, paquete
`com.gamalielhu.cuentasvendidas`, minSdk 26 / target 35. Gestiona cuentas vendidas (Netflix, Spotify…)
con dos vencimientos (proveedor/real y cliente), tarjeta usada y avisos 1 día antes. Textos en español.

## Estructura (`app/src/main/java/com/gamalielhu/cuentasvendidas`)
- `data/`: `AccountRecord` (Room, fechas en epochDay, `notifiedSupplierFor/ClientFor` evitan avisos repetidos),
  `Vencimientos.estado` (estado sale de las fechas; RENEWED se respeta hasta que esté por vencer, ≤3 días),
  `AppDatabase`/`AccountDao` (`@Upsert`), `Fechas` (serie Excel, ISO, dd/mm/aaaa día primero, "15 ene 2026").
- `excel/`: `LectorHojas` (todas las hojas; .xlsx a mano con ZIP + XmlPullParser, sin POI; .xls con jxl; .csv),
  `Mapeo` (detección por encabezado es/en + contenido; columnas a la derecha de "PROVEEDOR" son de la cuenta
  real, así las dos "EXPIRACIÓN" se separan; sin títulos reconoce correos, bancos y fechas; clave de columna
  `encabezado#n` guardada en prefs `mapeo`), `Filas` (fila -> registro; filas sin cliente ni fechas = sin vender,
  se omiten; hoja con solo correo + tarjeta asigna tarjetas, guardadas en prefs `tarjetas`), `Importador`
  (upsert: servicio + cuenta + perfil; el servicio es el nombre de la hoja; los vacíos no borran datos).
- Excel real de Gamaliel: REGISTRO DE CLIENTES.xlsx (una hoja por servicio, títulos en fila 9: PERFIL, NOMBRE,
  CELULAR, PIN, CORREO, CLAVE, PRECIO, EXPIRACIÓN (cliente, a veces "10/11" sin año), …, PROVEEDOR, PRECIO,
  EXPIRACIÓN (proveedor)) y TARJETAS USADAS.xlsx (sin títulos: B correo, C tarjeta).
- `workers/RevisarVencimientosWorker`: periódico 24 h (primero a las 9:00) + al abrir/importar/guardar.
- `notifications/Avisos` (canal `vencimientos`, acción WhatsApp) y `WhatsApp` (8 dígitos 6/7 → +591, 9 dígitos con 9 → +51).
- `ui/`: `CuentasViewModel` (navegación por `Pantalla`), `ListaScreen`, `EditarScreen`, `ImportarScreen`.

## Compilar
`.github/workflows/android.yml` (ubuntu, JDK 17, AGP 8.9.1, Kotlin 2.0.21, Gradle 8.14.3): `assembleDebug`
firmado con `app/firma.jks` (clave `cuentas`), artefacto + Release `v1.N` con `CuentasVendidas.apk`.
Si falla, errores en la rama `registro`. No hay Android SDK en el contenedor: solo se compila en Actions.
