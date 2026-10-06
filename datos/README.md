# Datos de entrenamiento de BetterBuild

Pares *petición → parámetros* para afinar el modelo local (Qwen3.5-0.8B).

| Archivo | Ejemplos | Para qué |
|---|---|---|
| `train.jsonl` | 40.000 | Entrenamiento |
| `val.jsonl` | 2.000 | Elegir el mejor punto del entrenamiento |
| `test.jsonl` | 2.000 | Nota final, con frases que el entrenamiento no contiene |
| `meta.json` | — | Prompt de sistema, esquema JSON, semilla y estadísticas |
| `audit.json` | — | Resultado de la auditoría de los 44.000 ejemplos |

Formato de cada línea:

```json
{"instruction": "Haz un mesón pequeñito",
 "input": "de 12 de alto, con la puerta al este, iluminado y vallado",
 "output": {"type": "tavern", "height": 12, "door_side": "east", "size": "small", "features": ["fence", "lanterns"]},
 "meta": {"lang": "es", "noisy": false, "held_out": false}}
```

- Generados el 29/09/2026 con la semilla `20260929` (`./gradlew :dataset:generate`).
- Cubren los 15 tipos originales. Los 27 tipos nuevos aún no tienen frases en el léxico.
- Cada etiqueta se construyó y validó con el compilador del mod antes de guardarse.
- Auditoría: 0 defectos, 0 fugas entre conjuntos, 0 contradicciones; 1 aviso de estilo
  ("cimientos de piedra, piedra").
