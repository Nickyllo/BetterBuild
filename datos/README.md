# Datos de entrenamiento de BetterBuild

Pares *petición → parámetros* para afinar el modelo local (Qwen3.5-0.8B).

| Archivo | Ejemplos | Para qué |
|---|---|---|
| `train.jsonl` | 60.000 | Entrenamiento |
| `val.jsonl` | 3.000 | Elegir el mejor punto del entrenamiento |
| `test.jsonl` | 3.000 | Nota final, con frases que el entrenamiento no contiene |
| `meta.json` | — | Prompt de sistema, esquema JSON, semilla y estadísticas |
| `audit.json` | — | Resultado de la auditoría de los 44.000 ejemplos |

Formato de cada línea:

```json
{"instruction": "Haz un mesón pequeñito",
 "input": "de 12 de alto, con la puerta al este, iluminado y vallado",
 "output": {"type": "tavern", "height": 12, "door_side": "east", "size": "small", "features": ["fence", "lanterns"]},
 "meta": {"lang": "es", "noisy": false, "held_out": false}}
```

- Generados el 06/10/2026 con la semilla `20260929` (`./gradlew :dataset:generate`).
- 42 tipos de construcción (granjas, pueblo, castillo, portal del Nether…) más peticiones imposibles.
- Cada etiqueta se construyó y validó con el compilador del mod antes de guardarse.
- Auditoría de los 66.000 ejemplos: sin incidencias (etiqueta↔texto en ambos sentidos,
  concordancia, fugas entre conjuntos, contradicciones, nombres de bloque contra la
  traducción oficial del juego).

## Construcciones reales

| Carpeta | Qué hay | Cómo se genera |
|---|---|---|
| `vanilla/` | 1.212 estructuras del juego (aldeas de 5 biomas, puestos, iglús, ruinas…): `indice.jsonl` con medidas y materiales de cada una | `python3 training/vanilla_estructuras.py` |
| `lectura/` | Segunda tarea: leer una construcción. Cada ejemplo es un schematic resumido en texto (medidas, bloques, bloques que dicen su uso, puertas, cómo lo lee el analizador de estilo y dos dibujos) y su JSON. 12.000/600/600 generados y validados con los generadores del mod; `real/` (no se sube) son ~220 casas de aldea y descargas para medir | `./gradlew :dataset:lectura` y `:dataset:lecturaReal` |
| `planetminecraft/` | Schematics descargados a mano: `indice.jsonl` con medidas, materiales y el estilo que aprende el Arquitecto; `fuentes.csv` con categoría, enlace, autor y licencia | `./gradlew :dataset:importar` (lee `~/Descargas`) |

Los `.nbt` de `vanilla/estructuras/` son de Mojang y no se versionan; el script los vuelve a sacar del jar.

De `planetminecraft/` solo se suben las construcciones de `publicables/`: las que tienen en `fuentes.csv` autor y una licencia que deja compartirlas (Creative Commons sin ND, CC0 o permiso del autor). Los originales se quedan en `schematics/` (ignorada) y en la carpeta `schematics/planetminecraft/` del juego.
