# Capacidades: local vs servidor (lo más complejo del repo)

Desde **v0.19.0**, las "capacidades inteligentes" pueden ejecutarse en el teléfono
o en el servidor. **Léete esto antes de tocar OCR, LanguageTool, marcadores o
conectores.** Es la parte que un agente despistado rompe con más facilidad.

## Modelo

Cuatro capacidades despachables (`enum Capability` en `core/.../data/Capabilities.kt`):

| Capacidad | En el teléfono (PHONE) | En el servidor (SERVER) |
|---|---|---|
| `OCR` | `LocalOcrProvider` → API compatible OpenAI (`/chat/completions`) | `POST /api/captures/:id/transcribe` |
| `LANGUAGE_TOOL` | `LocalTextCorrector` → `POST /check` | post-proceso del pipeline del servidor |
| `MARKER_DETECTION` | `MarkerDetector` (puro Kotlin, sin credenciales) | `detectMarkers` del servidor |
| `CONNECTOR_SYNC` | `LocalConnectorSync` (WebDAV/FTP) | SyncEngine del servidor |

Cada una tiene un **conmutador de 3 estados** `CapabilitySource = OFF | PHONE | SERVER`
guardado en `AppSettings` (`ocrSource`, `languageToolSource`, `markerDetectionSource`,
`connectorSyncSource`). Por defecto: OCR y marcadores = `SERVER` (comportamiento
histórico); LanguageTool y conectores = `OFF`.

### Disponibilidad y atenuado (UI)
`Capabilities.kt` calcula:
- `phoneCapable(cap)` — hay credenciales locales utilizables (marcadores: siempre true).
- `availabilityOf(cap, serverReachable)` — `phoneAvailable` + `serverAvailable`
  (`serverAvailable` = no es modo LOCAL **y** `configured` **y** servidor accesible).
- `effectiveSource(...)` — degrada a OFF lo que ya no está disponible (para la UI).

La UI (tarjetas en `Ui.kt`) atenúa/deshabilita el segmento PHONE/SERVER según
`availabilityOf`, y muestra el modo actual en la cabecera de cada tarjeta colapsable.

## Despacho y failover — `CapabilityDispatcher`

**Toda la decisión de "quién ejecuta" está centralizada en
`neopen/.../capability/CapabilityDispatcher.kt`.** No repartas esta lógica por la UI
ni el ViewModel. `runOcr(captureId)`:

- `OFF` → no hace nada (`Skipped`).
- `PHONE` → pipeline local (si no hay credenciales → `Failed`).
- `SERVER` → intenta el servidor; **si falla (o no hay conexión) y hay credenciales
  locales, repliega al teléfono** y, si había servidor configurado, **encola la
  subida del resultado** (`syncBack`). El failover vive DENTRO del modo Servidor.

Pipeline local (`runLocal`), espejo del servidor:
1. `rasterizePng(cap.strokes, cap.paper)` → PNG.
2. `LocalOcrProvider.transcribe(png, prompt)` → `OcrResultDto`.
3. Si LanguageTool ≠ OFF y hay credenciales: corrige el texto (tragando errores,
   como el servidor).
4. Si marcadores ≠ OFF: `MarkerDetector.detect(text)` → fusiona **solo los tags de
   marcadores** con los existentes y sobrescribe título/kind (los tags del OCR
   viven en la transcripción, no en la captura — igual que el servidor).
5. `repo.applyLocalTranscription(...)` (escribe en Room).
6. Si `syncBack`: `graph.enqueueTranscriptionSync(id)`.

## Failover sync-back (outbox)

Cuando el teléfono transcribe en lugar de un servidor que falló, el resultado se
sube luego al servidor:
- `OutboxKind.PUSH_TRANSCRIPTION` + `repo.enqueueTranscription(id)` arma un
  `TranscriptionPushDto` desde la captura local ya aplicada.
- `SyncEngine.runOp` lo drena: `api.putTranscription(...)` →
  **`PUT /api/captures/:id/transcription`** (endpoint del servidor; lo añadimos en
  `neopen` v0.21.0). Si el servidor es antiguo y devuelve 404, la op se **descarta**
  (no rompe nada; la transcripción local ya está aplicada).

> ⚠️ El failover de la transcripción **depende de que el servidor tenga el endpoint
> `PUT /api/captures/:id/transcription`**. Ver [INTEGRATION.md](INTEGRATION.md).

## Proveedores locales (`core/.../capability/`)

Son ports 1:1 de la lógica del servidor (`apps/server/src/`):
- `LocalOcrProvider` ← `ocr/provider.ts` (mismo SYSTEM_PROMPT, `temperature 0.2`,
  `response_format json_object`, `parseLoose`/`normalize`). **Cliente OkHttp propio
  contra `ocrLocal.baseUrl`, nunca `ApiFactory`** (cuya base es el servidor neopen).
- `LocalTextCorrector` ← `languageTool/provider.ts`. El `applyMatches` (reemplazo por
  offset descendente) es **delicado**: port exacto, con test unitario en
  `core/src/test/.../CapabilityLogicTest.kt`.
- `MarkerDetector` ← `ocr/detect.ts`. `\p{L}`/`\p{N}` ya son Unicode en la JVM.
- `WebdavConnector` (OkHttp puro, 0 deps) y `FtpConnector` (Commons Net + JSch).

## Conectores locales — `LocalConnectorSync`

Espejo (mirroring) port de `apps/server/src/sync/engine.ts`. Reutiliza los render.
Conjunto deseado por destino (inbox/loose/notebook), `sourceHash` SHA-1, debounce
1,5 s; tabla `connector_sync_state` (Room, migración v6→v7). Solo corre con
`connectorSyncSource == PHONE`. Formatos por hoja: `png`, `pdf`, `md` (+ PDF-libro
por cuaderno). El failover de conectores **no aplica** (lo pidió el usuario solo
para la transcripción).

## Credenciales locales e "Importar del servidor"

Las credenciales locales (OCR baseUrl/model/apiKey/prompt, LanguageTool, WebDAV,
FTP) se editan en la pestaña **Capacidades** (tarjetas colapsables) y se guardan
cifradas (`PwCrypto`). El botón **"Importar configuración del servidor"** llama a
`GET /api/settings` y rellena lo **no-secreto** (URLs, modelo, usuarios, carpetas);
**el servidor enmascara los secretos** (`{configured, locked}` sin el valor), así
que las API keys/contraseñas se introducen a mano. El merge preserva los secretos
locales (`MainViewModel.importServerConfig`).

## Decisiones de diseño que NO debes revertir sin permiso

- **3 estados explícitos** (OFF/PHONE/SERVER), con iconos; el failover vive dentro
  de SERVER, no es un 4º estado.
- **El prompt del OCR local es SOLO local.** En modo Servidor se usa el prompt del
  servidor; `CapabilityDispatcher.runServer` pasa `instructions = null`.
- **Los secretos nunca se importan** (el servidor no los expone): la UI avisa.
- **rclone y SMTP son solo servidor** (rclone es un binario Go inviable en Android).
