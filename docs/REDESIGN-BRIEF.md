# Brief de rediseño — neopen (app Android)

> Documento para entregar a un diseñador (o a Claude design / Figma) y pedir **dos
> propuestas de rediseño** completas. Describe qué hace la app, cómo está
> organizada y cada pantalla con todos sus elementos y estados, tal como existen
> hoy (v0.22.0). Al final está el encargo concreto.

---

## 1. Qué es la app (contexto de producto)

**neopen** es una app Android nativa (Kotlin + Jetpack Compose, **Material 3**)
que conecta un **bolígrafo inteligente Neo smartpen M1+ (NWP-F51)** por
**Bluetooth LE**. Captura lo que escribes a mano sobre papel Ncode, lo organiza
en cuadernos/hojas y permite transcribirlo (OCR con IA), exportarlo (SVG/PNG/PDF)
y sincronizarlo con servicios (WebDAV/FTP) o con un servidor propio.

Es **self-hosted / privacy-first**: existe una versión de servidor autoalojable
(repo `neopen`), pero la app funciona también **100% en el móvil sin servidor**.

### Tres modos de uso (elegidos en el alta y cambiables en Ajustes)
- **Completo (FULL)** — captura + cuadernos + capacidades, con servidor detrás.
- **Local (LOCAL)** — todo en el teléfono, sin servidor (offline-first). Se puede
  vincular un servidor más tarde sin perder datos.
- **Puente (BRIDGE)** — el móvil solo hace de puente Bluetooth→servidor; la
  organización vive en el servidor. La app muestra **solo Boli + Ajustes**.

### Concepto clave: "capacidades" con conmutador de 3 estados
Cuatro funciones inteligentes (Transcripción/OCR, corrección **LanguageTool**,
detección de **marcadores**, sincronización a **conectores**) se pueden ejecutar
en **Desactivado / Teléfono / Servidor**, con repliegue automático (failover).
Es lo más distintivo y lo más denso de la UI actual.

### Usuario tipo
Técnico/prosumer que valora la privacidad y el control. Toma notas a mano
(estudio, reuniones, diario) y quiere digitalizarlas a su manera.

---

## 2. Sistema de diseño actual (punto de partida)

- **Framework**: Jetpack Compose + **Material 3** (componentes estándar M3).
- **Color**: usa `MaterialTheme.colorScheme` (dynamic color del sistema cuando
  está disponible). Tema **claro/oscuro/sistema** configurable.
- **Acentos semánticos fijos** (fuera del theme, para estados del boli y avisos):
  - Verde `#22C55E` conectado · Ámbar `#F59E0B` conectando · Rojo `#EF4444`
    bloqueado · Gris `#9CA3AF` desconectado.
  - Naranja `#EA580C` para "trazos superpuestos".
- **Paleta de cuadernos** (color de lomo, elegible): `#5b8cff #64748b #3ad29f
  #ef4444 #f59e0b #a855f7 #ec4899 #14b8a6 #111827`.
- **Tipografía**: escala tipográfica M3 (headline/title/body/label). Monoespaciada
  solo en el log de depuración.
- **Forma**: tarjetas redondeadas (8 dp típicas), chips, FAB circular.
- **Idiomas**: es (por defecto), en, ca. Texto siempre vía recursos (i18n).
- **Iconografía**: Material Icons (Inbox, Book, Draw, Settings, etc.).

Render del trazo: el dibujo de la escritura se pinta sobre **lienzo blanco**
(`InkCanvas`), con opción de **presión→grosor** (el trazo varía de ancho según la
presión). Hay **fondos** opcionales por hoja/cuaderno (líneas, cuadrícula, puntos,
pentagrama, o imagen/PDF subido).

---

## 3. Arquitectura de navegación

**Barra de navegación inferior** (Material `NavigationBar`) con 2 o 4 pestañas
según el modo:

- Modo **Completo/Local** → `Bandeja · Cuadernos · Boli · Ajustes`
- Modo **Puente** → `Boli · Ajustes`

**Barra superior** (`TopAppBar`) con el título de la pestaña + acción de
**sincronizar** (icono refresh; badge con nº de pendientes; spinner si está
sincronizando; tinte rojo si hay error).

**Indicadores en las pestañas**:
- En **Boli**: punto de estado de color (verde/ámbar/rojo/gris) sobre el icono.
- En **Bandeja**: badge numérico si hay avisos de reimportación pendientes.

**Pantallas a pantalla completa** (push, con flecha atrás): Cuaderno abierto y
Visor de hoja.

**Antes de todo**: si no se ha completado el alta → **Asistente de configuración**.

---

## 4. Pantallas, una a una

### 4.1 Asistente de configuración (onboarding, 2 pasos)
- **Paso 0 — "¿Cómo quieres usarla?"**: título "neopen" + subtítulo. Tres
  **tarjetas seleccionables** (`ModeCard`: título + descripción, resaltada al
  elegir): Completo / Local / Puente. Botón inferior: "Empezar" (si Local) o
  "Siguiente".
- **Paso 1 — "Conecta tu servidor"** (solo si no es Local): campos **URL del
  servidor** y **contraseña** (oculta), botón **"Probar conexión"** (con spinner)
  y mensaje de resultado con color semántico (ok/credenciales/inaccesible).
  Botones **Atrás** / **Empezar** (habilitado solo si la prueba fue OK).

### 4.2 Bandeja (Inbox) — "Recientes"
Entrada principal en modo completo/local. Lista cronológica de últimas hojas.
- Cabecera "Recientes" + **chips 5/10/20** (cuántas mostrar) + botón **"Limpiar"**.
- **Campo de búsqueda** (busca en nombre, transcripción y etiquetas).
- **Tarjetas de aviso de reimportación offline** (borde rojo): miniatura del
  trazo + icono warning + texto (página borrada/movida) + botones
  **Descartar / Nueva captura / Combinar**.
- **Filas de hoja** (`PageListRow`): miniatura del trazo (64 dp), nombre, "Página
  N", aviso "sin formato" si aplica, y **menú de acciones** (⋮).
- Estados vacíos: "bandeja vacía" / "sin resultados".

### 4.3 Cuadernos
Rejilla/lista de cuadernos. Cabecera con título + **3 botones de vista**:
**Mosaico** (tarjetas grandes), **Compacto** (tarjetas pequeñas), **Lista**
(árbol desplegable).
- **FAB +** (abajo derecha) para crear cuaderno.
- **Tarjeta de cuaderno** (`NotebookCard`): portada (imagen del servidor o, en
  local, franja de color del lomo) + nombre + "N hojas" + **menú ⋮**. Insignia ⚠
  si el cuaderno no tiene formato de papel definido.
- **Agrupación por categoría** (opcional): cabeceras de sección con contador; el
  grupo "Sin categoría" va al final.
- **Vista Lista (árbol)**: fila con punto de color + nombre + "N hojas" +
  chevron; al pulsar **despliega sus hojas** in-situ.
- **Menú ⋮ del cuaderno**: Editar · Formato de página · Fondo (si viene del boli)
  · Compartir PDF · Guardar PDF · Eliminar.
- **Diálogo crear/editar cuaderno**: nombre, **selector de color** (swatches),
  **categoría** (campo + chips de categorías existentes), **etiquetas**.

### 4.4 Cuaderno abierto (pantalla completa)
TopAppBar con título + flecha atrás + **3 vistas**: Cuadrícula / Lista / Grande.
- **Cuadrícula**: tarjetas con miniatura (proporción 0.8) + nombre.
- **Lista**: filas tipo `PageListRow` (miniatura + nombre + página + acciones).
- **Grande**: tarjeta ancha con render alto (360 dp) + nombre + etiquetas +
  **fila de acciones completa**.
- Estado vacío: "este cuaderno no tiene hojas".

### 4.5 Visor de una hoja (pantalla completa)
- TopAppBar: nombre de la hoja + atrás + botón de texto **"Fondo"**.
- **Lienzo grande** centrado: la hoja se dibuja tan grande como cabe conservando
  proporción, sobre `Surface` blanca con sombra y borde sutil.
- **Fila de acciones** (chips con icono+texto, ver 4.6).
- **Etiquetas** (chips) si las hay.
- **Transcripción**: si existe, bloque scrollable con título "Transcripción" + texto.

### 4.6 Acciones de hoja (`CaptureActions`) — núcleo funcional
Aparecen como **chips con icono+etiqueta** (en visor/grande) o como **menú ⋮**
(en filas). Acciones:
- **Transcribir** (OCR; deshabilitado si offline) · **Compartir** · **Descargar**
  · **Etiquetas** · **Renombrar** · **Formato** (proporción de papel) · **Mover**
  (a otro cuaderno) · **Superpuestos** (solo si hay trazos solapados; naranja) ·
  **Finalizar/Reabrir** hoja (sellado) · **Eliminar**.
- **Diálogos asociados**: renombrar, mover (lista de cuadernos), etiquetas,
  confirmar borrado, selector de papel, selector de formato de export
  (SVG/PNG/PDF…), y el **resolutor de superposición** (ver 4.9).

### 4.7 Boli (pantalla más rica)
Scroll vertical con:
- **Tarjeta de estado**: punto de color + nombre/MAC del boli, estado textual
  (conectado/escaneando/bloqueado/reconectando…), **batería** (y "cargando"),
  **memoria libre %**, **páginas capturadas**, y error si lo hay.
- **Botón de acción principal** contextual: "Detener" (si conectando) /
  "Introducir PIN" (si bloqueado) / "Conectar" o "Reconectar".
- Cuando está conectado, **sub-pestañas** (`SecondaryTabRow`):
  - **Configuración**: toggles **Auto-encendido**, **Pitido**, **Datos offline**;
    **Sensibilidad de presión** (chips 1–5, si lo soporta); **Auto-apagado**
    (chips 5/10/20/30/60 min); **Contraseña/PIN del boli** (poner/cambiar/quitar
    + "olvidar PIN guardado", con aviso de reseteo de fábrica a los N intentos);
    MAC y firmware; herramienta avanzada **"Leer SOBP"**.
  - **Memoria offline**: botones **"Leer memoria"** / **"Importar todo"**; barra
    de progreso al importar; lista de notas (`section.owner.note`, nº de páginas)
    con **Importar** por nota; **"Borrar memoria"**.
- **Bolis emparejados** (siempre visible, aunque apagados): por cada boli, alias +
  **"Olvidar"**, conmutador **"Bloquear a este boli"**, y **estilo de trazo**
  (paleta de color + grosor en chips). Además, **estilo por defecto**.
- **"Ver registro (N)"** → diálogo de log de depuración (texto monoespaciado,
  Copiar/Limpiar).
- **Diálogo de PIN** (desbloqueo): campo numérico oculto + checkbox "recordar" +
  contador de intentos + aviso de reseteo.

### 4.8 Ajustes (5 sub-pestañas con icono)
`SecondaryTabRow`: **General · Conexión · Capacidades · Otros · Servidor**.
- **General**: Tema (chips sistema/claro/oscuro); Modo de la app (Completo/Puente);
  "Al finalizar una hoja" (nueva hoja / nuevo cuaderno); **Trazos superpuestos**
  (toggle + umbrales mín. trazos / mín. área %); **Presión como grosor** (toggle +
  slider de contraste; nota si manda el servidor).
- **Conexión**: **Sincronización en segundo plano** (toggle), **Importar al
  conectar** (toggle), **Notificación de control** (toggle); **Política de
  reimportación offline** (chips Preguntar/Descartar/Nueva captura/Combinar);
  **Modo de sync** (Manual/Intervalo + minutos); botón Guardar.
- **Capacidades**: intro + botón **"Importar configuración del servidor"** +
  **4 tarjetas colapsables** (OCR, LanguageTool, Marcadores, Conectores). Cada
  tarjeta: cabecera (icono + título + **estado** con color) + cuerpo con el
  **conmutador de 3 estados** (Desactivado/Teléfono/Servidor, con segmentos
  deshabilitados si no hay credenciales y nota explicativa del estado efectivo) +
  campos locales (URL/modelo/clave en OCR; prompt de OCR; WebDAV+FTP en
  Conectores; etc.).
- **Otros**: Copia de seguridad (Exportar/Restaurar JSON); **Historial de SOBP**
  (lista + borrar); **Borrar contenido** (zona de peligro, con confirmación).
- **Servidor**: en local → vincular servidor (URL+contraseña, migra datos); en
  servidor → credenciales + "Probar conexión" + Guardar, **última sincronización**,
  **pendientes de subir**, "Sincronizar ahora", y último error.

### 4.9 Diálogos especiales
- **Resolutor de trazos superpuestos**: previsualización de la hoja + **slider tipo
  línea de tiempo** (elige punto de corte; lo posterior se pinta en naranja) +
  contador "se moverán N trazos" + 3 salidas: misma página / nueva hoja / otro
  cuaderno.
- **Selector de fondo** (`BackgroundPickerDialog`): presets (Líneas, Cuadrícula,
  Puntos, Pentagrama) o imagen/PDF subido; ámbito hoja o cuaderno; quitar fondo.
- **Lector de SOBP**: espera una marca del boli, muestra el código detectado
  (`section.owner.book.page`) y lo guarda con un identificador.

### 4.10 Widgets de pantalla de inicio (fuera de la app)
- **Sincronización** (estado/batería/volcado/conectar), **Cuadernos** (acceso
  rápido), **Ediciones recientes** (con compartir). Son secundarios pero conviene
  que el lenguaje visual sea coherente con la app.

---

## 5. Estados y casos límite que el diseño debe contemplar
- **Offline** (sin servidor / sin red): acciones de OCR deshabilitadas; etiquetas
  "sin conexión"; modo local sin portadas (solo color).
- **Boli bloqueado por PIN** (rojo) y **riesgo de reseteo de fábrica** a los N
  intentos fallidos (mensajería de advertencia importante).
- **Conflictos de reimportación offline** (tarjetas de aviso).
- **Hojas "sin formato"** (proporción desconocida) y **selladas/finalizadas**.
- **Densidad alta**: la pestaña Boli y Ajustes→Capacidades acumulan muchos
  controles; hoy se resuelven con scroll + tarjetas colapsables + sub-pestañas.

---

## 6. Encargo a diseño — DOS propuestas de rediseño

Quiero **dos direcciones visuales distintas** (no dos variaciones de la misma),
ambas en Material 3 / Android, modo claro y oscuro, es/en/ca, accesibles
(contraste AA, áreas táctiles ≥ 48 dp).

Para cada propuesta entregar como mínimo:
1. **Concepto / principios** (1 párrafo: qué problema de UX prioriza).
2. **Sistema visual**: paleta, tipografía, formas, iconografía, componentes clave.
3. **Pantallas rediseñadas** (alta fidelidad): Onboarding, Bandeja, Cuadernos,
   Visor de hoja con acciones, **Boli** y **Ajustes→Capacidades** (las dos más
   complejas), en claro y oscuro.
4. **Patrón para densidad**: cómo organizan las muchas opciones de Boli y
   Capacidades (lo que hoy son sub-pestañas + tarjetas colapsables).
5. **Estados**: vacío, offline, boli bloqueado, aviso de conflicto.

### Dos direcciones sugeridas (orientación, el diseñador puede proponer otras)
- **Propuesta A — "Cuaderno cálido / editorial"**: estética de papelería digital,
  protagonismo de las portadas y el trazo, tipografía con carácter, color cálido,
  sensación de diario personal. Prioriza el contenido (las notas) sobre los
  controles.
- **Propuesta B — "Panel técnico / control"**: estética limpia y densa tipo panel
  de control, ideal para el usuario self-hosted; jerarquiza el estado del boli, la
  sincronización y las capacidades (3 estados) como ciudadanos de primera clase;
  máxima legibilidad de estados y diagnóstico.

### Restricciones técnicas a respetar
- Material 3 nativo (no inventar componentes imposibles en Compose).
- Mantener el **modelo de información** (cuadernos→hojas, modos, capacidades de 3
  estados): se puede reorganizar la presentación, no el modelo de datos.
- Conservar la mensajería crítica de seguridad (riesgo de reseteo del boli).
