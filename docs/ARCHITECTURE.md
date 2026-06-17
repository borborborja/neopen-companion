# Arquitectura de neopen-companion

App Android nativa (Kotlin + Jetpack Compose, Material 3). `minSdk 26`,
`targetSdk 34`, JDK 17, `applicationId = net.micapum.neopen`.

## Módulos (Gradle)

```
core/   (com.android.library)  — compartido, se expone con api(...)
  net.micapum.neopen.core
    data/      Api.kt (Retrofit + DTOs), ApiFactory, SettingsStore (DataStore +
               PwCrypto), Capabilities.kt (modelo de capacidades), SyncStatusStore,
               OverlapDetect
    pen/       PenManager (BLE), Protocol (framing v2), PenModels, PaperLookup
    capability/ Providers.kt (interfaces), LocalOcrProvider, LocalTextCorrector,
               MarkerDetector, WebdavConnector, FtpConnector, ConnectorPaths
    sync/      Uploader (página del boli → servidor)

neopen/ (com.android.application)  — LA APP QUE SE PUBLICA
  net.micapum.neopen.app
    NeopenFullApp.kt   Application + Graph (service locator) + migraciones Room
    ui/        MainViewModel, Ui.kt (toda la UI Compose), theme/
    capability/ CapabilityDispatcher (orquesta OCR/LT/markers + failover),
               LocalConnectorSync (espejo a conectores)
    data/      NeopenRepository, Models (dominio + Json/Moshi), Mappers
    db/        NeopenDb (Room: entidades, DAOs, @Database version)
    sync/      SyncEngine (push/pull + outbox), SyncWorker, PenService, control notif
    render/    Render/Export (SVG/PNG/PDF), InkCanvas, OverlapPreview
    widget/    widgets de inicio

live/   (com.android.application)  — app slim aparte
  IME de escritura en vivo con ML Kit Digital Ink (on-device). Comparte :core.
```

`settings.gradle.kts` incluye solo `:core`, `:neopen`, `:live`. **`:companion`
quedó retirado del build** (su código sigue en disco pero no se compila; ignóralo).

## Flujo de datos

```
Boli M1+  ──BLE──▶  PenManager (core/pen)  ──dots──▶  StrokePage
                                                          │ al finalizar página
                                                          ▼
                                   NeopenRepository.ingestPenPage[Local]
                                                          │
                         modo LOCAL  ┌─────────────────────┴─────────────────────┐  modo FULL/BRIDGE
                                     ▼                                            ▼
                              Room (neopen.db)                      Room + outbox → SyncEngine → REST → servidor
```

- **Lecturas**: la UI lee SIEMPRE de Room vía Flows (`NeopenRepository`).
- **Escrituras locales**: optimistas a Room + op en el **outbox** (FIFO). El
  `SyncEngine` drena el outbox (`push`) y aplica los cambios del servidor (`pull`).
- **Modo local** (`AppMode.LOCAL`): no hay servidor; el archivado y la detección
  de superposición se hacen en el dispositivo.

## Subsistemas clave

### Graph (inyección de dependencias)
`NeopenFullApp.Graph` es un *service locator* simple: crea y comparte `db`,
`settings`, `repo`, `sync`, `dispatcher`, `connectorSync`, `pen`, `online`, etc.
Acceso desde Composables/Workers con `Context.graph`.

### Persistencia (Room)
`db/NeopenDb.kt`: entidades `captures`, `notebooks`, `outbox`, `sync_meta`,
`page_backgrounds`, `connector_sync_state`. **Versión actual: 7.** Las migraciones
están en `NeopenFullApp.kt` (`MIGRATION_2_3 … MIGRATION_6_7`). Regla de oro:
nunca destructivas (ver [GOTCHAS.md](GOTCHAS.md)).

### Settings (DataStore + cifrado)
`core/.../data/SettingsStore.kt`: `AppSettings` (data class) persistido en
`androidx.datastore.preferences`. Los grupos de credenciales se guardan como
**blobs JSON** (igual que `knownPens`) y **solo el campo secreto va cifrado** con
`PwCrypto` (AES/GCM del Keystore, ligado al dispositivo, fuera del backup).

### Capacidades (lo más complejo)
OCR/LanguageTool/marcadores/conectores, cada uno con `CapabilitySource =
OFF|PHONE|SERVER`. Despacho y failover en `CapabilityDispatcher`. Detalle completo
en [CAPABILITIES.md](CAPABILITIES.md).

### Sync (offline-first)
`sync/SyncEngine.kt`: `push()` drena el outbox (FIFO, last-write-wins, repunta ids
temporales→reales), `pull()` aplica `/api/changes?since=cursor`. `SyncWorker`
(WorkManager) lo ejecuta periódicamente o a demanda. `OutboxKind` lista los tipos
de op (incluido `PUSH_TRANSCRIPTION` para el failover del OCR).

### Render
`render/`: `rasterizePng`, `renderSvg`, `renderPagePdf`, `renderNotebookPdf`.
**El render es en el dispositivo** (igual que la web). El OCR local rasteriza con
`rasterizePng`; los conectores locales generan png/pdf/md.

## CI

- `.github/workflows/build-apk.yml`: en push a `main`, PRs y tags `v*`. Compila
  `:neopen:assembleDebug`, sube el APK como artifact, actualiza la prerelease
  "latest" en `main` y **publica la release de GitHub con el APK en tags `v*`**.
- `.github/workflows/build-live-apk.yml`: build de `:live`.
