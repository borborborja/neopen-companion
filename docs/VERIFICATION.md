# Cómo verificar cada cambio

Requisito del entorno (este equipo **no tiene `java` en el PATH**):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

(El symlink `/opt/homebrew/opt/openjdk@17/bin/java` también existe; la ruta de
arriba es la que está verificada que funciona con Gradle.)

## Comandos base

```bash
./gradlew :neopen:compileDebugKotlin :core:compileDebugKotlin   # check rápido de compilación
./gradlew :neopen:assembleDebug                                 # APK completo (incluye Room/KSP, dex, recursos)
./gradlew :core:testDebugUnitTest                               # tests unitarios de lógica pura (:core)
./gradlew :live:compileDebugKotlin                              # :live también depende de :core
```

`assembleDebug` es la verificación fuerte: valida KSP (Room), el merge de recursos
(strings de los 3 idiomas) y el dexing de las dependencias. Hazlo siempre antes de
publicar.

## Por tipo de cambio

| Cambiaste… | Verifica |
|---|---|
| Lógica pura en `:core` (marcadores, LanguageTool, parseo OCR, slug, hash) | `./gradlew :core:testDebugUnitTest` (añade/ajusta tests en `core/src/test/.../CapabilityLogicTest.kt`) |
| Esquema Room (entidad/columna/tabla) | Migración nueva + subir `@Database(version)`; `assembleDebug` (KSP valida) + instalar sobre una versión previa y comprobar que no recrea la BD |
| El contrato con el servidor (`Api.kt`/DTOs) | `assembleDebug` **y** el lado servidor (`pnpm -r run typecheck` en `neopen`); ver [INTEGRATION.md](INTEGRATION.md) |
| UI Compose | `assembleDebug`; revisar en dispositivo (el emulador no tiene BLE pero sirve para ver UI/Ajustes) |
| Strings | `assembleDebug` falla si falta una clave usada; añade a `values/`, `values-en/`, `values-ca/` |

## E2E manual (dispositivo físico con BLE)

1. **Captura**: conectar el boli, escribir, ver la página en el inbox.
2. **Modo local sin credenciales**: Ajustes → Capacidades → todo en gris salvo
   "Desactivado" y "Detección de marcadores".
3. **OCR local**: credenciales OCR (p. ej. OpenRouter) → OCR en "Teléfono" →
   transcribir → aparece título/texto/tags/todos/fechas.
4. **LanguageTool + marcadores** en "Teléfono" → re-transcribir → texto corregido +
   `#tags`/`*título*` aplicados.
5. **Failover**: OCR en "Servidor", apagar el servidor → transcribir → repliegue al
   teléfono; al recuperar conexión, el resultado aparece en el servidor (outbox).
6. **Importar del servidor**: con servidor conectado → "Importar configuración del
   servidor" rellena URLs/modelo/usuarios; las claves se quedan vacías (aviso).
7. **Conectores**: WebDAV en "Teléfono" + Conectores en "Teléfono" → editar una
   captura → aparecen los ficheros (png/pdf/md) en Nextcloud/servidor.

## CI (antes de taggear una release)

```bash
gh run watch <run-id> --exit-status        # el run del PR/main/tag (NO uses `timeout`, no existe en macOS)
gh release view vX.Y.Z --json assets --jq '.assets[].name'   # -> neopen-debug.apk
```
