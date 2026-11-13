# Release y despliegue (Android)

Publicar una versión del companion = **subir versión + tag**; la CI compila el APK
y crea la **release de GitHub** con el APK adjunto. El runbook completo de los dos
repos está en `neopen/docs/DESPLIEGUE.md`; aquí va el lado Android.

## Pasos

1. **Sube la versión** en `neopen/build.gradle.kts`:
   - `versionCode` += 1 (entero monótono; **obligatorio** para instalar la
     actualización).
   - `versionName` = `"X.Y.Z"` (semver; patch=arreglos, minor=features, major=ruptura).
   > Solo se publica el módulo `:neopen`. `:companion` está fuera del build.

2. **Verifica en local**:
   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
   ./gradlew checkApiContract checkFileModes :core:testDebugUnitTest :neopen:testDebugUnitTest :neopen:assembleDebug :neopen:assembleGithub
   ```

3. **Rama → commit → push → PR → merge** (dos commits: feature + `release: vX.Y.Z`):
   ```bash
   git checkout -b feat/...
   git add -A && git commit               # feature
   git add neopen/build.gradle.kts && git commit   # release: vX.Y.Z (...)
   git push -u origin <rama>
   gh pr create --base main --title "..." --body "..."
   ```

4. **Espera el CI del PR en verde y luego mergea** (no publiques una release rota):
   ```bash
   gh run watch <run-id-del-PR> --exit-status     # NO uses `timeout` (no existe en macOS)
   gh pr merge <n> --merge --delete-branch
   ```

5. **Tag** (apuntando a `main` ya actualizado). El push del tag dispara
   `build-apk.yml`, que compila el APK optimizado `neopen.apk` y **publica la release** (no hay
   que crearla a mano):
   ```bash
   git checkout main && git pull --ff-only origin main
   git tag -a vX.Y.Z -m "vX.Y.Z — ..."
   git push origin vX.Y.Z
   ```

6. **Verifica la publicación**:
   ```bash
   gh run watch <run-id-del-tag> --exit-status
   gh release view vX.Y.Z --json isDraft,assets --jq '{draft:.isDraft, assets:[.assets[].name]}'
   # -> { "draft": false, "assets": ["neopen.apk"] }
   ```

## Notas

- GitHub distribuye la variante `github`, no depurable y optimizada con R8.
  Conserva el identificador y el certificado de los APK anteriores, de modo que
  se puede actualizar sin reinstalar. `debug` sigue disponible para desarrollo;
  las rutas de firma de Play y del candidato F-Droid no cambian.
- La CI comprueba el APK minificado en un job separado con
  `-PinstrumentedBuildType=github :neopen:connectedGithubAndroidTest`.

- **No tagear por debajo del último tag** (`git tag --sort=-v:refname | head`): GitHub
  podría no marcar la release como «Latest».
- **Cliente vs servidor**: si la versión añade campos al contrato, el **servidor debe
  estar al día** o no sincroniza. Destaca los **breaking changes** en las notas de la
  release. Ver [INTEGRATION.md](INTEGRATION.md) y `neopen/docs/DESPLIEGUE.md`.
- Las correcciones exclusivas de Android tienen versión propia. El manifiesto
  OpenAPI conserva el tag del contrato del servidor utilizado; no se sube su
  versión solo para igualarla con `versionName`. Android v0.26.1 sigue siendo
  compatible con el contrato de servidor v0.26.0.
- La release "latest" (prerelease) se actualiza en cada push a `main`; las versionadas
  (`vX.Y.Z`) son las estables.
- **Servidor (repo `neopen`)**: su release es aparte (tag `v*` → imagen Docker;
  push a `main` → `:latest`). Si una feature del companion necesita un endpoint nuevo
  del servidor, publica también el servidor.

## Historial de releases relevantes

- **v0.26.1** — Arranque y scroll optimizados: miniaturas preparadas fuera del
  hilo principal, caché de 24 MiB, listas ligeras y menos cargas repetidas de
  ajustes y sincronización. GitHub distribuye `neopen.apk`, no depurable y
  optimizado con R8, con el mismo identificador y certificado de los APK
  anteriores. Actualización comprobada conservando tinta y ajustes cifrados.
  Sin migraciones de datos ni cambios de contrato; compatible con servidor
  v0.26.0; `versionCode=42`. Las mejoras medidas corresponden al emulador API 34;
  la validación física de fluidez en el Pixel 8a permanece pendiente.

- **v0.26.0** — Reconoce la plantilla «Hoja en blanco» de Ncode Studio y muestra
  su nombre traducido en las secciones sincronizadas. La creación y generación
  A4/A5 se realizan en Studio web con servidor v0.26.0. Sin migraciones de datos;
  `versionCode=41`.

- **v0.25.0** — Libros de Ncode Studio por secciones con asignaciones por
  bolígrafo, previsualización, contador de códigos, operaciones por outbox y
  resolución explícita de conflictos hacia instancias actuales o históricas.
  Identidad de origen por trazo y asignación congelada por lote offline.
  Copias ZIP por streaming y migración Room 9→10 aditiva, conservando los datos.
  Studio permanece oculto hasta confirmar su activación en el servidor; apagarlo
  conserva libros, operaciones pendientes y diseños históricos. Las funciones
  nuevas requieren servidor v0.25.0; `versionCode=40`. La comprobación física de
  libretas impresas con el M1+ permanece pendiente y no se anuncia como validada.

- **v0.23.5** — Conserva la tinta al desconectar o cambiar de boli y durante la
  vinculación con el servidor; protege los datos y operaciones pendientes al
  cambiar de URL. Reimportaciones explícitas, contadores offline reiniciados al
  vaciar datos y exportaciones independientes por hoja, cuaderno y destino.
  Sin cambio de esquema Room ni ruptura de API; `versionCode=38`.

- **v0.23.3** — Sanea el contrato cross-repo: snapshot OpenAPI canónico con
  checksum, tests Moshi de DTOs críticos, esquemas Room históricos 2..9 y pruebas
  instrumentadas de migraciones y outbox/delta-sync en Android 14. Sin cambio
  incompatible de datos ni API.

- **v0.22.0** — Render: en modo servidor, la presión→grosor usa los ajustes del
  servidor (`GET /api/render-settings`) como fuente de verdad, para que web y Android
  rendericen igual; en modo local sigue mandando el ajuste local. Requiere
  `neopen` ≥ v0.22.0.
- **v0.21.6** — Widgets mejorados: cuadernos y ediciones recientes con botón de
  compartir, y widget del boli con estado, batería, volcado y conexión permanente.
- **v0.21.5** — Reconexión automática del boli mientras la app está abierta,
  preparación para F-Droid (`-Pfdroid`, metadata, licencia y screenshots) y textos
  públicos genéricos para Neo smartpen.
- **v0.21.4** — Ajuste de contraste para presión→grosor en `:neopen`/`:live` y
  pestañas de Ajustes con iconos para evitar cortes visuales en pantallas estrechas.
- **v0.21.3** — Boli: sensibilidad de presión en `:neopen`/`:live`, ajuste de
  presión→grosor para render/export/OCR/conectores locales, bolis conocidos con
  "olvidar" y bloqueo a un boli concreto. Android: avisos de reimportación offline
  para páginas eliminadas/movidas y política por defecto configurable.
- **v0.19.0** — capacidades local/servidor (OCR/LanguageTool/marcadores/conectores
  en el teléfono o servidor + failover). Requiere `neopen` ≥ v0.21.0 para el
  sync-back del failover (`PUT /api/captures/:id/transcription`).
- **v0.20.0** — Ajustes rediseñados: pestañas scrollables, tarjetas colapsables por
  servicio, "Importar configuración del servidor". Solo cliente.
