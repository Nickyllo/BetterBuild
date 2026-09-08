# Arquitectura

> Por qué el código está partido como está, y qué hay que tocar para añadir una
> versión de Minecraft o un loader.

## La regla que ordena todo

**El núcleo no puede importar ni una sola clase de Minecraft.**

No es purismo: es lo que hace viable soportar varias versiones y dos loaders sin
escribir el mod tres veces. Cada versión de Minecraft renombra y reorganiza sus
clases internas; si la lógica estuviera mezclada con ellas, cada versión sería un
mod distinto que mantener.

```
┌──────────────────────────────────────────────────────────────────────┐
│  core/                          Java 21 puro                         │
│                                                                      │
│  blueprint/  El vocabulario: qué puede describir el modelo           │
│  compile/    Primitivas → bloques, determinista                     │
│  validate/   ¿Se sostiene? ¿Se sale? ¿Se puede entrar?              │
│  build/      Orden de colocación, dónde pisa el constructor          │
│  agent/      Las fases del encargo, como máquina de estados          │
│  design/     De dónde salen los diseños (Claude, o local)            │
│  platform/   Interfaces que el juego debe implementar                │
└──────────────────────────────────────────────────────────────────────┘
                                   ▲
            implementan            │
  ┌────────────────────┬───────────┴────────┬────────────────────┐
  │ fabric-1.21.1      │ neoforge-1.21.1    │ forge-1.20.1       │
  │ entidad, varita,   │ ídem               │ ídem               │
  │ comandos, registro │                    │                    │
  └────────────────────┴────────────────────┴────────────────────┘
```

## El contrato entre las dos mitades

Todo lo que el núcleo necesita del juego cabe en cuatro interfaces
(`core/.../platform/`):

| Interfaz | Qué le pide al juego |
|---|---|
| `WorldView` | Leer y colocar bloques, altura del terreno, líquidos, bioma, snapshots |
| `BlockResolver` | ¿Existe este bloque en esta versión? |
| `ChatSink` | Decir algo por el chat |
| `SiteSurvey` | (no es interfaz) lo que se aprende mirando la parcela |

Esa superficie es diminuta a propósito. El núcleo no puede generar entidades, ni
disparar eventos, ni tocar el servidor — así que un cambio en cualquier API de
Minecraft solo puede romper el adaptador, nunca la lógica.

## Cómo se sobrevive a los cambios de versión

**Los bloques se nombran por texto, con cadena de sustitución.**

```java
BlockRef.of("minecraft:tuff_bricks", "minecraft:stone_bricks", "minecraft:cobblestone")
```

`BlockResolver` recorre la cadena y se queda con el primero que exista en la versión
que esté corriendo. Un plano que pide un bloque de 1.21 sigue construyéndose en
1.20.1 con el sustituto, en vez de fallar o dejar un agujero.

## El pipeline

```
DesignProvider → Blueprint → BlueprintCompiler → CompiledStructure
                                                        │
                                   StructureValidator ──┤  (aquí se para si falla)
                                                        │
                                        BuildPlanner ───┴→ BuildPlan
                                                              │
                                            ArchitectBrain ───┴→ BuildStep, uno a uno
```

Cada etapa es una clase con una responsabilidad y sin estado compartido, así que se
prueban por separado y en milisegundos.

### Por qué el modelo solo emite primitivas

`Element` es una `sealed interface`: el vocabulario completo que el modelo puede
usar. `BlueprintDto` es su reflejo plano, del que el SDK deriva el esquema JSON que
la API impone. El modelo **no puede** devolver una forma que el compilador no
entienda.

Todo lo que llega del modelo pasa además por `BlueprintMapper`, que es la frontera de
confianza: recorta cualquier región que se salga de la parcela, rechaza nombres
desconocidos y da valores por defecto a lo que falte.

### Por qué el plan lleva `standAt`

`BuildStep` no dice solo qué bloque va dónde, sino **desde qué posición hay que
colocarlo**. Es la diferencia entre un mod que pega estructuras y una entidad que
las construye: el constructor tiene que estar en algún sitio alcanzable antes de
poder poner un bloque.

El planificador ordena de abajo arriba (la obra se sostiene en todo momento
intermedio, no solo al final), en filas alternas (un recorrido continuo, sin saltos)
y limpia antes de construir dentro de cada capa (nunca está de pie donde va a poner
un bloque).

## Añadir una versión de Minecraft

1. `platform/<loader>-<version>/` con su `build.gradle`.
2. Implementar las cuatro interfaces de `core/platform/`.
3. Registrar la entidad, el ítem de la varita y los comandos.
4. Añadir el módulo a `settings.gradle`.

No se toca nada del núcleo. Si hace falta tocarlo, es señal de que se ha colado
lógica en el adaptador.

## Probar sin abrir el juego

```bash
./gradlew :core:test    # 21 pruebas
./gradlew :core:demo    # dibuja la casa generada en ASCII
```

`FakeWorld` implementa `WorldView` en memoria, así que el pipeline completo —
diseñar, compilar, validar, planificar y ejecutar cada paso — corre en un test
normal. La regresión más común (romper la geometría del compilador) se ve al
instante en el ASCII del `demo`.
