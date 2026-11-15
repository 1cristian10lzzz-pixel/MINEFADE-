# Minefade Data Mod — formato .mfmod v1

Archivo de texto UTF-8, una propiedad `clave=valor` por línea. Las líneas que empiezan con `#` son comentarios. No es ZIP y no ejecuta código.

**Metadatos:** `id`, `name`, `version` (formato `1` o `1.0`), `enabled` (`true`/`false`), `description` opcional.

**Ajustes que el motor reconoce actualmente:**

| Clave | Tipo | Límite |
|---|---|---:|
| `spawn.sheep`, `spawn.cow`, `spawn.bird`, `spawn.pig` | entero | 0–24 |
| `player.speed` | decimal | 0.5–2.0 |
| `world.day_speed` | decimal | 0.25–4.0 |

**Restricciones:** 32 KB, 128 líneas, claves únicas, solo propiedades conocidas; los valores deben estar dentro de rango. El paquete importado queda apagado hasta que el usuario lo active en MODS. La API ampliada para bloques, ítems, texturas, recetas, biomas, estructuras y entidades se implementará en fases posteriores, no debe simularse con claves no reconocidas.

```ini
# Minefade data mod v1
id=minefade_ultimate_core
name=Minefade Ultimate Core
version=1.0
description=Mejora ligera del movimiento y del ritmo diurno.
enabled=false
player.speed=1.08
world.day_speed=1.05
```
