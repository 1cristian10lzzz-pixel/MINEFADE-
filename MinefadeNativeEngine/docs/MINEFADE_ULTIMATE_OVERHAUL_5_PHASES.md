# Minefade Ultimate Overhaul — cinco fases reales

**Base:** APK Minefade 0.1 y rama `minefade-render-overhaul-review12`.  
**Regla:** cada fase se cierra con pruebas; no basta con que exista una pantalla o una clase.

## Orden definitivo

### Fase 1 — Fundación, estabilidad y cargador de mods (P0)
- Preservar APK base, código fuente, recursos y mundos; establecer una compilación reproducible para ARM64 y ARM32.
- Asegurar el ciclo Android/OpenGL: inicio, pausa, reanudación, resize y pérdida/restauración del contexto.
- Corregir bloqueos: mundo blanco, UV/texturas incorrectas, banda negra/safe area, pantalla de carga atascada y fallos al entrar al mundo.
- Validar IDs de bloques, atlas, guardado versionado, escritura atómica y recuperación de mundos.
- Corregir controles táctiles, inventario y HUD básicos para poder jugar sin fallos.
- Formalizar `.mfmod` v1: metadatos obligatorios, lista permitida, límites numéricos, duplicados, tamaño y versión. Solo datos; nada de código nativo arbitrario.
- **Cierre:** CI verde en ambas ABI; instalar/actualizar; abrir, guardar y reabrir mundos; importar, activar y desactivar un mod válido; rechazar uno inválido.

### Fase 2 — Motor, rendimiento y gráficos (P1)
- Medir CPU/GPU/RAM/temperatura y frame time antes/después.
- Priorizar la cola de chunks por distancia y visibilidad; reducir esperas del hilo GL.
- Mejorar greedy meshing, mallas parciales, caché, batching, frustum culling y LOD.
- Auditar GLSL ES 3.0, normales, profundidad, niebla, atlas, filtrado y recursos GPU.
- Mejorar iluminación, sombras escalables, agua, fluidos, lluvia, clima y partículas con presupuestos por dispositivo.
- **Cierre:** recorridos largos sin bloqueos ni texturas corruptas; comparar P50/P95, memoria y temperatura en equipos modestos y potentes.

### Fase 3 — Mundo y jugabilidad (P2)
- Bloques, ítems, estados, orientación, colisiones, herramientas, recetas, hornos, cofres y durabilidad.
- Generación determinista, biomas, cuevas, geología, minerales, estructuras y mazmorras.
- Vegetación, cultivos, fauna, IA, navegación, combate, drops, domesticación y persistencia de entidades.
- Hambre, salud, progresión, redstone, mascotas, monturas, NPC, aldeas, dimensiones y jefes.
- Comandos, creativo, logros, objetivos y misiones.
- **Cierre:** los nuevos datos sobreviven a guardar/cargar; los IDs antiguos se conservan; semillas repetidas producen mundos coherentes.

### Fase 4 — Experiencia Android y multijugador LAN (P3)
- Rediseñar inicio, mundos, avatar, ajustes, inventario, fabricación, pausa y HUD.
- Editor de HUD libre, escalado, safe area, iconos consistentes y eliminación de sombras/fondos no deseados.
- Controles multitáctiles fiables: mantener pulsado, cuatro direcciones, salto, agacharse y distribución zurda.
- Accesibilidad, localización, tamaños de texto, contraste y tutorial.
- LAN por Android NSD/mDNS: detección automática, crear/unirse sin códigos, lista de jugadores, sincronización y salida segura.
- **Cierre:** prueba real entre dos o más teléfonos; desconexión Wi-Fi sin congelar ni dañar mundos individuales.

### Fase 5 — Ecosistema avanzado y lanzamiento (P4)
- API pública de mods versionada: manifiestos, dependencias, conflictos, prioridad y compatibilidad.
- Ampliar mods a bloques, ítems, recetas, texturas, sonidos, biomas, estructuras y entidades.
- Scripts solo con sandbox, permisos y límites de CPU/memoria.
- Multijugador online solo con servidor autoritativo, autenticación y validación de paquetes.
- Distribución con versiones, hashes, procedencia y rollback.
- Regresión, pruebas prolongadas, APK release firmado, actualización y migración de mundos.
- **Cierre:** mods de terceros sin ejecutar código arbitrario; online probado; APK instalado y actualizado en teléfonos reales.

## Distribución de las 50 áreas del catálogo original

- **Fase 1:** 01 arquitectura C++; 02 Android/build; 03 API/cargador; 04 renderizador; 05 chunks; 06 mallas; 07 visibilidad/LOD; 10 texturas; 11 registro de bloques; 23 física del jugador; 26 inventario; 36 HUD base; 37 controles; 38 menús/carga; 43 guardado; 50 pruebas base.
- **Fase 2:** 08 iluminación/shaders; 09 sombras; 13 agua/fluidos; 14 generación del mundo (rendimiento); 18 clima; 20 modelos; 24 cámara; 25 animaciones; 35 sonido/música.
- **Fase 3:** 12 bloques/construcción; 15 biomas; 16 cuevas/geología; 17 estructuras; 19 vegetación; 21 IA; 22 fauna; 27 crafting; 28 combate; 29 objetos/equipo; 30 supervivencia; 31 redstone; 32 monturas/mascotas; 33 NPC/economía; 34 dimensiones/jefes; 45 creativo; 46 comandos/scripting local; 47 misiones/logros.
- **Fase 4:** 39 avatar; 40 LAN; 42 chat/social; 48 tutorial; 49 accesibilidad/localización; acabado final de 36–38.
- **Fase 5:** 41 red online; 44 canal de recursos/contenido; 50 pruebas finales y publicación.

## Límite real del mod loader actual

El motor actual lee paquetes de texto `.mfmod`, hasta 32 KB y 128 líneas. Ajustes que reconoce ahora: `spawn.sheep`, `spawn.cow`, `spawn.bird`, `spawn.pig` (0–24), `player.speed` (0.5–2.0) y `world.day_speed` (0.25–4.0). Metadatos: `id`, `name`, `version`, `description`, `enabled`. No carga todavía nuevos bloques, texturas, recetas ni scripts. Los ajustes de aparición se aplican al iniciar el motor; no se deben usar como spawns naturales persistentes hasta verificar guardado de entidades.

**Nota de honestidad:** el TXT previo contiene 50 áreas y 100 variantes de plantilla por área; no son 5.000 especificaciones completamente independientes. Este roadmap conserva todos los temas y los ordena por dependencias.
