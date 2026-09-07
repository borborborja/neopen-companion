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

- Auditoría de los cambios BLE/UI antes excluidos: ajustes confirmados por el boli,
  cola protegida frente a ACKs tardíos y reconexión con estado limpio.
- Volcado offline iniciado por la respuesta de la lista, sin carreras entre UI,
  autoimportación y workers. Un timeout no se anuncia como importación correcta.
- Persistencia FIFO: páginas → contadores → sincronización. No se marca importada
  una nota cuya escritura en Room haya fallado.
- Se conservan las páginas pendientes al entrar en escritura en vivo y se mantiene
  en cola la restauración de grabación offline al salir.
- Interfaz con confirmaciones/errores en español, catalán e inglés.
- Receta F-Droid actualizada: solo `:neopen`, sin ML Kit de `:live` y APK sin firma,
  incluso con variables de firma configuradas. No supone publicación en F-Droid.

BLE con bolígrafo físico sigue pendiente de verificación. Detalles en
`docs/AUDIT-SYNC-2026-09-07.md` y `docs/AUDIT-BLE-2026-09-07.md`.
