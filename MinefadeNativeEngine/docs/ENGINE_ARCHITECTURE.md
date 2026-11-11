# Arquitectura actual de Minefade Native Engine

```text
Android Activity / SurfaceView (Java)
  ├─ ciclo de vida, almacenamiento privado, audio SoundPool
  └─ JNI NativeBridge (llamadas GL y pausa/reanudación serializadas en el hilo GL)
       └─ Engine (C++20)
           ├─ Player: movimiento, gravedad, colisión y salto
           ├─ Inventory: 9 slots, pilas y validación de capacidad
           ├─ EntitySystem: 3 entidades ambientales provisionales + objetos caídos
           ├─ EnvironmentSystem: ciclo día/noche, cielo y dirección solar
           ├─ VoxelWorld
           │    ├─ chunks 16×16×96, biomas y cuevas
           │    ├─ hasta dos tareas asíncronas de generación de chunks
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


## Revisión nativa de noviembre de 2026

La rama `minefade-source-review` añade una primera corrección verificable sobre la base 0.5.0:

- **Entrada en mundo segura:** antes de habilitar el juego se comprueba la caja de colisión del jugador. Si el punto guardado está dentro de bloques, se busca un punto despejado en superficies cercanas y existe un punto alternativo de recuperación.
- **Minado por pulsación mantenida:** un toque breve ya no rompe instantáneamente. Mantener el dedo quieto sobre la zona de mirada acumula progreso en función de la dureza base del bloque; mover el dedo convierte el gesto en giro de cámara y cancela el minado.
- **Objetos caídos:** los drops se representan como entidades del mundo con gravedad, colisión simplificada, rebote, tiempo mínimo antes de recogerse y recogida por proximidad si hay espacio en el inventario.
- **HUD compacto:** se ocultan el rótulo superior y la instrucción permanente; se reducen los controles y espacios de la barra rápida, se quitan sus números, y la información del bloque objetivo y los objetos recogidos usa texto/progreso sin una caja opaca.
- **Carga de chunks:** la generación del terreno permite hasta dos tareas de chunk simultáneas. La construcción de mallas, su carga a OpenGL ES y parte del trabajo de visibilidad todavía ocurren en el hilo de renderizado.

La compilación de revisión se construye como variante de depuración con identificador de paquete separado `com.minefade.nativeengine.review`, para permitir una instalación de prueba paralela a otra instalación.

## Funciones que siguen pendientes

Esta revisión **no** es una reconstrucción completa de la versión clásica de Minefade. La base de repositorio disponible es el Native Engine 0.5.0 Alpha y todavía no contiene los menús clásicos, la personalización de avatar ni todos los sistemas anteriores. Siguen pendientes:

- Modelo del jugador visible y animado en tercera persona; modelos de entidades con esqueleto, animaciones e IA completa.
- Sistema de herramientas/armadura y bonificaciones de minería; daño, combate, hambre, consumo de alimentos y animación de mano.
- Inventario de 7 columnas, recetas, cofres/tolvas funcionales y un sistema general de objetos no limitados a bloques.
- Menús completos de inicio, ajustes, creación de mundos, logros, chat con emoji, muerte, configuración de controles y minimapa top-down.
- Multijugador LAN/Internet, lluvia, agua dinámica, sombras reales y estructuras de mundo.
- Mallas por chunk con caché incremental, descarga de chunks lejanos, frustum/occlusion culling y LOD. El worker doble es solo un primer paso, no una solución completa de rendimiento.
- Catálogo moderno completo de contenido: el registro actual parte de 284 bloques de una fuente Bedrock 1.8. Aún no incluye como sistemas jugables el equipo de cobre, cofres y golem de cobre, oxidación/encerado ni las incorporaciones posteriores de Minecraft.

Las pruebas automatizadas verifican los smoke tests del mundo, la recuperación de spawn, la física básica de drops y el catálogo. La compilación CI verifica que se genera el APK Android. Ninguna de esas comprobaciones sustituye una prueba visual y táctil en un dispositivo físico.
