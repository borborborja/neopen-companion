# Integración con el servidor (cross-repo)

El ecosistema son **dos repos** que evolucionan juntos:

| Repo | Qué es | Aquí |
|---|---|---|
| **neopen-companion** (este) | App Android | este repositorio |
| **neopen** | Servidor (Fastify+SQLite) + web (React) + tipos compartidos | repositorio [`neopen`](https://github.com/borborborja/neopen) |

> **Regla de oro:** muchas funciones del companion **dependen del servidor**. Una
> capacidad nueva del cliente solo funciona de punta a punta si el servidor está
> actualizado. **Un servidor antiguo descarta los campos que no conoce** → el dato
> no sincroniza. Si tocas el contrato, actualiza los **dos** lados y publícalos.

## Dónde vive el contrato

- **Fuente canónica**: `neopen/docs/openapi.yaml` (OpenAPI 3.1, exhaustivo).
- **Snapshot vendorizado aquí**: `docs/openapi.yaml`, byte a byte idéntico al
  contrato canónico indicado en `docs/openapi.snapshot.json`. El manifiesto fija
  su SHA-256; `sourceState: working-tree` identifica cambios conjuntos aún sin
  publicar sobre la versión base indicada por `serverTag`.
  `./gradlew checkApiContract` comprueba checksum, que `serverTag` corresponda a
  `info.version` del snapshot y que toda anotación Retrofit exista en la
  especificación. También comprueba el formato estable `X.Y.Z` de la app.

  La versión de Android es independiente de la versión de su contrato de
  servidor: una corrección exclusiva del cliente puede publicarse sin cambiar
  el snapshot ni publicar el servidor. **Android v0.26.1** mantiene el contrato
  **servidor v0.26.0**, sin cambios de endpoints, DTOs ni esquema Room.

- **Cliente Android**: `core/src/main/java/net/micapum/neopen/core/data/Api.kt`
  (interfaz Retrofit `NeopenApi` + DTOs Moshi). `ApiFactory.create(baseUrl)` lo
  construye. La contraseña va en la cabecera **`X-Auth-Password`** en cada petición
  (todas las rutas `/api/*` salvo `/api/health` la exigen).
- **Servidor**: `apps/server/src/routes/*.ts` + tipos en `packages/shared/src/index.ts`.
  Referencia: `neopen/docs/API.md` y `neopen/docs/openapi.yaml`.

**Si cambias un endpoint o el shape de un DTO en un lado, cámbialo en el otro.**
Moshi (Android) ignora campos no declarados y el servidor descarta lo que no valida,
así que un desajuste **falla en silencio** (no rompe el build, pero el dato se pierde).

## Endpoints que el companion usa (resumen)

- **Auth/health**: `GET /api/health` (público), `GET /api/capabilities` (valida la
  contraseña).
- **Sync (offline-first)**: `GET /api/changes?since=<cursor>` (pull delta),
  `POST /api/captures`, `PATCH /api/captures/:id`, `DELETE /api/captures/:id`,
  notebooks (`POST/PATCH/DELETE`), `PUT /api/notebooks/:id/order`, etc. Todo pasa por
  el **outbox** (`SyncEngine`).
- **OCR**: `POST /api/captures/:id/transcribe` (modo Servidor).
- **OCR failover**: `PUT /api/captures/:id/transcription` — sube una transcripción
  hecha en el teléfono. **Añadido en `neopen` v0.21.0.** Si el servidor es anterior,
  devuelve 404 y la operación se conserva pendiente con error (no se pierde la
  transcripción local). Actualizar el servidor antes de reintentar.
- **Vinculación local→servidor**: `PUT /api/migration` recibe como máximo un
  cuaderno y una captura por petición, con ids estables y metadatos completos.
  Se encolan primero los cuadernos. Es aditivo e idempotente por id, sin fusión
  por SOBP ni borrado de Room. Los reintentos vuelven a tomar un snapshot local,
  incluyendo las ediciones hechas después de un fallo. Requiere servidor
  **v0.23.4 o posterior**; no usa `/api/import`.
  La ingesta BLE comparte un bloqueo con la migración: las páginas que lleguen
  durante el snapshot esperan hasta terminar la transición a FULL y entonces se
  guardan con outbox. El primer pull nunca puede sobrescribir esos trazos. Un
  error o cancelación libera el bloqueo y conserva el modo y los datos locales.
- **Cambio de servidor**: Room, cursor y outbox conservan un vínculo persistido
  al servidor propietario. Si existen capturas, cuadernos o escrituras pendientes,
  Ajustes rechaza otra URL y conserva la configuración y los datos actuales; hay
  que sincronizar/exportar y cerrar sesión explícitamente antes de cambiar. El
  motor también rechaza una URL distinta introducida por otra vía, incluso tras
  reiniciar, sin enviar pendientes ni hacer pull del servidor equivocado. Volver
  a la URL original permite continuar. Con biblioteca vacía se reinician cursor,
  cachés y contadores; cambiar contraseña, mayúsculas del host o barra final no
  cambia la identidad del servidor.
- **Decisiones de reimportación**: el DTO Android envía `mergeMode: forceNew`
  cuando se pide otra captura, o `mergeMode: mergeTarget` con `targetCaptureId`
  cuando se elige fusionar. Esos campos ya existían en el OpenAPI canónico; no
  requieren cambiar la versión del contrato. Los IDs del payload se repuntan al
  confirmar una captura temporal.
- **Reintentos**: no se elimina ningún rechazo HTTP del outbox. Solo DELETE+404
  cuenta como éxito idempotente. `Retry-After` queda persistido junto al error;
  no se realiza pull mientras haya escrituras pendientes. El modo LOCAL no
  ejecuta push/pull aunque conserve credenciales.
- **Importar config**: `GET /api/settings` — devuelve la config del servidor **sin
  secretos** (los enmascara como `{configured, locked}`). Lo usa el botón "Importar
  del servidor" para rellenar lo no-secreto.
- **Otros**: overlap (`GET/PATCH /api/overlap`, `POST /api/captures/:id/split`),
  finish-mode, sobp-scans, anchors/backgrounds, studio (recents/presets), export/import.

## Responsabilidades (quién hace qué)

- **Render** (SVG/PNG/PDF): **en el cliente** (Android y web). El servidor solo
  rasteriza para su propia sincronización a conectores.
- **Capacidades** (OCR/LanguageTool/marcadores/conectores): el teléfono puede
  ejecutarlas en local (con credenciales propias) **o** delegar en el servidor. Ver
  [CAPABILITIES.md](CAPABILITIES.md).
- **Inbox/cuadernos**: en modo servidor viven en el servidor; en modo local, en Room.

## Al evolucionar el contrato — checklist

1. ¿El cambio es aditivo (campo opcional nuevo)? El servidor antiguo lo ignora; está
   bien, pero el dato no se usará hasta actualizar el servidor.
2. ¿Es incompatible (renombrar/eliminar/cambiar tipo)? Es **breaking**: actualiza
   ambos repos y destácalo en las notas de la release (ver
   [RELEASE.md](RELEASE.md) y `neopen/docs/DESPLIEGUE.md`).
3. Añade/ajusta el DTO en `Api.kt` (Android) y el schema/route en el servidor.
4. Copia el OpenAPI canónico al snapshot, actualiza su manifiesto y ejecuta
   `./gradlew checkApiContract :core:testDebugUnitTest :neopen:assembleDebug`.
   En servidor ejecuta typecheck, build, tests y `pnpm check:contract`.
5. Publica **servidor y app**. El redeploy de una instancia autoalojada es una
   operación separada que solo debe hacerse cuando se haya autorizado expresamente.
