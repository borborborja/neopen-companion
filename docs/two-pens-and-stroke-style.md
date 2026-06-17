# Dos bolis + estilo de trazo por boli — spec para portar a la web

Documento de referencia para replicar en la web (`neopen`) los cambios de boli
hechos en la app Android. Cubre el **contrato de datos**, el **render por trazo**, la **semántica
de estilo** y el **registro de bolis**. Marca qué está implementado en Android y qué queda.

## 1. Contrato de datos (servidor / shared)

Se añaden **dos campos opcionales** al trazo y un id de boli a la página. Todo **retrocompatible**:
si faltan, se usan los defaults de hoy (`#111111` / grueso `1.6`), así el servidor y la web
actuales siguen válidos sin tocar nada hasta portar.

```ts
// packages/shared/src/index.ts  (espejo de Android)
export interface Stroke {
  startTime: number;
  endTime: number;
  dots: Dot[];
  color?: number;   // ARGB int (p. ej. 0xFF111111). Ausente → #111111
  width?: number;   // grueso base (mismas unidades que el 1.6 actual). Ausente → 1.6
}
```

Android (referencia):
- `core/.../data/Api.kt` → `StrokeDto(startTime, endTime, dots, color: Int? = null, width: Float? = null)`.
- `core/.../pen/PenModels.kt` → `Stroke(..., color: Int? = null, width: Float? = null)` y
  `StrokePage(..., penId: String? = null)` (MAC del boli que capturó la página; diagnóstico/dedup).

**Nota color:** es un entero ARGB. En la web conviene guardar/serializar igual (int) y convertir a
`#RRGGBB` solo al render (ignorando alfa), o adoptar string `#rrggbb` — **decidir el formato al
portar** y mantenerlo consistente cliente/servidor.

## 2. Render por trazo (hoy global)

Hoy el render usa color/grueso **globales** (`#111`, `model.strokeWidth`). Pasa a ser **por trazo**:

- `buildModel`: cada polilínea resuelve `color = stroke.color ?? INK` y
  `width = (stroke.width ?? 1.6) * factorEscala`. (Android: `RenderModel.polylines` ahora es
  `List<StyledPolyline(points, color, width)>`.)
- **SVG** (`modelToSvg` en la web): mover `stroke`/`stroke-width` del `<g>` global a cada `<path>`.
- **PNG/Canvas** (`png.ts`): fijar `ctx.strokeStyle`/`ctx.lineWidth` **por polilínea** dentro del
  bucle, no una vez antes.
- El preview de superposición (overlap) mantiene el **color por corte** (mantener/mover) pero usa el
  **grueso del trazo**.

Android (referencia): `neopen/.../render/Render.kt` (`StyledPolyline`, `drawModelTo`, `InkCanvas`,
`OverlapPreview`) y `Export.kt` (`renderSvg` con estilo por `<path>`).

## 3. Semántica del estilo

- El estilo es **por boli** (`KnownPen.style`) más un **estilo por defecto** global (`defaultStyle`).
- **Congelado por trazo:** al capturar un trazo se le sella el color/grueso del boli en ese momento.
  Cambiar el estilo del boli **no** re-colorea trazos ya capturados; solo afecta a los nuevos.
- **Atribución híbrida:**
  - **Online (en vivo):** el trazo toma el estilo **actual** del boli al cerrarse (`endStroke`).
    Como es en vivo, esto es exacto y parte la sesión si cambias de color a media escritura.
  - **Offline (volcado de memoria):** todos los trazos del volcado toman el estilo **actual** del
    boli en el momento de volcar. No se parte el lote por estilo.
  - **Extra (pendiente):** reasignar por rangos los trazos de una captura offline a otro estilo,
    para cubrir "de qué a qué trazo" cuando un volcado mezcla sesiones de distinto estilo.
- La **fusión por SOBP** y la **detección de superpuestos** siguen **agnósticas al boli** (no
  cambian): dos bolis sobre la misma hoja física se fusionan por SOBP; el color de cada trazo es el
  del boli que lo hizo.

Android (referencia): sellado en `PenManager.endStroke` (online) y `handleOfflinePacket` (offline);
estilo resuelto vía `AppSettings.styleFor(mac)`.

## 4. Registro de bolis emparejados

- `KnownPen(mac, alias, style)` + `defaultStyle`, persistidos. En Android viven en `SettingsStore`
  (`AppSettings.knownPens: List<KnownPen>`, clave DataStore `known_pens` en JSON; `defaultStyle` en
  `default_stroke_color`/`default_stroke_width`). En la web → equivalente en su almacenamiento de
  ajustes.
- **Emparejamiento "primero visto":** al autorizar un boli, si su MAC no está registrada y hay hueco
  (tope 2), se añade con alias = nombre del boli (o la MAC) y el `defaultStyle`.
- API: `setPenStyle(mac, style)`, `setDefaultStyle(style)`, `renamePen(mac, alias)`,
  `forgetPen(mac)`. Editable **aunque el boli esté desconectado** (es persistencia).

## 5. Conexión dual (resumen — refactor BLE, pendiente en Android)

> Esta parte aún **no** está implementada (es el segundo PR). Se documenta para el contexto del port.

- Hoy un único `gatt`/conexión y escaneo "al primero que aparece". El objetivo es **dos bolis a la
  vez**: extraer `PenConnection` (una por boli) y dejar `PenManager` como coordinador con
  `Map<MAC, PenConnection>`, escaneo multi-boli, cola de conexión serializada (conectar el 2.º solo
  tras autorizar el 1.º, anti-GATT-133), reconexión por-conexión y salida agregada (mismo `onPage`).
- **Reloj:** para que el "corte por timeline" funcione entre dos bolis, normalizar el tiempo de los
  trazos online al wall-clock del dispositivo. Offline depende del RTC del boli (limitación).
- En la web esto no aplica (no hay BLE), pero el contrato (`penId`, color/grueso por trazo) sí.

## Estado en Android

- ✅ Implementado: contrato (`StrokeDto.color/width`, `StrokePage.penId`), render por trazo
  (pantalla + PNG/PDF/SVG), registro de bolis (`knownPens`/`defaultStyle`), sellado híbrido del
  estilo en captura, UI mínima de estilo por boli + por defecto en la pestaña Boli.
- ⏳ Pendiente: conexión dual simultánea (PenConnection + coordinador), tarjetas de historial de
  bolis con estado en vivo, reasignación de estilo por rangos en capturas offline.
