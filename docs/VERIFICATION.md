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
./gradlew :neopen:testDebugUnitTest                             # regresiones de app con Room/HTTP y Robolectric
./gradlew checkApiContract checkFileModes                      # OpenAPI/Retrofit, Room 2..10 y permisos Git
./gradlew :neopen:connectedDebugAndroidTest                     # migraciones + outbox/delta en emulador API 34
./gradlew :live:compileDebugKotlin                              # :live también depende de :core
```

`assembleDebug` es la verificación fuerte: valida KSP (Room), el merge de recursos
(strings de los 3 idiomas) y el dexing de las dependencias. Hazlo siempre antes de
publicar.

Las regresiones de app incluyen `SyncBoundaryRegressionTest` (vinculación con
escritura concurrente, cancelación, aislamiento de servidor, reimportación y
reinicio del gate offline), `PenSessionPersistenceTest` (tinta pendiente al
desconectar/cambiar de boli, conservando la MAC original) y
`LocalConnectorSyncRegressionTest` (rutas independientes, cambios de papel/destino
y limpieza segura de rutas compartidas o heredadas). Se ejecutan con
`:neopen:testDebugUnitTest`, sin dispositivo BLE.

## Por tipo de cambio

| Cambiaste… | Verifica |
|---|---|
| Lógica pura en `:core` (marcadores, LanguageTool, parseo OCR, slug, hash) | `./gradlew :core:testDebugUnitTest` (añade/ajusta tests en `core/src/test/.../CapabilityLogicTest.kt`) |
| Esquema Room (entidad/columna/tabla) | Migración nueva + subir `@Database(version)` + conservar todos los JSON desde v2; `connectedDebugAndroidTest` valida cada salto y la conservación 2→actual |
| El contrato con el servidor (`Api.kt`/DTOs) | `checkApiContract`, tests Moshi y `assembleDebug`; en servidor: typecheck, tests y `pnpm check:contract`. Ver [INTEGRATION.md](INTEGRATION.md) |
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
gh release view vX.Y.Z --json assets --jq '.assets[].name'   # -> neopen.apk
```

El job `Migraciones Room y sync (emulador oficial)` debe estar verde: usa una
imagen AOSP API 34, valida los esquemas históricos y recorre push/outbox + pull
delta contra MockWebServer. No sustituye las pruebas BLE en hardware físico.

## Distribución optimizada y rendimiento

`./gradlew :neopen:assembleGithub` genera un APK no depurable y minificado con el
mismo certificado de los APK de GitHub existentes. Antes de distribuirlo:

```sh
./gradlew -PinstrumentedBuildType=github :neopen:connectedGithubAndroidTest
```

Incluye pruebas sobre Room/outbox/Studio, serialización con valores nulos,
exportación PNG/SVG/PDF, algoritmos SSH cargados por reflexión y WebDAV HTTPS.
Las pruebas habituales de migración y sincronización también se ejecutan contra
esta variante. El job debug conserva su selección predeterminada.

La suite de caché comprueba el límite de 24 MiB, dos renderizados simultáneos,
deduplicación, cancelación y cambios locales sin revisión del servidor. Véase
[PERFORMANCE.md](PERFORMANCE.md) para medir arranque, frames y memoria con once
notas y un cuaderno de 512 páginas, y comparar resultados en el mismo equipo.
