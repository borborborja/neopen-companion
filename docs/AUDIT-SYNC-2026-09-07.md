# Correcciones de la auditoría Android ↔ web — 2026-09-07

> Registro de la verificación local previa a la publicación. Las correcciones
> se preparan para **v0.23.4**, con snapshot fijado al mismo tag del servidor.
> Los 33 tests de core citados abajo incluían 15 tests de cambios BLE locales que
> quedan fuera de esta release; la publicación verifica también una copia limpia
> del commit seleccionado. Véase `docs/RELEASE-v0.23.4.md`.

## Alcance

Corrección de los ocho defectos confirmados en la auditoría Android/web, sobre
los cambios locales preexistentes de `neopen-companion` y `neopen`. No incluye
publicación, despliegue, cambios de credenciales ni modificación de datos reales.

| Defecto | Corrección |
| --- | --- |
| LOCAL enviaba datos con credenciales guardadas | Guardas de modo en push/pull, entre operaciones y en la planificación de workers. |
| Vinculación perdía OCR/metadatos y fusionaba revisiones | Outbox de snapshots completos y `PUT /api/migration`, aditivo por id estable. Room no se vacía. |
| ARGB uint32 bloqueaba el delta Android | Normalización de entrada y lectura histórica en servidor; adaptador Moshi compatible con uint32/int32. |
| CREATE seguido de PATCH usaba un id obsoleto | FIFO releído tras cada operación; motor serializado y confirmación/remapeo transaccional. |
| Referencias a cuadernos temporales incompletas | Remapeo de targetId, notebookId de PATCH/CREATE y listas de orden. |
| HTTP 429 descartaba la operación | Se conserva la operación y el error; Retry-After persistido; los demás rechazos tampoco se descartan. |
| Web no reflejaba ediciones/borrados Android | Sondeo de la revisión global del servidor, independiente del marcador companion.at. |
| Borrar formato enviaba PATCH vacío | Presencia explícita de paper; se transmite paper:null sin añadir otros campos nulos. |

También se preservan las ediciones optimistas posteriores a una confirmación,
se agrupan las mutaciones básicas de Room con su outbox en transacciones y se
impide que un reset remoto borre escrituras pendientes. Las cancelaciones de
corrutinas se propagan; no se convierten en confirmaciones.

## Verificación realizada

- Servidor/web: **74 tests**, typecheck de los tres paquetes y build de producción.
- Android core: **33 tests**.
- Android app: **15 regresiones nuevas** con Robolectric API 34, Room real y
  MockWebServer HTTPS: modo local, remapeo, conflictos, rate limit, papel nulo,
  ARGB vía JSON y HTTP, migración completa/interrumpida, servidor antiguo,
  concurrencia, ordenación y protección frente a reset.
- `checkApiContract`: **38 operaciones Retrofit**, SHA del snapshot idéntico al
  servidor y esquemas Room 2..9. `pnpm check:contract`: **86 rutas**.
- `:neopen:assembleDebug`, `:neopen:assembleDebugAndroidTest` y
  `:live:compileDebugKotlin`: correctos. No cambia el esquema Room.
- Chrome real contra servidor y SQLite temporales: PATCH y DELETE simulando
  Android aparecen en la web tras el sondeo normal de 20 s, incluso sin cambiar
  companion.at. También se verificó migración de dos revisiones con reintento.

Reproducción de la suite Android:

```bash
ANDROID_HOME=/home/borja/Android/Sdk ./gradlew checkApiContract \
  :core:testDebugUnitTest :neopen:testDebugUnitTest :neopen:assembleDebug \
  :neopen:assembleDebugAndroidTest :live:compileDebugKotlin \
  -Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2
```

## Compatibilidad y límites

Actualizar servidor y app conjuntamente para usar la migración nueva. Un servidor
antiguo devuelve 404 y la app conserva las filas y el outbox, mostrando el error.
El snapshot OpenAPI está marcado como trabajo no publicado; no hay tag nuevo,
push ni despliegue. Los rechazos permanentes permanecen pendientes hasta corregir
su causa; no hay descarte automático ni nueva interfaz de resolución de conflictos.

No se ha ejecutado la instrumentación en dispositivo físico ni verificado BLE con
un bolígrafo real. Compilar el APK de androidTest no equivale a ejecutar esos tests.
Las pruebas cubren los ocho defectos descritos, no certifican ausencia de otros
errores en toda la aplicación ni recuperan datos ya perdidos por versiones antiguas.
