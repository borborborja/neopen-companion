# Metas futuras y tareas planificadas

Tenlas en cuenta **al programar ahora**: no cierres puertas a estas direcciones.

## Principios a preservar

- **El modelo de capacidades es extensible.** Añadir una capacidad nueva debe
  seguir el mismo patrón: entrada en `enum Capability`, conmutador en `AppSettings`,
  proveedor local en `core/.../capability/`, despacho en `CapabilityDispatcher`,
  tarjeta colapsable en `Ui.kt`. No metas atajos que asuman "solo estas 4".
- **Los proveedores locales son ports 1:1 del servidor.** Si el servidor cambia su
  pipeline (OCR/LanguageTool/marcadores), refleja el cambio en el port para que
  teléfono y servidor den el mismo resultado.
- **Contrato con el servidor sincronizado** (ver [INTEGRATION.md](INTEGRATION.md)).
- **Migraciones Room siempre hacia delante** (nunca recrear; ver [GOTCHAS.md](GOTCHAS.md)).
- **Offline-first**: cualquier acción nueva que escriba debe pasar por el outbox.

## Tareas planificadas (pendientes)

- **Botones "Probar conexión" por servicio** en las tarjetas de Capacidades
  (OCR/LanguageTool/WebDAV/FTP): validar credenciales en vivo sin guardar, estilo
  `ServerCheck`. Las tarjetas dejan el hueco listo. (Hay un chip de tarea abierto
  para esto.)
- **Indicador de estado de sync de conectores** por captura/cuaderno (espejo del
  `SyncBadge` de la web), leyendo `connector_sync_state`.
- **Config de conectores por colección/formato** (hoy `LocalConnectorSync` usa un
  set fijo `png/pdf/md` y sincroniza todas las colecciones). Permitir elegir
  formatos y destinos como en el servidor.
- **Aviso de URLs internas** al importar del servidor (p. ej. `http://ollama:11434`
  no es accesible desde el teléfono): resaltar las que probablemente no funcionen.
- **Promover el prompt del OCR** a ajuste de primer nivel si se decide que debe
  aplicar también al servidor (hoy es local-only por decisión del usuario — no lo
  cambies sin pedirlo).

## Ideas mayores (sin compromiso)

- HTR on-device estructurado (ML Kit da texto plano; el OCR estructurado necesita
  un LLM con visión). Hoy `:live` usa ML Kit solo para el IME.
- Cifrado de extremo a extremo (hoy el modelo es self-hosted con contraseña compartida).
- Soporte de más modelos de boli (probado solo con el M1+ / NWP-F51).

> Cuando completes una de estas, actualiza este fichero y `RELEASE.md`.
