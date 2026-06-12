# neopen companion (Android)

App Android **companion** del proyecto [neopen](https://github.com/borborborja/neopen).
Su único cometido: conectar por **Bluetooth LE** al **Neo smartpen M1+ (NWP-F51)**,
captar la escritura (en vivo y de la memoria offline) y **subirla en crudo** al
servidor neopen, que la archiva en su cuaderno por SOBP.

Es un **cliente fino**: no renderiza, no conoce la lógica de cuadernos. Todo eso
lo hace el servidor (ver `docs/openapi.yaml`, copiado del repo neopen).

## Pantallas

- **Boli**: cabecera con nombre + estado (`Conectado · Batería X% · Memoria Y%`) y
  botón Conectar/Reconectar. Dos pestañas, como la ventana del boli en la web pero
  **sin color ni sensibilidad de trazo**:
  - **Configuración**: encendido automático, sonido, guardar offline, apagado
    automático, e identidad (MAC, firmware).
  - **Memoria**: leer memoria, importar libreta / importar todo, vaciar memoria, y
    **volcar la memoria al servidor** (la copia/restauración completa de datos vive
    en el propio neopen → Ajustes → Cuadernos).
- **Ajustes**: URL del servidor + contraseña, y opciones de Bluetooth/sincronización
  (conexión en segundo plano, modo manual o por intervalo, sincronizar ahora).

## Arquitectura

```
pen/Protocol.kt    Constantes y framing del protocolo v2 (UUIDs, comandos, dot 17B,
                   escape DLE, checksum) — reconstruido del SDK oficial web_pen_sdk.
pen/PenManager.kt  BLE: scan/connect/GATT, reensamblado de frames, handshake,
                   captura online → StrokePage, y flujo offline. Expone PenState.
data/SettingsStore Ajustes (DataStore): URL, contraseña, BLE, intervalo.
data/Api.kt        Cliente Retrofit del servidor neopen (contrato docs/openapi.yaml).
sync/Uploader.kt   StrokePage → POST /api/captures (el servidor archiva por SOBP).
sync/PenService    Servicio en primer plano (conexión BLE en segundo plano).
sync/SyncWorker    Volcado offline periódico (WorkManager, modo intervalo).
ui/MainScreen.kt   Compose: pantalla del boli (pestañas) + ajustes.
```

Una sola conexión BLE vive en `NeopenApp` (Application), compartida por la UI y el
servicio. Cuando una página se completa, `PenManager.onPage` la sube vía `Uploader`.

## Cómo abrirlo / compilarlo

1. Abre la carpeta en **Android Studio** (Giraffe+); deja que genere el
   `gradle-wrapper.jar` y sincronice (o ejecuta `gradle wrapper` si tienes Gradle).
2. `minSdk 26`, `targetSdk 34`. Necesita un dispositivo **físico** con BLE
   (el emulador no tiene Bluetooth).
3. Concede los permisos de Bluetooth (y notificaciones en Android 13+) al arrancar.

> Este scaffold se ha escrito sin un dispositivo a mano, así que **la capa BLE está
> sin verificar en hardware**. La UI, los ajustes y el cliente API son completos y
> funcionales; la parte BLE necesita una pasada con el boli real (ver abajo).

## Estado del protocolo BLE (qué falta verificar)

Constantes **confirmadas** desde `web_pen_sdk` (la implementación que ya funciona en
la web): servicio `0x19F1`, write `0x2BA0`, notify `0x2BA1`, framing STX/ETX, escape
DLE (`0x07, b^0x20`), checksum = suma de DATA & 0xFF, y el enum completo de comandos
(`CMD.js`).

Marcado con **`VERIFY`** en el código (necesita el boli real o leer
`PenRequestV2.js` / `PenClientParserV2.js` del SDK):
- Payload exacto de `VERSION_REQUEST` y parseo de `VERSION_RESPONSE` (MAC/firmware).
- Offsets de `SETTING_INFO_RESPONSE` (batería/memoria) y los ids de `SETTING_TYPE`.
- Estructura del evento de dots y del `PAPER_INFO` (SOBP) — online.
- Layout del chunk offline (`OFFLINE_PACKET_REQUEST`) y su descompresión zlib.

**Alternativa recomendada si la reconstrucción da guerra:** integrar el **SDK oficial
de Android de NeoLAB** (GPL-3.0) y dejar que él haga el BLE; `PenManager` se
reescribe como un wrapper de su API y el resto de la app no cambia.

## APK (GitHub Actions)

El workflow `.github/workflows/build-apk.yml` compila un **APK debug** (instalable)
en cada push a `main`, en cada PR y a demanda:

- **Artifact**: pestaña *Actions* → run → `neopen-companion-debug`.
- **Release**: al crear un tag `vX.Y.Z`, el APK se adjunta a la release.

```bash
git tag v0.1.0 && git push origin v0.1.0   # genera una release con el APK
```

## Licencia

Las constantes del protocolo se reconstruyeron a partir de los SDK GPL-3.0 de NeoLAB
(no hay código copiado, solo los valores del protocolo). Si se integra el SDK oficial
de NeoLAB, esta app hereda **GPL-3.0**. Tenlo en cuenta para cualquier plan de
distribución comercial.
