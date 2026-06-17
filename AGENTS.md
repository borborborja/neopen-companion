# AGENTS.md

Guía para agentes de código (Claude Code, Cursor, etc.) en **neopen-companion**.
El documento canónico y más completo es [CLAUDE.md](CLAUDE.md); las guías por tema
están en [`docs/`](docs/). Este fichero resume lo imprescindible.

## Proyecto

App **Android nativa** (Kotlin + Jetpack Compose) para el Neo smartpen M1+.
Captura por BLE y funciona en **modo local** (todo en el teléfono) o **modo
servidor** (sync con [`neopen`](https://github.com/borborborja/neopen)). Cada
capacidad (OCR, LanguageTool, marcadores, conectores) corre en el teléfono o en
el servidor con un conmutador de 3 estados + failover.

Módulos Gradle: `:core` (BLE, REST, settings, capacidades), `:neopen` (la app que
se publica), `:live` (IME slim con ML Kit). Solo `:core/:neopen/:live` se compilan.

## Comandos (sin `java` en el PATH)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :neopen:assembleDebug        # APK
./gradlew :core:testDebugUnitTest      # tests de lógica pura
```

## Reglas críticas (no romper)

1. **Migraciones Room nunca destructivas** (migración explícita + subir `version`).
2. **El contrato con el servidor está en `core/.../data/Api.kt`**; cambiar un lado
   obliga a actualizar el otro ([docs/INTEGRATION.md](docs/INTEGRATION.md)).
3. **Secretos cifrados** con `PwCrypto`; el servidor nunca expone secretos.
4. **Capacidades**: el despacho y el failover los centraliza `CapabilityDispatcher`
   ([docs/CAPABILITIES.md](docs/CAPABILITIES.md)). El prompt local es solo local.
5. **UI por `stringResource`** en `values/` + `values-en/` + `values-ca/`.
6. **Mutaciones por outbox** (offline-first), no REST de escritura directo.

## Convenciones

Comentarios/UI en español (UI también en ca/en). Rama → PR → merge (no commits a
`main`). Commits terminan en `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
Release: sube `versionCode`+`versionName` y taggea `vX.Y.Z` (CI publica el APK).

## Verificación

`./gradlew :neopen:assembleDebug` sin errores; `:core:testDebugUnitTest` si tocaste
`:core`. Detalle en [docs/VERIFICATION.md](docs/VERIFICATION.md).

Más en [CLAUDE.md](CLAUDE.md) y [`docs/`](docs/).
