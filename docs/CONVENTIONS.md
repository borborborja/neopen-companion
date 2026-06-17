# Convenciones

## Código (Kotlin / Compose)

- **Idioma**: comentarios y mensajes de log en **español** (el codebase es así).
- **UI por recursos**: nunca literales en composables. `stringResource(R.string.x)`,
  con la clave en `res/values/strings.xml` (español, por defecto), `values-en/` y
  `values-ca/` (los idiomas vivos). El resto de locales caen al default; no hay
  enforcement de `MissingTranslation`.
- **Compose**:
  - No anides scrolls del mismo eje (la pantalla de Ajustes ya tiene un
    `verticalScroll` exterior; las tarjetas no llevan scroll propio).
  - Estado de formularios con `rememberSaveable(<objeto-config>) { mutableStateOf(...) }`:
    al guardar y recomponerse `settings`, la clave cambia y el draft se re-siembra
    del valor persistido. El `expanded` de un colapsable se keya en nada (no se
    cierra tras guardar).
  - APIs experimentales: `@OptIn(ExperimentalMaterial3Api::class)` en la función.
- **Settings**: se persisten con `SettingsStore` (DataStore). Los secretos se
  cifran con `PwCrypto` dentro de blobs JSON (patrón `encodeKnownPens`). Guardar =
  `vm.saveSettings(settings.copy(<solo-lo-que-cambia>))`.
- **Datos/sync**: mutaciones locales = optimista a Room + `enqueue(OutboxKind.…)`.
  No llames a REST de escritura directamente.
- **Room**: cualquier cambio de esquema → nueva `Migration` + subir
  `@Database(version)`. Nunca destructivo (ver [GOTCHAS.md](GOTCHAS.md)).
- **HTTP**: el cliente del servidor neopen es `ApiFactory`/`Api.kt`. Para servicios
  locales (OCR/LanguageTool/conectores) usa clientes OkHttp **propios** contra la
  URL del usuario, nunca `ApiFactory`.

## Git / releases (las exige el flujo del usuario)

- **Rama → commit → push → PR → merge.** Nunca commitees directo a `main`.
- Mensajes de commit terminan con:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- El cuerpo del PR termina con:
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
- Dos commits por release (como el historial): `feat(...): …` con el cambio y
  `release: vX.Y.Z (…)` con el bump de versión.
- **Versionado semver**, tags `vX.Y.Z`. Sube `versionCode` (+1, monótono;
  obligatorio para actualizar el APK) y `versionName` en `neopen/build.gradle.kts`.
- Antes de mergear, **espera a que el CI del PR pase en verde** (`gh run watch
  <id> --exit-status`; ojo: `timeout` no existe en macOS).

## Decisiones designadas por el usuario (no revertir sin permiso)

- Conmutador de capacidades de **3 estados explícitos** (Desactivado/Teléfono/
  Servidor); el failover vive dentro de "Servidor", no es un 4º estado.
- En modo Servidor, si el servidor falla y hay credenciales locales → el teléfono
  ejecuta y **resincroniza**.
- El **prompt del OCR local es solo local**; no se manda al servidor.
- En Ajustes hay que **dejar claro que se configura la versión local (teléfono)** y
  ofrecer **"Importar configuración del servidor"** (solo lo no-secreto).
- La pantalla de Ajustes debe ser **intuitiva**: pestañas legibles (scrollables) y
  **servicios colapsables** (cada capacidad en una tarjeta, colapsada por defecto).
- Conectores locales soportados: **WebDAV + FTP/SFTP**. rclone/SMTP solo servidor.
