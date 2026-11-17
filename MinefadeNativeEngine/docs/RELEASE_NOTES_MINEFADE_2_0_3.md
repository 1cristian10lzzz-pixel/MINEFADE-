# Minefade 2.0.3 — renderer rebuild

**Objetivo de esta actualización:** corregir las texturas/caras de bloques y reducir los tirones al generar terreno, sin cambiar el formato ni los IDs de los mundos existentes.

## Corrección visual

- Se corrigió la inversión vertical duplicada del atlas en los dos shaders del mundo.
- El generador del catálogo, el helper nativo de UV y los shaders ahora comparten orientación y altura del atlas.
- Se conserva filtrado NEAREST y los bordes de tiles con margen para evitar mezclar texeles vecinos.
- Se corrigieron los colores de agua y lava en el shader de reserva.
- Se suavizó el sombreado precalculado de caras y se mejoró la luz ambiente hemisférica.

## Renderer y streaming de chunks

- Nuevo `WorldMeshRenderer`: recursos VAO/VBO separados para opaque y transparent por chunk.
- Las revisiones de geometría permiten conservar buffers de chunks que no cambiaron.
- Se eliminó el rebuild incondicional de la corona exterior.
- El frustum culling usa los límites verticales reales de la malla del chunk.
- Los chunks transparentes se ordenan por distancia al dibujar y no vuelven a ordenar sus triángulos cuando no cambia su revisión.
- El meshing pendiente se prioriza por distancia y se distribuye en un presupuesto de un chunk por paso a 30 Hz.
- La generación de terreno continúa en trabajadores de fondo.

## Contenido y UX

- Añadida una primera familia de ruinas de superficie deterministas, confinadas dentro del chunk y construidas con IDs existentes.
- Se conservan los mundos y los datos ya guardados; las ruinas solo aparecen en chunks nuevos que aún no estaban guardados.
- La búsqueda LAN comienza automáticamente al abrir LOCAL; se mantienen crear, buscar y unirse.
- Se incorporan pruebas de fuente para NSD/mDNS, límites de protocolo y esquemas de controles.

## Validación automatizada

El workflow ejecuta:
- regresiones C++ del mundo, revisiones de chunk, presupuesto de meshing, caras y normales;
- validación del atlas de 414 bloques y de la orientación UV;
- pruebas de recuperación de inicio y HUD Android;
- pruebas de integración de fuente para LAN y controles táctiles;
- pruebas existentes de Rust, audio, mods y perfiles shader;
- compilación Android, verificación de firma, manifiesto y librerías ARM64/ARM32.

## Trabajo todavía abierto

- No hay benchmark medido de P50/P95, memoria ni temperatura en el Mali-G57 del teléfono.
- No se ha podido probar una sesión LAN real entre dos teléfonos.
- El LOD distante aún no usa quadtree ni varias capas verticales; puede simplificar cuevas y voladizos.
- Fase 3 sigue abierta para mazmorras, estructuras variadas con botín, pathfinding/IA avanzada y persistencia completa de entidades.
- Fase 4 sigue abierta para editor libre de HUD, chat e inventarios/drops sincronizados, autoridad de guardado del anfitrión y reconexión.
- Sombras dinámicas avanzadas y calibración de shader por GPU siguen pendientes.

El workflow de compilación y sus pruebas son obligatorios antes de publicar el APK. La validación visual final debe hacerse en el dispositivo objetivo.
