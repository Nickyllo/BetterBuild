# BetterBuild

Un mod de Minecraft donde la IA no es un menú: es **un habitante de tu mundo**.
Un arquitecto que camina, te escucha, va al sitio que le señalas y lo construye
con sus propias manos mientras tú lo ves.

> Estado: el núcleo está programado y probado. La entidad dentro del juego está en
> desarrollo. Ver [el estado detallado](docs/BetterBuild-como-funciona.pdf).

## Documentación

| Documento | Para qué |
|---|---|
| [BetterBuild-como-funciona.pdf](docs/BetterBuild-como-funciona.pdf) | Explicación general, en castellano y sin tecnicismos |
| [docs/flujo-usuario.md](docs/flujo-usuario.md) | El flujo completo desde el punto de vista del jugador |
| [docs/arquitectura.md](docs/arquitectura.md) | Cómo está organizado el código y por qué |

## La idea en una frase

Marcas un terreno con la varita, le dices *«hazme aquí una taberna medieval»*, y él
va andando hasta allí, lo mira, te propone algo y lo construye bloque a bloque.

## Por qué el modelo no coloca bloques

Un LLM se pierde en la geometría voxel. Así que el reparto es:

```
lo que le dices → PLANO declarativo (muro, tejado, arco, ventana…)
                → compilador determinista del mod → bloques
                → validación → el Arquitecto los coloca, andando
```

La IA diseña y conversa. El mod construye. Por eso *«sube el tejado»* es instantáneo
y gratis, y por eso nada llega al mundo sin pasar por el validador.

## Compilar y probar

El núcleo es Java puro: **no hace falta Minecraft para trabajar en él.**

```bash
./gradlew :core:test     # 21 pruebas, segundos
./gradlew :core:demo     # genera una casa y la dibuja en ASCII
```

Los módulos de plataforma necesitan las herramientas de cada loader y son opcionales:

```bash
./gradlew -Pplatforms=true build
```

## Estructura

```
core/                    Java puro, sin una sola clase de Minecraft.
                         Planos, compilador, validador, planificador,
                         cerebro del Arquitecto y cliente de Claude.
platform/
  fabric-1.21.1/         Adaptadores: entidad, varita, comandos, registro.
  neoforge-1.21.1/
  forge-1.20.1/
docs/
```

Añadir una versión de Minecraft es escribir un adaptador nuevo. El núcleo no se toca.

## Configuración

El mod funciona **sin ninguna clave**: el Arquitecto construye desde su repertorio
local. Para que pueda diseñar cosas nuevas, define la credencial en el entorno del
servidor (nunca en el repositorio ni en la partida guardada):

```bash
export ANTHROPIC_API_KEY=...
```
