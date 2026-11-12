# Libros de Studio en Android

El acceso a Libros, su contador y las opciones de Studio solo aparecen tras
verificar `capabilities.studio.enabled=true` en el servidor configurado. El
permiso no se persiste: arranque, modo local, cambio de servidor o credenciales
y errores de verificación mantienen la función oculta. Se revalida al volver
al primer plano, durante la sincronización y cada 30 segundos con la app visible.
Desactivar Studio cierra el gestor y conserva la caché y las operaciones
pendientes, que esperan a su reactivación; las notas normales siguen funcionando.

La pestaña **Libros** consulta la caché Room de las definiciones completas, recetas,
perfiles congelados, instancias y bolígrafos canónicos del servidor. Permite consultar
el diseño y sus páginas, renombrar la etiqueta, cambiar el grupo de bolígrafos,
confirmar traslados, archivar, desarchivar, eliminar y descargar o compartir el PDF y
la receta. La edición de secciones y maquetación se realiza en Studio web.

Las modificaciones se encolan en el outbox; una petición pendiente **no activa ni
libera** asignaciones en el teléfono. El servidor comprueba revisiones y solapamientos.
El archivo conserva los destinos; eliminar libera solo los de ese libro. Las
instancias cerradas permanecen selladas incluso si sus capturas se han movido.

Los identificadores externos se normalizan únicamente si son MAC completas. El
campo `identifiers` del servidor permite reconocer los alias de una identidad UUID;
los nombres nunca provocan una fusión. El enlace explícito también pasa por el
outbox. El registro de un bolígrafo precede a su primera captura.

Cada trazo mantiene su bolígrafo de origen. La fusión local utiliza instancia y
página cuando existe asignación; los códigos compartidos por libros distintos no se
fusionan globalmente por SOBP. La captura en vivo congela su asignación al entrar en
la cola; todo el lote offline conserva la instantánea del inicio de la descarga.
`assignmentId` y `assignmentRevision` permiten al servidor rechazar una asociación
atrasada sin perder trazos. Los lotes retenidos se muestran en Libros, donde se elige
explícitamente un bolígrafo y su libro de destino o se descarta el lote. La resolución
confirmada desbloquea el CREATE pendiente sin reenviarlo a un libro diferente.

El diseño histórico se obtiene por la referencia de instancia y se rasteriza con
`PdfRenderer`. Sus recursos privados permanecen en el almacenamiento de la app;
el PDF final se descarga únicamente al solicitarlo y se transmite sin regenerarlo.
Los libros generados obtienen sus medidas ISO de Studio, sin consultar NeoLAB.

La migración Room 9 → 10 es aditiva. Conserva todas las capturas y fondos existentes,
añade sus referencias de origen y guarda la caché Studio en tablas nuevas. El test
`StudioMigrationTest` abre una base real creada con el esquema publicado de la v9,
la migra con Room y comprueba que conserva captura, trazos y diseño.

Las copias del servidor se exportan como ZIP por streaming al selector de documentos.
La restauración reconoce ZIP por firma y sigue aceptando JSON antiguo; usa un archivo
temporal para evitar cargar la copia completa en RAM.

Verificación de esta ampliación: `checkApiContract`, `checkFileModes`,
`:core:testDebugUnitTest`, `:neopen:testDebugUnitTest`, `:neopen:assembleDebug` y
`:live:compileDebugKotlin`. La validación física de impresión y lectura con el M1+
requiere una sesión con el dispositivo; no se deduce de estas pruebas de software.

## Habilitación del servidor

Android muestra Libros, su capacidad y los recientes/presets de Studio únicamente
tras verificar `capabilities.studio.enabled === true` en el servidor actual. El
permiso no se guarda en disco, se vincula a URL y credenciales, se invalida al pasar
a local o cambiar de servidor y se revalida al volver al primer plano, cada 30
segundos mientras la app está visible y antes de sincronizar. Si se apaga con
Libros abierto, se vuelve a Bandeja; no se borran los libros ni sus operaciones.

Las mutaciones de libros suspendidas mantienen su outbox y su explicación en el
estado de sincronización. Los bolígrafos, alias y notas ordinarias siguen
sincronizándose. El diseño histórico de una captura se descarga por el recurso
normal `GET /api/captures/{id}/design`, independiente de la activación actual.
