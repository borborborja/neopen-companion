# Trampas conocidas — léelo antes de depurar

## Entorno / build

- **`java` NO está en el PATH.** Exporta
  `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` antes de
  cualquier `./gradlew`.
- **`timeout` no existe en macOS.** No lo uses en scripts; para esperar al CI usa
  `gh run watch <id> --exit-status`.
- **El emulador no tiene Bluetooth.** Para probar captura BLE necesitas un
  dispositivo físico (la UI sí se puede revisar en emulador).
- **`:companion` está retirado del build** (`settings.gradle.kts` solo incluye
  `:core/:neopen/:live`). Su código sigue en disco; **ignóralo**, no lo "arregles".

## Datos / Room

- **Migraciones nunca destructivas.** Solo `fallbackToDestructiveMigrationFrom(1)`
  está permitido (v1 efímera). Toda v2→vN tiene `Migration` explícita. Si añades una
  columna/tabla y no subes `@Database(version)` + migración, Room **borra la BD del
  usuario** en la siguiente actualización. La versión actual es **7**.
- **Outbox y last-write-wins.** Las escrituras locales son optimistas a Room + op en
  el outbox. `SyncEngine` repunta ids temporales→reales (`outboxRepoint`). No subas
  cosas por REST saltándote el outbox: rompes el orden y la reconciliación.

## Seguridad / secretos

- **`PwCrypto` cifra con una clave del Keystore ligada al dispositivo.** Al restaurar
  un backup en otro móvil, los secretos se pierden (descifrado → ""); el usuario los
  reintroduce. No asumas que un secreto guardado sobrevive a un cambio de dispositivo.
- **El servidor nunca expone secretos** (`GET /api/settings` enmascara API keys y
  contraseñas como `{configured, locked}`). "Importar del servidor" trae solo lo
  no-secreto; no esperes recuperar claves por la API.

## Capacidades

- **`LocalOcrProvider`/`LocalTextCorrector` usan clientes OkHttp propios** contra la
  URL del usuario. **Nunca uses `ApiFactory`** (su base es el servidor neopen).
- **`applyMatches` (LanguageTool) es delicado**: reemplazo por offset descendente con
  guardas. Si lo tocas, corre `:core:testDebugUnitTest`.
- **El prompt del OCR local es solo local.** No lo mandes al `/transcribe` del
  servidor (decisión del usuario).
- **El failover de la transcripción depende del endpoint
  `PUT /api/captures/:id/transcription`** del servidor (neopen ≥ v0.21.0). Sin él,
  la op del outbox se descarta (la transcripción local ya está aplicada).
- **URLs del servidor pueden ser internas** (Docker, p. ej. `http://ollama:11434`):
  importadas al teléfono pueden no ser accesibles. El usuario debe revisarlas.

## Contrato cross-repo

- **Los desajustes fallan en SILENCIO.** Moshi ignora campos no declarados y el
  servidor descarta lo que no valida: el build no rompe, pero el dato se pierde.
  Cambia siempre los **dos** lados ([INTEGRATION.md](INTEGRATION.md)).

## Compose / UI

- **No anides scrolls del mismo eje** (Ajustes ya tiene `verticalScroll` exterior).
- **APIs experimentales** (`SecondaryScrollableTabRow`…) requieren
  `@OptIn(ExperimentalMaterial3Api::class)` en la función.
- **`rememberSaveable` de drafts**: keya en el objeto de config (no en nada), o un
  draft viejo pisará al guardar; el `expanded` de un colapsable, al revés (en nada).
- **i18n**: añade las claves a `values/` + `values-en/` + `values-ca/`. `assembleDebug`
  falla si usas una clave que no existe en el default.
