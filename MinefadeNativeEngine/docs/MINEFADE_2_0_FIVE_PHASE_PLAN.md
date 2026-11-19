# Minefade 2.0 — plan de cierre en cinco fases

Este plan agrupa todo el trabajo que todavía aparece pendiente en la guía técnica 2.0/2.1 y en la auditoría del repositorio. No declara terminados los sistemas que solo estén descritos en documentos. Cada fase debe conservar IDs de bloques, seeds, mundos, inventarios, perfiles del avatar, ajustes y compatibilidad de guardados.

## Fase 1 — Fundación y estabilidad del streaming de mundo

Objetivo: impedir que la carga/generación de terreno trabaje con solicitudes obsoletas y dejar una base verificable para el pipeline de render.

- Auditoría de rama, commit, dependencias, ABI, GLES, ciclo de vida Android y formato de mundos.
- Conservar el formato de guardado y los IDs existentes; pruebas de carga/guardado atómico.
- Colas de generación acotadas por radio, deduplicación por época y prioridad determinista por proximidad.
- Descartar resultados de workers que pertenezcan a una zona de generación anterior.
- Evitar que un worker viejo bloquee el reenvío de una solicitud actual para el mismo chunk.
- Mantener un fallback limitado cuando no se pueda crear un worker o falle una tarea actual.
- Conservar revisiones de malla por chunk y reutilizar capacidad de VBO si la nueva malla cabe.
- Pruebas de regresión para movimientos rápidos, streaming, revisión de mallas y buffers GPU.
- Registrar métricas básicas de solicitudes, trabajos pendientes y resultados descartados.

Criterio de salida: ningún resultado de un epoch antiguo se añade al mundo; no se duplican solicitudes activas; mundos guardados siguen abriendo; las pruebas nativas y el APK compilan.

## Fase 2 — Pipeline de renderizado y corrección visual

Objetivo: completar el camino chunk → snapshot → malla CPU → cola GPU → dibujo, sin bloquear el hilo de presentación.

- Crear snapshots inmutables de chunk y vecinos; versionar datos y descartar mallas obsoletas.
- Workers persistentes para meshing con prioridades por visibilidad, proximidad y dirección del movimiento.
- Cola independiente de subida GPU, limitada por tiempo y bytes por frame; solo el hilo con contexto EGL toca OpenGL.
- Cancelación, deduplicación y límites de memoria CPU/GPU; liberación segura de buffers.
- Culling de frustum y oclusión más avanzada; rangos por chunk/material y visibilidad estable.
- Completar greedy meshing y geometría/colliders de bloques parciales, orientaciones y conexiones.
- Diagnóstico y arreglo reproducible de pantalla blanca, UV, atlas, winding, caras y franja negra.
- Padding de atlas, mipmaps, filtrado, ETC2 y ASTC con fallback según extensiones; evaluar texture arrays.
- Iluminación voxel incremental, luz de cielo/emisión, AO y actualización correcta entre bordes de chunk.
- Medir p50/p95/p99 de frame, generación, meshing, upload, draw calls y memoria en el dispositivo.

Criterio de salida: el render no espera al meshing ni hace subidas ilimitadas; no hay mallas antiguas visibles tras editar; pruebas visuales repetibles en Android.

## Fase 3 — Agua, clima, iluminación y efectos

Objetivo: sustituir las aproximaciones actuales por sistemas ambientales completos que respeten el presupuesto móvil.

- Niveles de fluido y propagación de agua/lava; fuentes, caída y actualización local.
- Transparencia ordenada, depth-write correcto, agua junto a hielo/vidrio, luz bajo el agua.
- Refracción/reflejos aproximados solo donde el dispositivo lo soporte y el perfil lo permita.
- Lluvia con pool de partículas, viento, gravedad, salpicaduras, audio espacial y densidad configurable.
- Ciclo meteorológico y transición de clima/biomas.
- Shadow maps GPU, bias correcto, culling de luz y perfiles de calidad por capacidad.
- Reducir efectos automáticamente bajo carga térmica o exceso de frame time.
- Posprocesado opcional después de estabilizar profundidad, motion y color; comparar MSAA/TAA en hardware real.

Criterio de salida: agua, lluvia y sombras se validan en escenas de prueba y no rompen rendimiento, transparencias ni guardado.

## Fase 4 — LOD de Distant Horizons y generación del mundo

Objetivo: completar terreno distante jerárquico y mejorar la profundidad del mundo sin reemplazar los chunks fuente.

- Separar interfaces del núcleo voxel, almacenamiento, adaptador Android/JNI y backend GLES.
- Jerarquía espacial de regiones (quadtree o equivalente) y selección de LOD por cámara.
- Datos de terreno distante con materiales por columna/capas verticales, no solo altura superficial.
- Persistencia versionada del LOD derivado; invalidación por seed/generador/materiales y reconstrucción desde chunks.
- Transiciones entre niveles sin grietas, solapamientos, z-fighting ni pérdida de cuevas/estructuras importantes.
- Culling de regiones y entidades por distancia/oclusiones; refinamiento progresivo.
- Medir FastNoise2/ARM NEON antes de decidir integración; mantener seeds deterministas y compatibilidad de mundos existentes.
- Más cuevas 3D, acúiferos, minerales, biomas, vegetación y estructuras deterministas que crucen límites de chunk.
- Pregeneración nativa opcional de regiones con pausa, reanudación, cancelación y progreso persistente; no copiar Chunky GPL sin cumplir su licencia.
- Revisar archivos y avisos de licencia de Distant Horizons antes de reutilizar código; no integrar Sodium directamente bajo PolyForm Shield ni copiar Chunky sin análisis GPL.

Criterio de salida: LOD persistente y reconstruible, transiciones visuales correctas y chunks originales como única fuente de verdad.

## Fase 5 — Contenido, interfaz, multijugador y versión estable

Objetivo: completar sistemas de juego y verificar el producto entero antes de declarar una versión estable.

- Rig esquelético GLB/GLTF para jugador y criaturas; animaciones de herramientas, minería, combate, daño y locomoción.
- IA de producción: navegación, pathfinding, combate, drops, domesticación, reproducción y persistencia de entidades.
- Completar herramientas, armaduras, durabilidad, recetas/crafting, cofres, almacenamiento, hambre, daño y progresión.
- Completar catálogo, estados y orientaciones de bloques, modelos, sonidos, efectos, estructuras y biomas pendientes.
- Avatar: conservar apariencia elegida en primera/tercera persona y sincronizar aspecto y animación de jugadores remotos.
- HUD configurable libremente, controles multitáctiles, inventario y paneles comprobados en distintas relaciones de pantalla.
- LAN/NSD con pruebas en dos o más Android; sincronizar mundo, entidades, inventario y autoridad del anfitrión; reconexión y limpieza.
- Multijugador por Internet solo si se implementa el transporte/servicio correspondiente con seguridad y pruebas.
- Ciclo de vida Activity/Surface/EGL, recuperación de contexto, pausa/reanudación, instalaciones encima de versiones previas y migraciones.
- Pruebas largas, Perfetto/Profiler/GPU Inspector, FPS y frame-time p95/p99, memoria, temperatura, crashes y ANR en móviles reales.
- Crear avisos de terceros y revisar licencias/atribuciones; changelog, fuente y APK firmado de producción en el Release.
- No borrar datos de usuario ni publicar una compilación debug como estable.

Criterio de salida: funciones jugables comprobadas, compatibilidad de mundos probada, instalación/actualización verificada en Android real y release reproducible.

## Regla general de cierre

Una compilación verde demuestra que el código compila y pasan las pruebas automatizadas incluidas; no demuestra por sí sola que el juego se vea o funcione bien en un teléfono. Cada fase debe documentar qué cambió, qué prueba lo verifica, qué sigue pendiente y qué no se pudo probar físicamente.