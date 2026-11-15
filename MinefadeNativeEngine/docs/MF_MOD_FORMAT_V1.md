# Minefade Data Mod — formato .mfmod v1

Archivo de texto UTF-8, una propiedad `clave=valor` por línea. Las líneas que empiezan con `#` son comentarios. No es ZIP y no ejecuta código.

**Metadatos:** `id`, `name`, `version` (formato `1` o `1.0`), `enabled` (`true`/`false`), `description` opcional.

**Ajustes que el motor reconoce actualmente:**

| Clave | Tipo | Límite |
|---|---|---:|
| `spawn.sheep`, `spawn.cow`, `spawn.bird`, `spawn.pig` | entero | 0–24 |
| `spawn.rabbit` | entero | 0–24 (máximo combinado 16) |
| `spawn.zombie`, `spawn.skeleton` | entero | 0–24 (máximo combinado 8 por tipo) |
| `player.speed` | decimal | 0.5–2.0 |
| `player.jump` | decimal | 0.75–1.5 |
| `entity.speed` | decimal | 0.5–2.0 |
| `world.day_speed` | decimal | 0.25–4.0 |

**Restricciones:** 32 KB, 128 líneas, claves únicas, solo propiedades conocidas; los valores deben estar dentro de rango. El paquete importado queda apagado hasta que el usuario lo active en MODS. Los paquetes siguen siendo datos seguros: no ejecutan scripts, bibliotecas ni código nativo. `player.speed` ajusta el desplazamiento, `player.jump` la fuerza de salto, `entity.speed` la velocidad de las criaturas y `world.day_speed` el ciclo diurno. Los valores de distintos paquetes habilitados se combinan multiplicativamente y el motor limita el resultado para evitar cifras extremas. El registro de bloques, las texturas, las recetas, los biomas y las estructuras aún no forman parte de esta API.

```ini
# Minefade data mod v1
id=minefade_ultimate_core
name=Minefade Ultimate Core
version=1.0
description=Mejora ligera del movimiento y del ritmo diurno.
enabled=false
spawn.rabbit=1
spawn.zombie=0
spawn.skeleton=0
player.speed=1.08
player.jump=1.08
entity.speed=1.12
world.day_speed=1.05
```
