# Minefade Native Engine — renovación profunda Rust/GLES (0.7.0)

Motor de Minefade para Android con **núcleo de simulación y animación Rust**, puente C ABI, **C++20** para mundo voxel/JNI y adaptadores de render, **OpenGL ES 3.0**, **RmlUi 6.3** y FreeType 2.13.3. El proyecto contiene únicamente el motor y recursos propios de esta rama Native Engine; no modifica el motor binario de Alpha 10.

> **Estado de entrega:** la rama de revisión tiene un workflow de GitHub Actions que ejecuta pruebas nativas, regenera el catálogo/atlas y compila el APK Android de revisión. El workflow confirma la compilación; eso no sustituye una prueba visual, táctil y de instalación en un teléfono físico.


## Renovación profunda Review9 — Rust, animación y UI

La rama `minefade-deep-renewal` incorpora un núcleo `rust-core` enlazado como biblioteca estática Android. Rust procesa movimiento, gravedad, colisiones por callbacks al mundo C++, poses del jugador/criaturas y el reloj/cielo del ambiente. C++ mantiene el mundo voxel y guardado, el host JNI, RmlUi y OpenGL ES. La migración es incremental y explícita; no se declara que todo el juego sea Rust.

La pantalla Avatar se reorganiza alrededor de una previsualización grande con respiración, parpadeo, movimiento suave y giro automático; las seis categorías quedan en pestañas horizontales con opciones mayores. Ajustes pasa a categorías horizontales que aprovechan todo el ancho, contenido desplazable y opciones compactas emergentes. Se añade una categoría de animación con interruptor y calidad, conectada al core Rust. El fondo ambiental respeta Reducir movimiento. La geometría animada de criaturas se actualiza a 30 Hz y los buffers de ítems a 20 Hz para reducir presión de subida al GPU.

El informe detallado de qué se encontró, qué se integró y qué sigue pendiente está en `docs/MINEFADE_DEEP_RENEWAL_AUDIT.md`. El sistema aún no tiene rig GLTF completo, clima/lluvia, shadow maps, simulación de fluidos completa, LOD/culling ni validación de rendimiento en un teléfono físico.

## Qué se mejoró en esta revisión

### Bloques: catálogo completo de la fuente aportada

Se conservaron las **284 definiciones base** del catálogo 1.8-ish del APK de referencia y se añadieron **22 bloques modernos** (cobre, menas de pizarra profunda, restos ancestrales, amatista, calcita, toba y bloques de metal en bruto). El registro actual contiene **306 bloques**; los 284 IDs antiguos mantienen su posición para no invalidar partidas guardadas. Cada entrada tiene:

- ID estable, clave de origen y nombre en español.
- Material/sonido y alias de textura superior, lateral e inferior.
- Tres celdas propias en el atlas: **918 tiles únicos**.
- Texturas de minerales regeneradas con vetas irregulares conectadas, bordes sombreados y brillos facetados distintos para carbón, hierro, oro, redstone, diamante, lapislázuli, esmeralda y cuarzo. Las caras superior, lateral e inferior se sombrean por separado.
- Forma aproximada, colisión/render flags, transparencia y fluido.
- Resistencia, herramienta preferida, nivel de luz, límite de pila y bloque de caída.
- Reglas específicas para agua/lava corrientes, horno encendido, mineral de redstone encendido, lámpara, losas dobles y otros bloques especiales.

El atlas RGBA de 576×522 se genera con pixel-art procedimental original de Minefade, patrones específicos por material y bordes extendidos para reducir el sangrado de filtrado. **No se han copiado ni empaquetado imágenes de textura originales de Minecraft.**

**Alcance exacto:** el catálogo conserva las 284 entradas originales 1.8-ish y añade 22 bloques modernos, pero todavía no es una recreación completa de todas las versiones actuales de Minecraft ni de cada estado, orientación o variante de colocación. La resistencia y herramienta preferida son aproximaciones de Minefade cuando `blocks.json` no contiene comportamiento de juego suficiente. Las mallas de escalera, valla, puerta, losa, panel y otros bloques especiales son geometrías base; aún no implementan todos sus estados/orientaciones ni colisión de precisión por forma.

### Catálogo modular de bloques

Las definiciones de contenido ya no se editan como una única lista monolítica. Se organizan en `assets/data/block_modules/`:

- `01_terrain.json`: terreno, roca, suelos y materiales naturales.
- `02_minerals.json`: menas, minerales profundos, cobre, amatista y materiales de minería.
- `03_wood_building.json`: madera, construcción, puertas, vallas, escaleras y almacenamiento.
- `04_vegetation.json`: plantas, hojas, cultivos, flores, corales y vegetación.
- `05_mechanics_redstone.json`: redstone, mecanismos, raíles y componentes.
- `06_decoration_misc.json`: decoración y bloques restantes.
- `manifest.json`: orden estable de IDs y lista de módulos.

El generador combina los módulos siguiendo el manifiesto y valida duplicados/omisiones. El registro C++ se sigue generando como una tabla contigua compacta para conservar búsqueda por ID rápida y buena localidad de caché; separar los archivos editables no obliga a fragmentar la tabla en tiempo de ejecución.

### Arranque y creación del mundo

- La primera zona de aparición se genera y valida antes de habilitar el control del jugador.
- En modo individual, los ocho chunks vecinos se solicitan después de la inicialización y se generan con los dos workers existentes. Esto evita bloquear el hilo OpenGL durante la pantalla de carga.
- La sesión LAN conserva su anillo inicial síncrono para que los participantes comiencen con el mismo terreno disponible.
- Los chunks que falten se regeneran de forma determinista; el guardado puede contener solo los chunks ya terminados y visitados.

### Mundo y chunks

- Mundo voxel de 16×16 por chunk y 96 bloques de altura.
- Terreno determinista con ruido Perlin/fractal de varias escalas, océanos, ríos, pantanos, desiertos, sabanas, bosques, llanuras, nieve y montañas; cuevas amplias, vetas minerales, árboles variables, flores, cactus y formaciones rocosas.
- Raycast 3D DDA para romper y colocar bloques.
- Malla con descarte de caras internas de bloques opacos.
- Pasadas separadas para terreno opaco y superficies transparentes/fluido; los triángulos transparentes se ordenan de lejos a cerca. El agua incorpora ondulación animada por shader en la superficie superior; todavía no es una simulación completa de niveles/caudal.
- Generación de chunks con hasta dos workers asíncronos y cola de prioridad por distancia al jugador; la distancia de visión se configura en tres niveles.
- Guardado binario `MFVOX002`, escritura temporal y renombrado; lectura compatible con el formato de prototipo `MFVOX001`.

La generación asíncrona actual no es un sistema masivo de workers: mantiene un worker para la siguiente tarea y el render vuelve a construir/subir la malla visible cuando se completan chunks. Los chunks visitados siguen residentes en RAM y el guardado tiene un límite de 4096 chunks.

### Jugador, inventario, entidades y entorno

- El núcleo Rust gestiona movimiento normalizado, giro, salto, gravedad, movimiento en fluidos y colisiones contra el mundo C++ mediante callbacks.
- La prueba de contacto bajo los pies mantiene el estado de suelo estable y evita el pequeño rebote que producía la gravedad en cada frame; al caminar fuera de un borde, el jugador cae normalmente.
- Las poses del jugador se calculan en Rust: brazos/piernas, respiración, torso, cabeza, ojos/parpadeo y transición de salto. C++ aplica la pose a los pivotes de las piezas de la malla.
- La simulación Rust calcula marcha, balanceo de cabeza, cola, patas, alas y parpadeo de las criaturas ambientales; las mallas de entidad son geométricas voxel articuladas, no rigs externos.
- Los jugadores remotos reciben una pose de reposo animada; el protocolo LAN actual aún transmite posición/yaw, no el estado completo de movimiento/animación.
- Barra rápida de nueve espacios; inventario con inserción atómica y límite de pila por definición.
- Si el inventario está lleno, romper un bloque que genera objeto restaura el bloque para no perderlo silenciosamente.
- El ciclo día/noche, daylight, dirección solar y color del cielo son actualizados por Rust; el render C++ consume esos valores.
- Tres entidades ambientales de prueba tienen movimiento y pose; todavía no se han convertido en fauna con IA de producción, daño, domesticación y persistencia.
- Las mallas animadas de criaturas se reconstruyen/suben como máximo a 30 Hz; los buffers de ítems se suben a 20 Hz para aliviar el hilo GLES sin detener la simulación.
- Efectos locales de romper, colocar, salto, pasos, recogida y UI.
- Pausa, guardado y reanudación se ejecutan desde el hilo GL mediante eventos de `GLSurfaceView`.

### Interfaz y renderizador

- Avatar: previsualización grande, giro manual/automático, respiración, parpadeo, balanceo de miembros y transición respetuosa con Reducir movimiento. Las seis categorías se muestran en pestañas horizontales y las opciones tienen celdas mayores.
- Ajustes: categorías de ancho completo (vídeo, audio, controles, HUD, animación y accesibilidad), contenido desplazable, desplegables compactos emergentes y sin controles que no tengan efecto conectado.
- Fondo de menús con desplazamiento ambiental y partículas de bajo coste; se detiene cuando se activa Reducir movimiento.
- Renderizador del mundo con shaders GLSL ES 3.00, perspectiva, normales, profundidad y descarte de caras posteriores.
- VAO/VBO separados para mundo, transparencia y entidades/ítems.
- Textura atlas por material con canal alfa para superficies transparentes.
- RmlUi 6.3 inicializado mediante interfaces propias de sistema y renderizado OpenGL ES.
- HUD táctil, barra rápida, inventario, pausa, salto, guardado y guardado/salida.

Limitaciones de RmlUi: el HUD actual carga desde memoria y usa fuente de sistema Android. El backend admite geometría, texturas generadas desde memoria y scissor; todavía no decodifica archivos de imagen externos ni implementa todos los filtros, capas, máscaras y shaders avanzados de RmlUi. La vista de avatar usa proyección 3D por cubos, no un visor GLTF de rigs completos.

## Sistemas que todavía no están completos

- Rig esquelético general GLB/GLTF, animaciones de combate/herramientas/minería, primera persona de mano animada y sincronización del estado de animación remoto.
- IA de producción, pathfinding, combate, daño, hambre, domesticación, reproducción y persistencia completa de criaturas.
- Inventario ampliado, recetas/crafting completo, cofres funcionales, durabilidad de armaduras y progresión.
- Multijugador Internet. LAN/NSD/TCP ya está implementado como prototipo, pero necesita pruebas físicas con dos dispositivos y no replica todavía las poses/velocidades completas.
- Lluvia y clima, caudal/niveles de fluidos, refracción y reflejos.
- Shadow maps, iluminación indirecta, culling/occlusion, LOD y caché/descarga de chunks lejanos.
- Geometrías y colisiones exactas para todos los estados, conexiones y orientaciones de escaleras, puertas, vallas, etc.
- Perfilado de GPU/FPS/temperatura y validación visual/táctil en teléfonos Mali y otros controladores.

## Verificaciones y construcción

El workflow `.github/workflows/native-review.yml` en la rama `minefade-deep-renewal` ejecuta:
- `cargo test` y build release de `rust-core`.
- Smoke tests C++ del mundo, chunks, raycast, guardado/carga, inventario, jugador y entidades enlazados contra la biblioteca estática Rust.
- `tests/test_block_catalog.py` y regeneración de módulos/atlas.
- Compilación Android con Gradle, CMake y NDK, con ABI `arm64-v8a` y `armeabi-v7a`.
- Verificación y publicación del APK debug Review9 como artefacto.

Una ejecución verde confirma tests y compilación de CI; no confirma por sí misma que el juego ya se haya probado en un teléfono físico. La instalación Review9 usa el paquete `com.minefade.nativeengine.review9` y guarda partidas por separado, sin importar automáticamente mundos de versiones anteriores.

## Construcción Android

Requisitos fijados:

- JDK 17.
- Rust stable + Cargo, con targets `aarch64-linux-android` y `armv7-linux-androideabi`.
- Android SDK Platform 36 y Build Tools 36.0.0.
- Android NDK `28.2.13676358`.
- CMake `3.22.1`.
- Gradle `9.6.0` y Android Gradle Plugin `9.5.0`.
- Conexión a GitHub durante CMake para descargar FreeType 2.13.3 y RmlUi 6.3.

Desde la raíz del proyecto:

```bash
gradle --no-daemon :app:assembleDebug
```

Salida esperada cuando la compilación termina correctamente:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Instalación con ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El workflow `.github/workflows/android.yml` configura JDK, Gradle, SDK, NDK y CMake y publica el APK debug como artefacto. Debe ejecutarse en un repositorio GitHub con este proyecto; no se ha ejecutado en esta sesión.

## Estructura

```text
app/src/main/java/com/minefade/nativeengine/  Host Android, SurfaceView, JNI y audio
app/src/main/cpp/engine/                      Motor principal y renderizador 3D
app/src/main/cpp/world/                       Mundo voxel, chunks, raycast, malla y guardado
app/src/main/cpp/blocks/                      Registro de las 306 definiciones de bloque
app/src/main/cpp/game/                        Jugador, inventario, entidades y entorno
app/src/main/cpp/ui/                          Runtime RmlUi y backend OpenGL ES
app/src/main/assets/data/                    Catálogo y definiciones de referencia
app/src/main/assets/textures/                Atlas RGBA original de Minefade
app/src/main/assets/audio/                   Efectos de sonido
 tests/                                        Pruebas nativas y del catálogo
 tools/                                        Generador reproducible de catálogo/atlas
 docs/                                         Arquitectura, lista de bloques y preview
 .github/workflows/                            Workflow de compilación Android
```

### Revisión de interfaz y avatar

- La portada elimina el eslogan de gran tamaño y las etiquetas de versión/estado del motor; las acciones principales quedan más cerca del encabezado.
- El fondo de pantallas deja la cuadrícula rígida y usa capas de luz, arcos y partículas tenues.
- Ajustes se divide en Vídeo, Audio, Controles, Interfaz y Accesibilidad.
- Calidad gráfica, distancia de chunks, campo de visión, sombras de contacto, ondulación del agua, sensibilidad, inversión vertical, escala del HUD y preferencias de audio se guardan y se aplican al iniciar una partida.
- El editor de avatar usa módulos de piel, cabello, ojos, ropa, armadura y accesorios. Los selectores son miniaturas geométricas sombreadas y la vista previa de voxel proyectado desde cajas 3D se puede girar arrastrando. Las seis elecciones se envían al jugador nativo mediante JNI.
- Los objetos caídos y el objeto seleccionado en primera persona usan las celdas de textura del bloque correspondiente en vez de una caja genérica.

El editor usa una proyección 3D de piezas voxel dibujadas por Canvas; el jugador en el mundo también recibe las opciones y cambia sus materiales y capas de cabello/armadura/accesorios. El personaje nativo sigue siendo una malla articulada por piezas con animaciones básicas, no un rig esquelético/GLB completo; las apariencias de jugadores remotos aún no se sincronizan por LAN.

### Pase de objetos y acabado del render

- La entidad de mano y los objetos caídos se agrupan en un buffer/pase separado que muestrea el atlas real; los modelos de personajes y criaturas mantienen su propio pase de color por vértice.
- RmlUi carga miniaturas de los bloques desde el mismo atlas mediante rutas internas `minefade://block/ID`; la barra rápida y el inventario muestran iconos con cantidades en vez de abreviaturas de tres letras.
- El shader mezcla niebla de distancia con el color variable del cielo para suavizar el horizonte según la distancia de visión.
- Se añadieron sombras de contacto geométricas sencillas bajo jugador y criaturas; son una aproximación de oclusión local, no sombras dinámicas proyectadas en paredes/terreno.
- El ave ambiental mueve las alas de forma independiente; oveja, vaca y jugador conservan animaciones de marcha básicas.

**Pendiente visual:** las sombras de contacto no sustituyen un shadow map; el agua tiene ondas de superficie, pero aún no existe propagación de flujo, alturas por bloque ni refracción física; el ítem en mano es un mesh de bloque compacto con una manga sencilla, no una animación completa de brazo/herramienta.


## Review10 — controles táctiles y render optimizado

La rama `minefade-render-optimization` prepara Review10 sobre la base Review9, con paquete de prueba independiente `com.minefade.nativeengine.review10`.

- **Controles del mundo:** HUD con joystick de cuatro direcciones visible, salto y agacharse; barra rápida más grande y de mayor contraste. La zona táctil del joystick se excluye de la captura de clics de RmlUi sin bloquear los huecos de la barra rápida.
- **Editor de avatar:** disposición apaisada de dos columnas, controles/estilos a la izquierda y personaje a la derecha. Se redibujan iconos vectoriales por módulo y miniaturas más legibles; se añaden detalles al rostro, botas, manos y accesorios de la vista previa.
- **Paneles y desplazamiento:** los visores verticales y horizontales limitan el dibujo a su propio rectángulo para que el contenido desplazado no pinte por encima de las pestañas ni de otros módulos.
- **Logros:** ocho objetivos con progreso persistente para entrada al mundo, minería, construcción, recogidas y saltos. Los contadores se alimentan de eventos emitidos por el motor durante la partida.
- **Modelos:** oveja, vaca y ave conservan sus poses Rust y reciben detalles geométricos pequeños y movimiento secundario suave de orejas/cola; el ave añade plumas de cola.
- **Malla greedy:** las caras exteriores de cubos opacos coplanares con la misma textura se combinan en rectángulos por chunk. El atlas repite la textura dentro de cada cara grande para evitar estirar el tile.
- **Visibilidad y detalle:** descarte por frustum de AABB de chunks, criaturas y jugadores remotos; caras ocultas por bloques vecinos no se emiten; LOD por distancia simplifica bloques de detalle en chunks lejanos.
- **CPU/GPU:** dos workers construyen las mallas de chunks de forma determinista; se conservan buffers por chunk/material y se fusionan rangos opacos contiguos en llamadas de dibujo. Las transparencias mantienen un pase separado y orden aproximado por distancia.

**Límites importantes:** el descarte de oclusión se hace por caras cubiertas por vecinos; no hay consultas de oclusión GPU ni shadow maps. El LOD actual es por distancia del chunk, no por objeto individual. La generación y construcción de mallas tienen trabajo paralelo, pero la simulación física/IA general no se ha movido íntegramente a workers. El atlas sigue siendo un atlas 2D, no texture arrays. No se afirma FPS concreto ni validación física en GPU Mali hasta probarlo en el teléfono.


## Minefade 0.8.0 — mundos, mods y comandos

La rama `minefade-worlds-mods-commands` prepara la revisión 0.8.0:

- **Mundos independientes:** cada partida tiene carpeta, archivo de mundo y metadatos propios. La pantalla lista las partidas, permite crear, renombrar y eliminar, y conserva el antiguo `default.mfworld` como «Mundo anterior» durante la migración inicial.
- **Creación de mundo:** nombre, semilla, supervivencia/creativo/aventura, trucos y cofre inicial. El cofre conserva su inventario junto al archivo de mundo.
- **Modificaciones:** el módulo MODS permite importar y activar/desactivar archivos `.mfmod`. La primera API carga únicamente pares `clave=valor` con límites; no ejecuta scripts ni código nativo. Efectos incluidos: población adicional de ovejas, vacas, aves y cerdos, velocidad del jugador y velocidad del ciclo día/noche.
- **Comandos:** `/ayuda`, `/oveja [cantidad]`, `/vaca [cantidad]`, `/pollo [cantidad]`, `/cerdo [cantidad]`, `/modo supervivencia|creativo|aventura`, `/tiempo dia|noche`, `/dar <id> [cantidad]`, `/tp <x> <y> <z>` y `/semilla`. Los comandos de trucos requieren que se activen en la creación del mundo.
- **Creativo:** el inventario abre el catálogo de bloques colocables, permite seleccionar un bloque en la barra rápida y no consume el stack al colocar. Aventura no permite minar ni colocar; supervivencia mantiene las reglas anteriores.
- **Fauna:** se añade un modelo de cerdo con patas articuladas, hocico, orejas y movimiento suave de cola; el nuevo comando y los mods pueden generarlo.
- **Texturas y audio:** el generador procedural añade iluminación por cara y patrones específicos para deepslate, cobre envejecido, amatista, prismarina, terracota, piedra del End y panales. Los eventos de audio se estratifican por tipo de material usando las muestras PCM existentes.

Los mundos separados solo guardan partidas creadas desde el nuevo gestor. La copia antigua se migra como mundo independiente al primer inicio. Los mods de datos tienen efectos limitados a las claves documentadas; esto no es todavía una API de scripting, Java mod loader o carga de bibliotecas nativas. La lista de comandos tampoco es paridad completa con Minecraft: se limita a los comandos enumerados y solo funciona dentro de un mundo con trucos permitidos.

El modo creativo reutiliza el catálogo de bloques placeables y la barra rápida de nueve ranuras; no añade aún recetas, herramientas ni un sistema de inventario de doble mano.
