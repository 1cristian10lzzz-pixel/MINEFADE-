# Minefade Native Engine — revisión integral de sistemas (0.6.0)

Reconstrucción nativa independiente de Minefade para Android: **C++20**, **OpenGL ES 3.0**, **RmlUi 6.3** y FreeType 2.13.3. El proyecto contiene únicamente el motor y recursos propios de esta rama Native Engine; no modifica el motor binario de Alpha 10.

> **Estado de entrega:** la rama de revisión tiene un workflow de GitHub Actions que ejecuta pruebas nativas, regenera el catálogo/atlas y compila el APK Android de revisión. El workflow confirma la compilación; eso no sustituye una prueba visual, táctil y de instalación en un teléfono físico.

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

- Movimiento táctil, giro de cámara, gravedad, subpasos para reducir el atravesamiento de paredes y salto.
- Movimiento y gravedad reducidos al entrar en agua/lava.
- Barra rápida de nueve espacios; inventario con inserción atómica y límite de pila por definición.
- Si el inventario está lleno, romper un bloque que genera objeto restaura el bloque para no perderlo silenciosamente.
- Tres entidades ambientales de prueba con movimiento y animación básica de extremidades en malla dinámica separada.
- Ciclo día/noche, cielo variable, luz direccional, perfiles de calidad y materiales de agua diferenciados; aún no hay sombras dinámicas por mapa de profundidad.
- Efectos locales de romper, colocar y pulsar botones.
- Pausa, guardado y reanudación se ejecutan desde el hilo GL mediante eventos de `GLSurfaceView`, evitando carreras con el hilo que actualiza el mundo.

Las entidades son modelos provisionales de cajas, no modelos esqueléticos completos. No hay todavía IA compleja, combate, reproducción, domesticación ni persistencia de entidades.

### Interfaz y renderizador

- Renderizador del mundo con shaders GLSL ES 3.00, perspectiva, normales, profundidad y descarte de caras posteriores.
- VAO/VBO separados para mundo, transparencia y entidades.
- Textura atlas por material con canal alfa para superficies transparentes.
- RmlUi 6.3 inicializado mediante interfaces propias de sistema y renderizado OpenGL ES.
- HUD táctil, barra rápida, inventario, pausa, salto, guardado y guardado/salida.

Limitaciones de RmlUi: la interfaz actual carga su HUD desde memoria y usa una fuente de sistema Android. El backend admite geometría, texturas generadas desde memoria y scissor; todavía no admite archivos de imagen externos ni todas las funciones avanzadas de filtros, capas, máscaras y shaders de RmlUi.

## Sistemas que todavía no están completos

- Compilación, firma, instalación y prueba real del APK Android.
- Sombras dinámicas shadow-map, luz indirecta y shaders avanzados.
- Flujo físico del agua, olas, reflejos, lluvia y clima completo.
- Generación extensa de estructuras, dimensiones Nether/End y más biomas.
- Modelos articulados, animaciones esqueléticas, IA, combate y drops físicos de entidades.
- Inventario ampliado con arrastrar/soltar, crafting, armaduras y durabilidad de herramientas.
- Multijugador LAN/Internet y descubrimiento automático NSD.
- Colisión exacta con todos los volúmenes parciales y orientación de escaleras, puertas, vallas, etc.
- Clasificación de transparencia por objeto/cara y tratamiento de materiales refractivos.
- Streaming y descarga de chunks a disco, caché de mallas por chunk y perfilado en GPU/teléfonos reales.

## Verificaciones ejecutadas

- `tests/run-world-tests.sh`: pruebas C++ nativas de mundo, chunks, raycast, guardado/carga, inventario, colisión básica, entidades, entorno y generación asíncrona.
- `tests/test_block_catalog.py`: 306 entradas, IDs consecutivos, nombres no vacíos, 918 tiles únicos, tamaños RGBA/PNG, texturas minerales por cara, drops especiales y bloques protegidos.
- Compilación Java 17 con stubs de API Android para comprobar sintaxis/firmas (no sustituye a AGP/D8).
- Compilación de las pruebas C++ de escritorio con warnings habilitados y ejecución con AddressSanitizer/UBSan.
- Las pruebas nativas no equivalen a enlazar con Android NDK/RmlUi ni a ejecutar el juego en un teléfono.

## Construcción Android (cuando SDK/NDK estén disponibles)

Requisitos fijados:

- JDK 17.
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
