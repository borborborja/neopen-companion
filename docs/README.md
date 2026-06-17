# docs/

Guías para programar en **neopen-companion**. Empieza por [`/CLAUDE.md`](../CLAUDE.md)
(raíz). El repo del servidor es [`neopen`](https://github.com/borborborja/neopen).

| Documento | Para qué |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Cómo está organizado: módulos, flujo de datos, subsistemas |
| [CAPABILITIES.md](CAPABILITIES.md) | Sistema local/servidor de 3 estados + failover + conectores (lo más complejo) |
| [INTEGRATION.md](INTEGRATION.md) | Relación con el servidor: contrato de la API, qué rompe si no cuadra |
| [CONVENTIONS.md](CONVENTIONS.md) | Convenciones de código, Compose, i18n y git/release |
| [VERIFICATION.md](VERIFICATION.md) | Cómo verificar cada tipo de cambio (build, tests, E2E manual) |
| [RELEASE.md](RELEASE.md) | Publicar una versión (bump, tag, CI) y desplegar |
| [ROADMAP.md](ROADMAP.md) | Metas futuras a tener en cuenta + tareas planificadas |
| [GOTCHAS.md](GOTCHAS.md) | Trampas conocidas — léelo antes de depurar |
| [two-pens-and-stroke-style.md](two-pens-and-stroke-style.md) | Estilo de trazo por boli (2 bolis) |

Protocolo BLE del boli (SOBP, dots, framing v2): el documento autoritativo es el
SDK `web_pen_sdk` del repo `neopen` y su `docs/PROTOCOL.md`. En este repo, la
implementación vive en `core/.../core/pen/` (`PenManager`, `Protocol`, `PenModels`).
