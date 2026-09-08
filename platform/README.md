# Adaptadores de plataforma

Cada carpeta es **solo pegamento**: traduce el núcleo al API de una versión y un
loader concretos. Ninguna contiene lógica del mod — si algo de lógica acaba aquí,
está en el sitio equivocado.

| Módulo | Minecraft | Loader | Java |
|---|---|---|---|
| `fabric-1.21.1` | 1.21.1 | Fabric | 21 |
| `neoforge-1.21.1` | 1.21.1 | NeoForge | 21 |
| `forge-1.20.1` | 1.20.1 | Forge | 17 |
| `shared-mc/` | — | *(compartido)* | 17 |

## Por qué existe `shared-mc/`

Los adaptadores de mundo y de chat solo tocan **clases vanilla de Minecraft**, no
clases del loader. Con mappings oficiales de Mojang en los tres módulos, el mismo
fichero compila en Fabric, NeoForge y Forge. Por eso `shared-mc/` no es un módulo,
sino un directorio de fuentes que los tres añaden a su `sourceSet`.

Lo que sí cambia por loader es el punto de entrada y el registro de entidades e
ítems, y eso vive en cada módulo.

## Estado

⚠️ **Estos módulos aún no se han compilado.** Requieren descargar Minecraft, los
mappings y el toolchain de cada loader (varios GB). El núcleo sí está compilado y
probado; estos adaptadores están escritos pero sin verificar, y es esperable tener
que ajustar nombres de métodos al construirlos por primera vez:

```bash
./gradlew -Pplatforms=true :platform:fabric-1.21.1:build
```

## Añadir una versión

1. Copiar el módulo del loader más parecido.
2. Ajustar `gradle.properties` (versión de Minecraft, del loader, y `javaRelease`).
3. Apuntar a `shared-mc/` si las APIs vanilla coinciden; si no, crear `shared-mc-<ver>/`.
4. Registrarlo en `settings.gradle`.
