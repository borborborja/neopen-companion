# v0.23.4 — Sincronización Android/web sin pérdida de operaciones

## Compatibilidad y actualización conjunta

Actualizar el servidor neopen a **v0.23.4** antes de vincular datos locales.
La nueva migración conserva los ids y las revisiones separadas; un servidor
anterior devuelve 404 y la app conserva la biblioteca y el outbox.

No cambia el esquema Room. `versionCode=37` permite instalar la actualización
sobre la build anterior con la misma firma. El APK publicado es de depuración,
como las versiones anteriores de este repositorio.

## Cambios

- El modo LOCAL no contacta con el servidor aunque guarde credenciales.
- La migración conserva OCR, papel, sellado, clasificación, orden y clones.
- Las ediciones tras crear una captura o cuaderno usan los ids confirmados.
- Los rechazos HTTP no descartan operaciones; se respeta Retry-After.
- Se conserva el borrado explícito del formato mediante paper:null.
- Los colores ARGB uint32 de la web ya no bloquean la bajada incremental.
- Confirmaciones transaccionales, protección de ediciones pendientes y 15
  regresiones de sincronización incorporadas a CI.

Se publican las correcciones de auditoría; los cambios locales preexistentes de
BLE, interfaz y F-Droid no forman parte de esta versión. BLE con bolígrafo físico
sigue pendiente de verificación. El detalle está en `docs/AUDIT-SYNC-2026-09-07.md`.
