# Historial del esquema Room

Los JSON 2..8 se recuperaron de las entidades publicadas en el historial Git;
el 9 ya era el esquema exportado vigente. Referencias usadas:

| Esquema | Commit fuente |
|---|---|
| 2 | `34c54e5` |
| 3 | `0788837` |
| 4 | `f82b7f4` |
| 5 | `feb0889` |
| 6 | `2159883` |
| 7 | `506c055` |
| 8 | `bcc5461` |

Room regenera los hashes de identidad de los snapshots reconstruidos. La prueba
`NeopenDbMigrationTest` crea bases desde estos assets y valida cada salto, además
de comprobar que la cadena 2→9 conserva datos reales.
