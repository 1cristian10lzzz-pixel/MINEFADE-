# Minefade Native Engine — revisión integral de sistemas (0.5.0)

Reconstrucción nativa independiente de Minefade para Android: **C++20**, **OpenGL ES 3.0**, **RmlUi 6.3** y FreeType 2.13.3. El proyecto contiene únicamente el motor y recursos propios de esta rama Native Engine; no modifica el motor binario de Alpha 10.

> **Estado de entrega:** el código fuente y los recursos están preparados y las pruebas nativas del mundo y del catálogo pasan en este entorno. **No se ha generado un APK instalable aquí.** No hay Android SDK, NDK, `gradle`, `sdkmanager` ni `adb` disponibles en el entorno de trabajo y la descarga de Gradle no está accesible. Por eso no adjunto un APK inventado ni afirmo que se haya instalado o probado en un teléfono.

## Qué se mejoró en esta revisión

### Bloques: catálogo completo de la fuente aportada

Se registraron **284 de 284 definiciones** incluidas en `assets/resource_packs/vanilla/blocks.json` del APK de referencia que proporcionó el usuario. Cada entrada tiene:

- ID estable, clave de origen y nombre en español.
- Material/sonido y alias de textura superior, lateral e inferior.
- Tres celdas propias en el atlas: **852 tiles únicos**.
- Texturas de minerales regeneradas con vetas irregulares conectadas, bordes sombreados y brillos facetados distintos para carbón, hierro, oro, redstone, diamante, lapislázuli, esmeralda y cuarzo. Las caras superior, lateral e inferior se sombrean por separado.
- Forma aproximada, colisión/render flags, transparencia y fluido.
- Resistencia, herramienta preferida, nivel de luz, límite de pila y bloque de caída.
- Reglas específicas para agua/lava corrientes, horno encendido, mineral de redstone encendido, lámpara, losas dobles y otros bloques especiales.

El atlas RGBA de 576×486 se genera con pixel-art procedimental original de Minefade, patrones específicos por material y bordes extendidos para reducir el sangrado de filtrado. **No se han copiado ni empaquetado imágenes de textura originales de Minecraft.**

**Alcance exacto:** las 284 entradas corresponden al catálogo antiguo 1.8-ish extraído del APK de referencia; no representan todos los bloques de versiones modernas de Minecraft ni cada estado, orientación o variante de colocación. La resistencia y herramienta preferida son aproximaciones de Minefade cuando `blocks.json` no contiene comportamiento de juego suficiente. Las mallas de escalera, valla, puerta, losa, panel y otros bloques especiales son geometrías base; aún no implementan todos sus estados/orientaciones ni colisión de precisión por forma.

### Arranque y creación del mundo

- La primera zona de aparición se genera y valida antes de habilitar el control del jugador.
- En modo individual, los ocho chunks vecinos se solicitan después de la inicialización y se generan con los dos workers existentes. Esto evita bloquear el hilo OpenGL durante la pantalla de carga.
- La sesión LAN conserva su anillo inicial síncrono para que los participantes comiencen con el mismo terreno disponible.
- Los chunks que falten se regeneran de forma determinista; el guardado puede contener solo los chunks ya terminados y visitados.

### Mundo y chunks

- Mundo voxel de 16×16 por chunk y 96 bloques de altura.
- Terreno determinista con ruido de varias escalas, cinco biomas base, océanos, árboles, cactus, cuevas y vetas de carbón, hierro, oro, diamante, lapislázuli y redstone.
- Raycast 3D DDA para romper y colocar bloques.
- Malla con descarte de caras internas de bloques opacos.
- Pasadas separadas para terreno opaco y superficies transparentes/fluido; los triángulos transparentes se ordenan de lejos a cerca antes de dibujar.
- Generación de chunks en un worker asíncrono, una tarea a la vez, priorizada por distancia al jugador.
- Guardado binario `MFVOX002`, escritura temporal y renombrado; lectura compatible con el formato de prototipo `MFVOX001`.

La generación asíncrona actual no es un sistema masivo de workers: mantiene un worker para la siguiente tarea y el render vuelve a construir/subir la malla visible cuando se completan chunks. Los chunks visitados siguen residentes en RAM y el guardado tiene un límite de 4096 chunks.

### Jugador, inventario, entidades y entorno

- Movimiento táctil, giro de cámara, gravedad, subpasos para reducir el atravesamiento de paredes y salto.
- Movimiento y gravedad reducidos al entrar en agua/lava.
- Barra rápida de nueve espacios; inventario con inserción atómica y límite de pila por definición.
- Si el inventario está lleno, romper un bloque que genera objeto restaura el bloque para no perderlo silenciosamente.
- Tres entidades ambientales de prueba con movimiento y animación básica de extremidades en malla dinámica separada.
- Ciclo día/noche, cielo variable y luz direccional por cara.
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
- `tests/test_block_catalog.py`: 284 entradas, IDs consecutivos, nombres no vacíos, 852 tiles únicos, tamaños RGBA/PNG, drops especiales y bloques protegidos.
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
app/src/main/cpp/blocks/                      Registro de las 284 definiciones de bloque
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
