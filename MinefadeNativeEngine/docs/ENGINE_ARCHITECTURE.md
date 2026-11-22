# Arquitectura actual de Minefade Native Engine

```text
Android Activity / SurfaceView (Java)
  ├─ ciclo de vida, almacenamiento privado, audio SoundPool
  └─ JNI NativeBridge (llamadas GL y pausa/reanudación serializadas en el hilo GL)
       └─ Engine (C++20)
           ├─ Player: movimiento, gravedad, colisión y salto
           ├─ Inventory: 9 slots, pilas y validación de capacidad
           ├─ EntitySystem: 3 entidades ambientales provisionales
           ├─ EnvironmentSystem: ciclo día/noche, cielo y dirección solar
           ├─ VoxelWorld
           │    ├─ chunks 16×16×96, biomas y cuevas
           │    ├─ worker asíncrono para generación de un chunk
           │    ├─ raycast DDA y reglas de drops/colocación
           │    ├─ malla opaca + malla transparente ordenada
           │    └─ guardado MFVOX002 / lectura MFVOX001
           ├─ OpenGL ES 3.0
           │    ├─ shader 3D, luz direccional y depth buffer
           │    ├─ VAO/VBO mundo opaco, transparencia y entidades
           │    └─ atlas RGBA Minefade 576×486, 852 tiles
           └─ RmlUiRuntime
                ├─ Rml::SystemInterface
                ├─ Rml::RenderInterface
                ├─ HUD/RML desde memoria, touch, inventario y pausa
                └─ RmlUiOpenGLES (geometría, texturas generadas, scissor)
```

## Catálogo de bloques

La fuente de referencia incluye 284 entradas vanilla del APK de Minecraft aportado. Minefade asigna IDs consecutivos y tres tiles por bloque (superior/lateral/inferior). Los píxeles son originales y se generan de forma reproducible mediante `tools/generate_block_catalog.py`; la herramienta no copia PNG del juego de referencia.

El catálogo cubre nombres, alias, material/sound, forma aproximada, flags, luz, dureza, herramienta, drop y límite de pila. No es paridad con cada estado y orientación de Minecraft: los metadatos no presentes en la fuente son aproximados y varias geometrías todavía usan formas simplificadas.

## Dependencias fijadas

- RmlUi 6.3
- FreeType 2.13.3
- Android Gradle Plugin 9.5.0 / Gradle 9.6.0 / JDK 17
- Android NDK 28.2.13676358
- CMake 3.22.1
- OpenGL ES 3.0

## Límites actuales

- El backend RmlUi no decodifica imágenes externas y no implementa todos los features avanzados de layers, filters, clip masks y shaders.
- El render de transparencias ordena triángulos por distancia, una aproximación móvil; no resuelve refracción ni intersecciones complejas.
- La geometría de las formas parciales y su colisión todavía no representa todos los estados, conexiones y rotaciones.
- La generación usa un único worker y no descarga chunks visitados de RAM.
- Las entidades son modelos temporales de cubos y no tienen animación esquelética o IA de producción.
- El multijugador LAN/Internet, la lluvia, el agua dinámica y las sombras de mapa siguen pendientes.
- La compilación/instalación del APK necesita Android SDK/NDK/Gradle y una prueba real en un dispositivo; no se marca como validada antes de eso.
