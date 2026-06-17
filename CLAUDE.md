# CLAUDE.md — guía para programar en neopen-companion

> Léeme **antes de tocar nada**. Este fichero evita los errores más comunes del
> repo. Hay guías detalladas en [`docs/`](docs/). El repo del **servidor** es
> [`neopen`](https://github.com/borborborja/neopen); muchas funciones del
> companion dependen de él
> (ver [docs/INTEGRATION.md](docs/INTEGRATION.md)).

## Qué es neopen-companion

App **Android nativa** (Kotlin + Jetpack Compose, Material 3) para el **Neo
smartpen M1+ (NWP-F51)**. Captura la escritura por **Bluetooth LE** (en vivo y
volcando la memoria offline del boli) y, según el modo:

- **Modo servidor** — sube las páginas a un servidor [`neopen`](https://github.com/borborborja/neopen) que las archiva en cuadernos y sincroniza.
- **Modo local** — todo **en el teléfono**, sin servidor. Se puede vincular un servidor más tarde sin perder nada.

Desde **v0.19.0** cada "capacidad inteligente" (OCR/transcripción, corrección
LanguageTool, detección de marcadores, sincronización a conectores) puede
ejecutarse **en el teléfono o en el servidor** mediante un conmutador de 3
estados, con **repliegue automático** (failover). Esto es lo más complejo del
repo: léete [docs/CAPABILITIES.md](docs/CAPABILITIES.md) antes de tocarlo.

```
Boli (BLE) → PenManager capta dots → agrupa en página (StrokePage)
   │ al finalizar la página
   ▼
Room (neopen.db)  ─ modo local: se queda aquí
   │              ─ modo servidor/completo: + outbox → REST → servidor
   ├─ Transcribir (OCR) → CapabilityDispatcher → teléfono o servidor
   ├─ Conectores (WebDAV/FTP) → LocalConnectorSync (modo teléfono) o servidor
   └─ Exportar/render (SVG/PNG/PDF) en el dispositivo
```

## Estructura (módulos Gradle)

```
core/     Librería compartida: cliente REST (Retrofit/Moshi), BLE (PenManager,
          protocolo v2), settings (DataStore + cifrado), capacidades (providers,
          conectores). Se expone con `api(...)` para que :neopen lo herede.
neopen/   App unificada (puente + completo + local): UI Compose, Room, sync
          (outbox), render, workers, dispatcher de capacidades. ES LA QUE SE PUBLICA.
live/     App slim aparte: boli + teclado de escritura en vivo (IME) con ML Kit.
```

`settings.gradle.kts` incluye **solo** `:core`, `:neopen`, `:live`. El antiguo
`:companion` quedó retirado del build (su código sigue en disco pero NO se
compila). Mapa de módulos y subsistemas → [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Comandos (el JDK NO está en el PATH de este equipo)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

./gradlew :neopen:compileDebugKotlin :core:compileDebugKotlin   # check rápido
./gradlew :neopen:assembleDebug                                 # APK completo
./gradlew :core:testDebugUnitTest                               # tests unitarios (:core)
./gradlew :live:compileDebugKotlin                              # :live también depende de :core
```

Se necesita un **dispositivo físico** con BLE (el emulador no tiene Bluetooth).
El SDK de Android se resuelve por `local.properties` (`sdk.dir`). Cómo verificar
cada tipo de cambio → [docs/VERIFICATION.md](docs/VERIFICATION.md).

## ⚠️ Reglas que NO debes romper

1. **Migraciones Room: NUNCA destructivas.** El esquema arrancó en v2; de v2 en
   adelante SIEMPRE hay `Migration` explícita en `NeopenFullApp.kt`
   (`MIGRATION_x_y`) y `@Database(version = N)` se sube a la vez. Solo está
   permitido `fallbackToDestructiveMigrationFrom(1)` (la v1 efímera de dev).
   Si añades/cambias una columna o tabla: nueva migración + subir `version`.

2. **El contrato con el servidor vive en `core/.../core/data/Api.kt` (Retrofit).**
   Si cambias un endpoint o DTO aquí, el servidor debe entenderlo (y viceversa).
   Un servidor antiguo **descarta** campos que no conoce → el dato no sincroniza.
   Ver [docs/INTEGRATION.md](docs/INTEGRATION.md).

3. **Secretos cifrados en reposo.** Las contraseñas/API keys se guardan cifradas
   con `PwCrypto` (AES/GCM del Android Keystore) dentro de blobs JSON en DataStore
   (`SettingsStore.kt`). Al añadir un campo secreto nuevo, cífralo igual (solo el
   secreto, no el resto). El servidor **nunca** expone secretos por su API.

4. **Capacidades (OCR/LanguageTool/marcadores/conectores): respeta el modelo.**
   El conmutador es `CapabilitySource = OFF | PHONE | SERVER`. El despacho y el
   failover los centraliza `CapabilityDispatcher`. No metas lógica de "quién
   ejecuta" repartida por la UI. Detalle → [docs/CAPABILITIES.md](docs/CAPABILITIES.md).

5. **El prompt del OCR local es SOLO local** (decisión del usuario). El modo
   Servidor usa el prompt del servidor; no mandes el prompt local al `/transcribe`.

6. **Texto de UI siempre por `stringResource(R.string.clave)`.** Añade la clave a
   `values/` (español, por defecto), `values-en/` y `values-ca/` (idiomas vivos).
   No pongas literales en los composables.

7. **Offline-first: las mutaciones locales se aplican a Room y se encolan en el
   outbox** (`OutboxKind` + `SyncEngine`). No hagas llamadas REST directas de
   escritura saltándote el outbox (rompe el last-write-wins y el repunte de ids).

8. **No anides scrolls del mismo eje** en Compose (la pantalla de Ajustes ya tiene
   un `verticalScroll` exterior).

## Convenciones

- **Idioma del código**: comentarios y mensajes en **español**; UI en es/ca/en.
- **Git**: rama → commit → push → PR → **merge** (nunca commits directos a `main`).
  Mensajes de commit terminan con
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; cuerpo del PR termina
  con `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Versionado**: tags `vX.Y.Z`. Al publicar, sube `versionCode` (+1, monótono) y
  `versionName` en `neopen/build.gradle.kts`, en un commit `release: vX.Y.Z (...)`.
- Más en [docs/CONVENTIONS.md](docs/CONVENTIONS.md).

## Antes de dar algo por terminado

- `./gradlew :neopen:assembleDebug` en verde (y `:core:testDebugUnitTest` si tocaste
  lógica de `:core`).
- Si cambiaste el contrato con el servidor, confirma el lado servidor (ver INTEGRATION).
- Detalle por tipo de cambio → [docs/VERIFICATION.md](docs/VERIFICATION.md).

## Más detalle (docs/)

- Organización y subsistemas → [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Sistema de capacidades local/servidor + failover → [docs/CAPABILITIES.md](docs/CAPABILITIES.md)
- Relación con el servidor (contrato) → [docs/INTEGRATION.md](docs/INTEGRATION.md)
- Convenciones de código y git → [docs/CONVENTIONS.md](docs/CONVENTIONS.md)
- Cómo verificar cambios → [docs/VERIFICATION.md](docs/VERIFICATION.md)
- Release y despliegue → [docs/RELEASE.md](docs/RELEASE.md)
- Metas futuras y tareas planificadas → [docs/ROADMAP.md](docs/ROADMAP.md)
- Trampas conocidas → [docs/GOTCHAS.md](docs/GOTCHAS.md)
