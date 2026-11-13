# Comparación de arranque y scroll — 13 de noviembre de 2026

Resultados observados en un **emulador Android 14/API 34**, con KVM,
renderizado por software, 2 vCPU y 2 GiB de RAM. No son resultados del Pixel 8a.
Se ejecutaron tres iteraciones de cada escenario, con tres pasadas de
calentamiento y sin otras compilaciones o pruebas simultáneas.

La referencia es v0.26.0 (`cf8c746`), añadiendo únicamente el mismo harness,
la señal de carga local y la configuración de APK optimizado. Ambas versiones
usan R8, la misma firma, las mismas reglas y las mismas fixtures. Por tanto,
esta comparación mide los cambios de implementación; no incluye la diferencia
adicional entre distribuir un APK debug y uno optimizado.

## Tiempos

| Escenario y métrica | Referencia | Optimizado |
|---|---:|---:|
| Arranque frío: primera pantalla (mediana) | 460,14 ms | 358,36 ms |
| Arranque frío: lista utilizable, TTFD (mediana) | 1203,34 ms | 809,68 ms |
| Arranque caliente: lista utilizable, TTFD (mediana) | 511,60 ms | 132,94 ms |
| Scroll de 11 notas: CPU por frame (p95) | 130,07 ms | 13,25 ms |
| Scroll de 11 notas: exceso sobre plazo de frame (p95) | 160,31 ms | −2,63 ms |
| Scroll de 512 páginas: CPU por frame (p95) | 35,53 ms | 13,73 ms |
| Scroll de 512 páginas: exceso sobre plazo de frame (p95) | 27,41 ms | −1,99 ms |

Un exceso negativo indica margen respecto al plazo de presentación del frame.
TTFD corresponde a la lista local disponible para interactuar; los fondos y
miniaturas pueden completarse después, sin esperar al servidor.

## Memoria y límites de la medición

La memoria no mejora de forma uniforme. En arranque frío, el máximo de RSS
anónimo pasa de 183808 a 110348 KiB. En el scroll de 512 páginas pasa de 108464
a 149152 KiB, mientras el heap baja de 39482 a 21356 KiB. Durante scroll de
11 notas el heap también aumenta (20153 a 39051 KiB), aunque su RSS anónimo
disminuye. La caché cambia el reparto entre trabajo repetido y memoria retenida.
Su presupuesto de 24 MiB no representa un límite para toda la memoria de la
aplicación, las imágenes de origen y los recursos gráficos.

Las once notas contienen 8×1000, 2×6000 y 1×15000 puntos; el cuaderno grande,
512 páginas de 200 puntos. La presión está activada y el servidor configurado
es inaccesible, para comprobar la disponibilidad de los datos locales.
La primera tanda, con una fila mayor que CursorWindow, se descartó; no se
incluye en estos resultados. No se truncó ni modificó tinta de usuario.

Tres iteraciones permiten comprobar la infraestructura y observar estos casos,
pero no establecen garantías de latencia ni de memoria para otros dispositivos.
La comprobación física de fluidez y captura BLE con el Pixel 8a sigue pendiente.

## Datos y reproducción

- [JSON de referencia](baseline.json) y [JSON optimizado](after.json).
- [Comparación completa de las 34 métricas](comparison.json).
- [Comandos y preparación del baseline](../../PERFORMANCE.md).

Ambas tandas terminaron con cuatro escenarios correctos. Los JSON incluyen la
identidad del emulador, iteraciones y muestras para revisar la comparación.
