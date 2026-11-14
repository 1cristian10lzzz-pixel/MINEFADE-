# Arquitectura actual de Minefade Native Engine (Review9)

```text
Android Activity / GLSurfaceView (Java)
  ├─ ciclo de vida, almacenamiento privado, SoundPool, NSD/TCP LAN
  └─ JNI NativeBridge (todas las llamadas de mundo/render en el hilo GL)
       └─ Engine (C++20: host Android, mundo, guardados, mallas y GLES)
           ├─ Rust core estático (ABI C versionada)
           │    ├─ movimiento, rotación, salto, gravedad y colisión por callbacks
           │    ├─ poses del jugador: brazos, piernas, torso, cabeza, respiración y blink
           │    ├─ poses de criaturas: marcha, cola, cabeza, alas y blink
           │    ├─ inventario: stack capacity, merge, atomic add, selection and consume
           │    ├─ dropped-item gravity, voxel collision and bounce (C++ retains pickup/storage)
           │    └─ reloj día/noche, iluminación base, cielo y dirección solar
           ├─ Player / Inventory / DroppedItemSystem / EntitySystem (C++)
           ├─ VoxelWorld
           │    ├─ chunks 16×16×96, biomas, ríos y cuevas
           │    ├─ hasta dos tareas asíncronas de generación
           │    ├─ DDA, drops, colocación y guardado MFVOX002 / lectura MFVOX001
           │    └─ mallas opaca/transparente y objetos con atlas
           ├─ OpenGL ES 3.0
           │    ├─ shader GLSL ES 3.00, luz direccional, niebla y ondas
           │    ├─ VAO/VBO de mundo, transparencia, entidades e ítems
           │    └─ atlas RGBA 576×522 / 918 tiles
           └─ RmlUiRuntime + RmlUiOpenGLES
                ├─ HUD/RML desde memoria, touch, inventario y pausa
                └─ menús Java: Avatar, Ajustes, mundo y módulos LAN
```

## Frontera Rust/C++

Rust es ahora el núcleo de simulación, inventario y evaluación de poses, no una reescritura indiscriminada de todo el juego. `rust-core` expone una ABI C con versión y estructuras `repr(C)`; CMake compila la biblioteca estática para `aarch64-linux-android` y `armv7-linux-androideabi`. El código C++ mantiene el acceso a Android/JNI, RmlUi, OpenGL ES, mundo voxel, catálogo, guardados, mallas y transporte LAN. Los tamaños de las estructuras de la ABI tienen comprobaciones `static_assert` en C++ y el motor valida la versión ABI al arrancar.

Esta frontera preserva una única autoridad de mundo/colisiones en C++ y evita duplicar chunks o guardados en Rust. La simulación Rust consulta el mundo mediante callbacks de solo lectura. El array de nueve slots sigue almacenándose en C++ para mantener el formato del guardado, pero todas las decisiones de capacidad/pila/consumo se ejecutan en Rust. Los drops mantienen su colección/recogida en C++, mientras gravedad, colisiones, rebote y edad se calculan en Rust.

## Catálogo de bloques

La fuente se divide en seis módulos JSON más un manifiesto de orden estable. La fuente original conserva 284 entradas base y las extensiones modernas añaden 22 bloques: 306 bloques en total con tres tiles por bloque. El generador combina los módulos en una tabla C++ contigua (lookup eficiente y localidad de caché) y un atlas de 918 tiles. Los píxeles son originales y se generan de forma reproducible mediante `tools/generate_block_catalog.py`.

El catálogo cubre nombres, alias, material/sound, forma aproximada, flags, luz, dureza, herramienta, drop y límite de pila. No es paridad con cada estado y orientación de Minecraft: los metadatos no presentes en la fuente son aproximados y varias geometrías todavía usan formas simplificadas.

## Dependencias fijadas

- RmlUi 6.3
- FreeType 2.13.3
- Android Gradle Plugin 9.5.0 / Gradle 9.6.0 / JDK 17
- Android NDK 28.2.13676358
- CMake 3.22.1
- Rust stable + Cargo (targets Android arm64-v8a y armeabi-v7a)
- OpenGL ES 3.0

## Límites actuales

- El backend RmlUi no decodifica imágenes externas y no implementa todos los features avanzados de layers, filters, clip masks y shaders.
- Las sombras actuales son geometría de contacto simple; todavía no hay shadow maps ni sombras dinámicas proyectadas sobre bloques.
- El render de transparencias ordena triángulos por distancia, una aproximación móvil; el agua ya ondula con shader, pero todavía no simula caudal, niveles ni refracción compleja.
- La geometría de las formas parciales y su colisión todavía no representa todos los estados, conexiones y rotaciones.
- La generación usa hasta dos workers y no descarga chunks visitados de RAM; las mallas animadas de criaturas se actualizan con límite de 30 Hz y los buffers de ítems con límite de 20 Hz.
- Los jugadores/entidades tienen poses articuladas por pivotes sobre mallas voxel de cajas; todavía no son rigs esqueléticos GLB/GLTF, y las criaturas aún usan IA ambiental provisional.
- La lluvia, la simulación completa de fluidos, los shadow maps, las sombras dinámicas proyectadas y el multijugador de Internet siguen pendientes.
- La CI verifica tests Rust/C++, catálogo y empaquetado Android. Sigue haciendo falta una prueba visual/táctil en dispositivo físico; no se afirma rendimiento o estabilidad Mali hasta realizarla.


## Revisión nativa de noviembre de 2026

La rama `minefade-source-review` añade una primera corrección verificable sobre la base 0.5.0:

- **Entrada en mundo segura:** antes de habilitar el juego se comprueba la caja de colisión del jugador. Si el punto guardado está dentro de bloques, se busca un punto despejado en superficies cercanas y existe un punto alternativo de recuperación.
- **Minado por pulsación mantenida:** un toque breve ya no rompe instantáneamente. Mantener el dedo quieto sobre la zona de mirada acumula progreso en función de la dureza base del bloque; mover el dedo convierte el gesto en giro de cámara y cancela el minado.
- **Objetos caídos:** los drops se representan como entidades del mundo con gravedad, colisión simplificada, rebote, tiempo mínimo antes de recogerse y recogida por proximidad si hay espacio en el inventario.
- **HUD compacto:** se ocultan el rótulo superior y la instrucción permanente; se reducen los controles y espacios de la barra rápida, se quitan sus números, y la información del bloque objetivo y los objetos recogidos usa texto/progreso sin una caja opaca.
- **Carga de chunks:** la generación del terreno permite hasta dos tareas de chunk simultáneas. La construcción de mallas, su carga a OpenGL ES y parte del trabajo de visibilidad todavía ocurren en el hilo de renderizado.

La rama `minefade-deep-renewal` prepara Review9 con identificador de paquete separado `com.minefade.nativeengine.review9`, para permitir instalación paralela a las revisiones anteriores y mantener los datos privados separados.

## Funciones que siguen pendientes

Esta revisión **no** es una reconstrucción completa de la versión clásica de Minefade. La base disponible es el Native Engine y conserva sus límites de sistemas jugables. Los menús, ajustes modulares y editor de avatar están presentes en esta rama; lo siguiente sigue pendiente:

- Rig esquelético GLB/GLTF general para jugadores y criaturas; las poses actuales articulan las cajas voxel existentes. IA de criaturas de producción, pathfinding, combate y sincronización de animaciones remotas.
- Sistema de herramientas/armadura y bonificaciones de minería; daño, combate, hambre, consumo de alimentos y animación de mano.
- Inventario de 7 columnas, recetas, cofres/tolvas funcionales y un sistema general de objetos no limitados a bloques.
- Logros con progresión completa, chat con emoji, pantalla de muerte y minimapa top-down.
- Multijugador de Internet, lluvia/clima, simulación completa de caudal de fluidos, refracción, shadow maps y estructuras de mundo avanzadas.
- Mallas por chunk con caché incremental, descarga de chunks lejanos, frustum/occlusion culling y LOD. El worker doble es solo un primer paso, no una solución completa de rendimiento.
- Catálogo moderno completo de contenido: el registro actual conserva 284 bloques de una fuente Bedrock 1.8 y agrega 22 bloques minerales/modernos. Aún no incluye como sistemas jugables el equipo de cobre, cofres y golem de cobre, oxidación/encerado, geodas completas ni todas las incorporaciones posteriores de Minecraft.

Las pruebas automatizadas verifican los smoke tests del mundo, la recuperación de spawn, la física básica de drops y el catálogo. La compilación CI verifica que se genera el APK Android. Ninguna de esas comprobaciones sustituye una prueba visual y táctil en un dispositivo físico.

## Opciones gráficas y avatar

El menú de ajustes separa vídeo, audio, controles, interfaz, animación y accesibilidad en pestañas horizontales de ancho completo y usa menús emergentes compactos. Calidad, distancia de chunks, FOV, sombras de contacto, ondas de agua, sensibilidad, inversión vertical y perfil de animación se entregan por JNI. La vista previa del avatar usa proyección de cajas 3D con giro táctil/automático, respiración, parpadeo y balanceo. Las seis elecciones cambian materiales, cabello, ojos, ropa, armadura y accesorios. El modelo en mundo tiene poses de brazo/pierna/torso/cabeza calculadas por Rust, pero sigue siendo una malla voxel de piezas, no un rig esquelético GLB/GLTF; las poses de animación remotas aún no se envían en el protocolo LAN.

## Rust: alcance real de la migración

La biblioteca `rust-core` ya está enlazada al motor de Review9. El módulo incluye movimiento/colisión por callbacks, actualización del jugador, cálculo de poses del jugador/entidades y ciclo día/noche/cielo. Hay tests unitarios de Rust, smoke tests C++ que enlazan la biblioteca y build Android para las ABI objetivo. C++ conserva mundo/guardados, entidad como contenedor de estado, inventario, catálogo, RmlUi y backend GLES. La migración no significa que todo el juego sea Rust; se continuará por fronteras puras y comprobables.

