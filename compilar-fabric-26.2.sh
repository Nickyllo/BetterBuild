#!/usr/bin/env bash
# Compila BetterBuild para Minecraft 26.2 (Fabric) y lo instala en la instancia.
#
# Sin Gradle ni Loom, igual que el mod de Verity: 26.2 no viene ofuscado, asi que
# javac compila contra el jar del juego tal cual. Segundos en vez de minutos.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRISM="$HOME/.local/share/PrismLauncher"
INSTANCIA="${1:-$PRISM/instances/26.2/minecraft}"
NESTED="$HOME/.local/share/nickyllo-mod/fabric-nested"

JDK="$PRISM/java/java-runtime-epsilon"
JAVAC="$JDK/bin/javac"
JAR="$JDK/bin/jar"
[[ -x "$JAVAC" ]] || { echo "No encuentro el JDK 25 en $JDK" >&2; exit 1; }

CP="$PRISM/libraries/com/mojang/minecraft/26.2/minecraft-26.2-client.jar"
CP="$CP:$(find "$NESTED" -name '*.jar' | sort | tr '\n' ':')"

# El jar mas nuevo de una biblioteca. sort -V (orden de version) y no el sort
# normal: en datafixerupper conviven la 6.0.8 de la instancia de Forge 1.20.1 y
# la 10.0.21 de 26.2, y alfabeticamente "10" va antes que "6".
mas_nueva() {
    find "$PRISM/libraries/$1" -name '*.jar' 2>/dev/null \
        | grep -viE 'forge|1\.20\.1|sources|javadoc' \
        | sort -V | tail -1
}
for lib in com/google/code/gson/gson org/slf4j org/joml org/jspecify \
           com/mojang/datafixerupper com/mojang/brigadier it/unimi/dsi/fastutil \
           io/netty/netty-buffer io/netty/netty-common; do
    j=$(mas_nueva "$lib")
    [[ -n "$j" ]] || { echo "No encuentro ningun jar de $lib" >&2; exit 1; }
    CP="$CP:$j"
done
CP="$CP:$PRISM/libraries/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"

SALIDA="$RAIZ/build/mod-26.2"
rm -rf "$SALIDA"; mkdir -p "$SALIDA"

# El nucleo entero MENOS las tres clases que hablan con la API de Claude: traen
# el SDK de Anthropic, Jackson y OkHttp detras, y empaquetar todo eso dentro de
# un mod es otro problema. Sin ellas el Arquitecto construye igual, desde su
# repertorio local; conectar la IA es el siguiente paso.
FUENTES=$(find "$RAIZ/core/src/main/java" -name '*.java' \
            ! -name 'ClaudeDesignProvider.java' \
            ! -name 'BlueprintDto.java' \
            ! -name 'BlueprintMapper.java')
FUENTES="$FUENTES $(find "$RAIZ/platform/fabric-26.2/src/main/java" -name '*.java')"

echo ">> Compilando..."
"$JAVAC" -encoding UTF-8 -nowarn --release 21 -cp "$CP" -d "$SALIDA" $FUENTES

echo ">> Empaquetando..."
cp -r "$RAIZ/platform/fabric-26.2/src/main/resources/." "$SALIDA/"
NOMBRE="betterbuild-0.1.0.jar"
"$JAR" --create --file "$RAIZ/build/$NOMBRE" -C "$SALIDA" .

if [[ -d "$INSTANCIA/mods" ]]; then
    echo ">> Instalando en $INSTANCIA/mods"
    cp "$RAIZ/build/$NOMBRE" "$INSTANCIA/mods/$NOMBRE"
    ls -la "$INSTANCIA/mods/$NOMBRE"
else
    echo ">> Instancia no encontrada; el jar queda en $RAIZ/build/$NOMBRE"
fi
