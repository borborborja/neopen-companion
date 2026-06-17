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
   ./gradlew :neopen:assembleDebug          # + :core:testDebugUnitTest si tocaste :core
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
   `build-apk.yml`, que compila `neopen-debug.apk` y **publica la release** (no hay
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
   # -> { "draft": false, "assets": ["neopen-debug.apk"] }
   ```

## Notas

- **No tagear por debajo del último tag** (`git tag --sort=-v:refname | head`): GitHub
  podría no marcar la release como «Latest».
- **Cliente vs servidor**: si la versión añade campos al contrato, el **servidor debe
  estar al día** o no sincroniza. Destaca los **breaking changes** en las notas de la
  release. Ver [INTEGRATION.md](INTEGRATION.md) y `neopen/docs/DESPLIEGUE.md`.
- La release "latest" (prerelease) se actualiza en cada push a `main`; las versionadas
  (`vX.Y.Z`) son las estables.
- **Servidor (repo `neopen`)**: su release es aparte (tag `v*` → imagen Docker;
  push a `main` → `:latest`). Si una feature del companion necesita un endpoint nuevo
  del servidor, publica también el servidor.

## Historial de releases relevantes

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
