# Medición reproducible de arranque y scroll

El problema objetivo es un Pixel 8a con 11 notas en modo servidor. El entorno de
desarrollo puede compilar y ejecutar pruebas en emulador; sus resultados no
representan la fluidez de ese teléfono. No hay mejoras porcentuales afirmadas
sin una comparación medida en el mismo dispositivo.

La [comparación observada en el emulador API 34](performance/emulator-api34/README.md)
incluye las muestras originales y los resultados de tres iteraciones por caso.

## APK y datos aislados

La variante `github` es la distribución optimizada, no depurable, firmada con el
mismo certificado que los APK de GitHub anteriores. La variante `benchmark`
hereda esa configuración pero usa **`net.micapum.neopen.benchmark`**, una base de
datos y preferencias distintas. Su actividad para sembrar datos solo existe en
`src/benchmark`; no se incluye en los APK de usuario ni en F-Droid.

El módulo `:benchmark` y la variante de prueba solo se habilitan con
`-Pperformance`, y nunca con `-Pfdroid`. No cambian las versiones de
Kotlin/Compose/AGP. Macrobenchmark 1.3.4 y ProfileInstaller 1.3.1 son compatibles
con el toolchain actual.

Las fixtures son deterministas: 11 notas (8 de 1.000 puntos, 2 de 6.000 y 1 de
15.000), o un cuaderno de 512 páginas de 200 puntos. Incluyen presión, OCR para
búsqueda y fondos de preset/PNG. El servidor configurado es
`https://127.0.0.1:1`, deliberadamente inaccesible, para medir la carga de Room
sin esperar una sincronización. No utilizan datos personales.

Cada fila se limita a menos de 1,5 MB de tinta serializada para caber en el
`CursorWindow` de Android. La fixture inicial de 50.000 puntos excedía ese límite
y hacía fallar el arranque en ambas versiones; esa tanda no es una medición de
rendimiento válida y se descarta.

## Ejecutar en un dispositivo

Con JDK 17 y el SDK configurado en `local.properties`, conectar el Pixel por ADB:

```sh
adb devices -l
./gradlew -Pperformance :benchmark:connectedBenchmarkAndroidTest
```

Los cuatro escenarios son `coldStart11Notes`, `warmStart11Notes`, `scroll11Notes`
y `scroll512PageNotebook`. Ejecutan 10 iteraciones tras compilar con tres pasadas
de calentamiento. Registran TTID/TTFD en el arranque, tiempos de frames durante
el scroll y memoria máxima muestreada en cada escenario. TTFD corresponde a la
lista local utilizable; las miniaturas pueden completarse después.

Para una comprobación breve de infraestructura, sin tratarla como medida final:

```sh
./gradlew -Pperformance :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.iterations=1
```

Solo en el emulador se debe añadir:

```sh
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

En el dispositivo físico no se suprimen advertencias de Macrobenchmark. Mantener
iguales la versión Android, frecuencia de pantalla, presión, temperatura y estado
de batería entre las dos mediciones. No ejecutar compilaciones u otras pruebas
simultáneamente. Además de los JSON, conservar las trazas Perfetto.

## Comparación antes/después

El siguiente comando prepara un worktree temporal de v0.26.0 con **solo** el
harness, la variante optimizada y la señal TTFD añadidos. La carga y el render
originales permanecen intactos. Así se compara el cambio de implementación con
el mismo tipo de APK, en lugar de atribuirle la diferencia entre debug y release.

```sh
python3 scripts/prepare-performance-baseline.py /tmp/neopen-performance-baseline
```

Ejecutar el benchmark en ese worktree primero. Guardar sus resultados antes de
instalar y medir el árbol optimizado; ambas variantes utilizan únicamente el
paquete `.benchmark` y se vuelven a sembrar en cada escenario.

Los resultados se copian bajo
`benchmark/build/outputs/connected_android_test_additional_output/` e incluyen
`*-benchmarkData.json`. Conservar cada directorio fuera del árbol de build:

```sh
python3 scripts/compare-performance.py /ruta/resultados-antes /ruta/resultados-despues \
  --output /ruta/comparacion.json
```

El script informa medianas y percentiles disponibles, sin completar valores
ausentes. Conserva el delta absoluto y omite porcentajes cuando una métrica con
signo cruza cero. Rechaza comparar modelos/dispositivos distintos. No se usan umbrales
de tiempo del emulador como gates de rendimiento del Pixel.

## Regresiones funcionales y de caché

Las pruebas de Room comprueban límites antes de leer tinta, búsqueda global,
clones/instancias e invalidación con el mismo `rev` y `updatedAt`. Las pruebas de
miniaturas comprueban reutilización, tamaño, presión y cambios de fondo. Completar
también escritura BLE, volcado offline, outbox y migraciones existentes. El APK
minificado tiene su propia prueba instrumentada; los tests debug no sustituyen
esa comprobación de reflexión/serialización.

## Actualización real desde el APK publicado

Solo en un emulador, el probe comprueba que instalar el APK optimizado sobre
v0.26.0 conserva una captura, sus trazos exactos y la contraseña cifrada. No
desinstala ni limpia los datos y rechaza reducir `versionCode`. Sus casos se
omiten en la suite habitual si no se proporciona `upgradeStage`.

Compilar los dos APK de instrumentación desde este árbol:

```sh
./gradlew :neopen:assembleDebugAndroidTest
./gradlew -PinstrumentedBuildType=github :neopen:assembleGithub :neopen:assembleGithubAndroidTest
python3 scripts/check-apk-upgrade.py --serial emulator-5554 \
  --candidate-apk neopen/build/outputs/apk/github/neopen-github.apk \
  --baseline-test-apk neopen/build/outputs/apk/androidTest/debug/neopen-debug-androidTest.apk \
  --test-apk neopen/build/outputs/apk/androidTest/github/neopen-github-androidTest.apk \
  --output-dir /tmp/neopen-upgrade-check
```

El baseline se descarga del asset público v0.26.0 y se verifica su checksum;
también puede indicarse con `--baseline-apk`. Añadir `--adb` y `--aapt` si las
herramientas del SDK no están en el PATH. Se usa el test APK debug para sembrar
los datos en el baseline y, después de actualizar la app, se sustituye por el
test APK github para verificarlos. Esto evita mezclar los distintos nombres
de clases de las bibliotecas compartidas tras R8. Los logs y `result.json`
quedan en el directorio de salida indicado.

Referencias oficiales: [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
y [medición del arranque](https://developer.android.com/topic/performance/appstartup/analysis-optimization).
