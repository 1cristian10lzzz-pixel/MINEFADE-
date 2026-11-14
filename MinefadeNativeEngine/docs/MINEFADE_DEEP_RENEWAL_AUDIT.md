# Auditoría de renovación profunda — Minefade 0.7.0 / Review9

Fecha de trabajo: 14 de noviembre de 2026. Rama auditada: `minefade-deep-renewal`.

## Resultado de la auditoría de fuentes

El repositorio no contiene dos motores completos independientes. La rama `main` guarda el paquete del Native Engine comprimido en 32 fragmentos Base64, junto con el workflow que lo reconstruye y el parche de eventos de sonido. La rama `minefade-source-review` es el árbol de trabajo expandido: contiene el proyecto Android, C++20, RmlUi, catálogo modular, texturas generadas, recursos de audio, código LAN y pruebas. La automatización de la rama principal ya reconstruye ese proyecto y aplica el parche de sonido.

La auditoría no encontró un segundo paquete de motor listo para fusionar, ni una clave de firma Android reutilizable. Por eso la renovación parte del árbol expandido y no sobrescribe la rama de revisión anterior. El historial de documentación sí enumera bastantes sistemas aún incompletos; se mantienen como trabajo pendiente y no se presentan como funciones terminadas.

## Funciones existentes que se conservaron

- Las 284 entradas base del catálogo conservan IDs; se agregan 22 bloques hasta 306, seis módulos JSON y atlas RGBA de 576×522 con 918 tiles.
- Mundo voxel, biomas, cuevas, chunks asíncronos, DDA, guardado MFVOX002/lectura MFVOX001, generación determinista y recuperación de aparición.
- Inventario de nueve espacios, barra rápida, objetos caídos, minado por pulsación mantenida y HUD/RmlUi.
- Editor de avatar de seis módulos, persistencia de preferencias, menú de ajustes por categorías, ajustes gráficos/táctiles, audio y descubrimiento LAN que ya existía.
- Render OpenGL ES 3, shader GLSL ES 3.00, atlas, mallas por buffers y pases separados de opacos/transparencia/entidades.

## Renovación implementada en Review9

### Rust como núcleo de simulación y animación

Se añadió `rust-core` como crate Rust estable que se compila a biblioteca estática Android y se conecta al motor C++ mediante una ABI C versionada. El alcance migrado y conectado es:

- Normalización de controles, giro, límites de cámara y salto.
- Movimiento horizontal, gravedad, agua/líquidos y colisiones por callbacks al mundo voxel C++.
- Tiempo de animación, poses del jugador, balanceo de brazos/piernas, respiración, torso, cabeza, parpadeo y pose de salto.
- Movimiento base, fase y poses de criaturas: marcha cuadrúpeda, cabeza, cola, parpadeo y batido independiente de alas del ave.
- Inventario de nueve slots: comprobación de capacidad, fusión de pilas, inserción atómica, selección y consumo.
- Física de los objetos caídos: gravedad, colisión por callback, rebote, temporizador y base de la recogida en C++.
- Actualización de ciclo día/noche, color del cielo y dirección solar.

C++ sigue siendo responsable del host Android/JNI, mundo voxel/guardado, contenedor de estado de inventario y drops, generación y mallas, recogida/proximidad, RmlUi 6.3 y render OpenGL ES. Las operaciones de inventario y la física elemental de drops ya se ejecutan en Rust. Esta frontera evita una reescritura insegura de los subsistemas que dependen de APIs Android/GLES/RmlUi. La presencia del crate no significa que todo el motor esté escrito en Rust.

### Avatar y menús

- La vista de avatar ocupa ahora una tarjeta de previsualización grande arriba y una banda de seis módulos desplazable horizontalmente; el selector de opciones usa una cuadrícula más grande y de ancho completo.
- La vista previa tiene giro automático lento, respiración, movimiento de brazos/cabeza y parpadeo. El arrastre manual suspende el giro automático durante la interacción.
- Las preferencias `avatar_idle_animation`, `avatar_transition_animations` y `reduce_motion` controlan los efectos correspondientes.
- Ajustes deja de repartir la pantalla en una barra lateral estrecha y un contenido reducido. Sus categorías se muestran como pestañas horizontales; el contenido usa el ancho disponible y desplazamiento vertical.
- Los controles de opción usan menús emergentes compactos en lugar de listas desplegables de ancho completo.
- Se añade categoría de animación con interruptor global y calidad Ahorro/Equilibrada/Completa; se conecta al JNI y al núcleo Rust.
- El fondo del menú mueve partículas y bandas de luz con un coste bajo y respeta Reducir movimiento.

### Render y rendimiento

- Las mallas de criaturas se recalculan y suben como máximo a 30 Hz; los objetos caídos/buffers de ítems se suben con una frecuencia limitada a 20 Hz, mientras la simulación sigue usando el delta de frame acotado.
- Se conserva el shader GLSL ES 3.00 y el backend GLES. La corrección anterior del encabezado GLSL y el diagnóstico del compilador quedan como base de esta rama.
- Review10 añade greedy meshing de cubos opacos, descarte por frustum para AABB de chunks/entidades, descarte de caras vecinas cubiertas, LOD por distancia del chunk, dos workers de construcción de malla y batches contiguos de chunks opacos. No son consultas de oclusión GPU ni shadow maps y requieren validación visual/perfilado en dispositivo.

## Validación

La rama Review9 ejecuta en CI:
1. Pruebas unitarias de Rust para movimiento, salto/gravedad, poses, criaturas, ambiente, pilas/capacidad del inventario, consumo y rebote de objetos caídos.
2. Smoke tests C++ del mundo, chunks, inventario, colisiones, jugador y entidades enlazados contra la biblioteca Rust.
3. Pruebas Python del catálogo modular y atlas.
4. Construcción Android con Gradle, CMake, NDK y las dos ABI arm64-v8a/armeabi-v7a.
5. Verificación y publicación del APK debug como artefacto de GitHub Actions.

Un test inicial de Rust descubrió dos problemas de prueba (comparación del valor vacío de una pose y permanencia en suelo). Ambos se corrigieron en el código de prueba/física; la siguiente ejecución de CI es la que determina si la cadena completa está validada. Hasta que el workflow termine en verde no se debe afirmar que Review9 está compilado correctamente.

## Pendiente real — no está simulado como completado

- Rig esquelético general/GLTF, animaciones de combate, herramientas, minería y daño; las poses actuales articulan las cajas voxel existentes.
- IA de producción, pathfinding, combate, domesticación, drops y persistencia completa de criaturas.
- Lluvia/clima, ciclo meteorológico, simulación de caudal/altura de fluidos y refracción.
- Shadow maps, sombras proyectadas de alta calidad, luz indirecta, postprocesado, perfiles GPU y medidas reales de FPS/temperatura.
- Caché incremental de mallas, descarga de chunks lejanos a disco, oclusión GPU por consultas y LOD por objeto/entidad. El culling actual es por frustum y caras cubiertas por vecinos; el LOD actual es por distancia del chunk.
- Sistema completo de crafting, cofres, durabilidad, armaduras funcionales, combate, hambre y progresión.
- Apariencias de avatar remotas en multijugador, sincronización de animación, multijugador Internet y prueba física de LAN/NSD.
- Instalación/actualización sobre una firma de producción común.

## Instalación de prueba

Review9 usa el identificador de paquete `com.minefade.nativeengine.review9` y almacenamiento privado independiente. No sustituye Review8 ni importa automáticamente sus mundos. No desinstales versiones anteriores ni borres datos para probar esta APK. La compilación CI no sustituye la prueba final en el teléfono Mali-G57.


## Review10 — controles y optimización

La rama `minefade-render-optimization` incorpora cambios de HUD, avatar, desplazamiento, logros y renderizado:
- Editor de avatar en dos columnas con previsualización a la derecha e iconos vectoriales rediseñados.
- Joystick nativo visible, teclas direccionales indicativas, salto, botón de agacharse y barra rápida de mayor tamaño/contraste; el D-pad se reserva solo dentro de sus límites reales.
- ScrollViews/HorizontalScrollViews de avatar, ajustes y multijugador recortan el contenido a su viewport para evitar que la lista sobreponga pestañas y paneles.
- Progreso guardado de ocho logros alimentado por eventos del motor.
- Modelos de oveja/vaca/ave con detalles y movimientos secundarios leves; no se trata de una sustitución por modelos esqueléticos.
- Greedy meshing agrupa caras opacas coplanares por tile, con UV local repetible; se guardan rangos de vértices por chunk/material.
- Dos workers construyen mallas, LOD por distancia simplifica plantas/detalles y se descartan AABB fuera del frustum. Los chunks opacos contiguos se agrupan en llamadas de dibujo. Las caras vecinas ocultas se eliminan durante el propio meshing.

La verificación CI de esta rama debe completarse por separado; hasta que pase tests C++/Rust, catálogo y Gradle no se considera listo el APK Review10. La comprobación automatizada no sustituye la prueba táctil y gráfica en el teléfono real.
