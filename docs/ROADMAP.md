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

## Boli: solicitadas por el usuario

- ✅ **Contraseña/PIN del boli en la WEB**: paridad completada en `neopen` v0.22.0;
  el navegador gestiona el intercambio BLE y avisa del reset tras 10 fallos.
- ✅ **Android (`:neopen` + `:live`)**: sensibilidad de presión + "la presión modifica
  el grosor del trazo". El control de sensibilidad solo aparece si `SETTING_INFO`
  reporta FSR/sensibilidad; el toggle de presión→grosor vive en ajustes y afecta al
  render Android (preview, PNG/PDF/SVG, OCR y conectores locales).
- ✅ **Android (`:neopen` + `:live`)**: gestión de bolis emparejados. Se registran los
  bolis autorizados, "olvidar" evita la reconexión automática y el bloqueo a un MAC
  concreto impide conectar otros bolis.
- ✅ **Web/servidor (`neopen`)**: presión→grosor portada al render compartido/web en
  v0.22.0; la sensibilidad se oculta cuando el SDK no reporta compatibilidad.
- **(Solo Android) Wizard para rellenar páginas no digitalizadas con una foto**:
  conectar el boli y puntear ligeramente la página para detectar su SOBP → buscar si
  esa página/cuaderno existe → preguntar cómo proceder → el usuario hace una foto →
  detectar los trazos → pedir que compruebe si son correctos (si no, repetir foto) →
  marcar unos trazos (inicio/fin) para que el usuario los puntee en el papel y así
  calibrar y dimensionar correctamente los trazos fotografiados sobre la hoja.

## Ideas mayores (sin compromiso)

- HTR on-device estructurado (ML Kit da texto plano; el OCR estructurado necesita
  un LLM con visión). Hoy `:live` usa ML Kit solo para el IME.
- Cifrado de extremo a extremo (hoy el modelo es self-hosted con contraseña compartida).
- Mejoras de compatibilidad entre modelos Neo smartpen.

> Cuando completes una de estas, actualiza este fichero y `RELEASE.md`.
