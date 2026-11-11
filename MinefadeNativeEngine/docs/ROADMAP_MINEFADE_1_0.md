# Hoja de ruta — Minefade 1.0

**Rama de trabajo:** `minefade-source-review`  
**Base técnica actual:** Minefade Native Engine 0.5.0 Alpha (C++20, OpenGL ES 3, RmlUi)  
**Estrategia:** cinco fases acumulativas. No se elimina una función anterior sin sustituirla, probar su equivalente y conservar compatibilidad de datos.

## Reglas de desarrollo

1. Cada fase se implementa en la misma rama de trabajo, con commits pequeños y revisables.
2. Cada hito debe superar pruebas nativas, compilación Android y comprobaciones manuales en un dispositivo.
3. Cada fase genera un APK de revisión independiente que puede instalarse junto a la instalación anterior; la versión estable no se sustituye hasta validar la nueva.
4. Los mundos guardados necesitan migración/versionado y nunca se borran como solución a un error.
5. El perfil gráfico debe ser escalable: bajo, medio y alto, con límites seguros de memoria, resolución y efectos para Android de gama baja/media.
6. Las referencias visuales incluidas por el usuario en `Minefade imagen/` se usan como guía de composición y distribución. Los recursos finales serán originales de Minefade; las referencias a Minecraft/Mods describen comportamiento y no autorizan copiar sus texturas, modelos o código.
7. No se declara terminado un sistema por tener solo un botón, una textura de prueba o una clase vacía. Debe funcionar desde la interfaz hasta los datos guardados.

## Fase 1 — Estabilidad del motor y jugabilidad esencial

**Propósito:** convertir el prototipo nativo en una base jugable y recuperable.

### Trabajo
- Revisar ciclo de vida Android, JNI, contexto EGL/OpenGL y restauración tras pausa, rotación y pérdida del contexto.
- Hacer robusto el formato de mundos: versión, validación de límites, escritura temporal atómica, recuperación de guardados dañados y migración de versiones antiguas.
- Corregir aparición dentro de bloques, caída al vacío, colisiones, escalones, líquidos y bloqueos de movimiento.
- Rediseñar entrada multitáctil: IDs de puntero independientes, zonas configurables, gesto de cámara separado de minado/colocación, sensibilidad y cancelación segura.
- Implementar minado por mantener pulsado: dureza por bloque, herramienta preferida, tier, eficiencia, progreso, cancelación al cambiar de objetivo y reglas de drops.
- Implementar objetos del mundo: gravedad, colisión, rebote, rotación, agrupación de pilas, tiempo de desaparición, recogida, inventario lleno y persistencia.
- Completar jugador: primera/tercera persona, modelo orientado correctamente, locomoción, salto, caída, agachado, uso/comida y animaciones de mano.
- Completar entidades: orientación, cajas de colisión, navegación básica, estados, daño, retroceso, muerte, drops y animaciones.
- Crear base de combate: salud, daño, invulnerabilidad temporal, ataque cuerpo a cuerpo, alcance, cooldown, retroceso, armadura y muerte/reaparición.
- Incorporar pruebas automatizadas para mundo, colisión, guardado, inventario, minado, drops y entidades.

### Criterios para cerrar la fase
- Un mundo puede crearse, guardarse, cerrarse, reabrirse y continuar sin perder bloques ni inventario.
- Un guardado con posición bloqueada se recupera sin dejar al jugador atrapado.
- Mirar alrededor nunca mina ni coloca bloques accidentalmente.
- Minar requiere mantener pulsado y el tiempo cambia según dureza y herramienta.
- El drop aparece en el mundo, puede bloquearse con el terreno y se recoge respetando la capacidad.
- El jugador y las entidades están de pie, correctamente orientados y con colisiones utilizables.
- Pruebas nativas y APK de revisión superados; prueba manual de movimiento, combate, guardado y regreso al mundo.

### Estado de partida
La rama ya contiene una primera implementación y pruebas para recuperación de spawn, minado mantenido por dureza base, drops físicos básicos, HUD de objetivo y dos trabajos de chunks simultáneos. Esto es un avance parcial: todavía faltan herramientas con tiers, animaciones completas, tercera persona, combate y validación física en dispositivo.

## Fase 2 — Rediseño integral de interfaces y controles

**Propósito:** rehacer el flujo completo de pantallas con menos espacio desperdiciado y diseño móvil consistente.

### Trabajo
- **Inicio:** botones más cortos, avatar ligeramente menor, tarjetas con tratamientos exclusivos, iconografía trabajada, animaciones de entrada/selección y fondo 3D de calidad; evitar botones que sean solo un icono genérico dentro de una caja.
- Quitar completamente las líneas superiores y bandas que solo reservan espacio para el nombre de cada pantalla. No dejar una línea vacía al ocultar el título.
- Navegación por módulos compacta, iconos sin etiquetas cuando el usuario lo pidió y ayudas accesibles sin ocupar líneas permanentes.
- **Avatar:** retícula de cuatro elementos por fila, iconos uniformes de mayor calidad, panel compacto, modelo 3D con iluminación y rotación fluidas, previsualización real de ropa/equipamiento.
- **Ajustes:** módulos compactos, scroll completo, controles flotantes pequeños, información contextual sin tapar activar/desactivar y controles que se puedan mover.
- **Controles:** modo editor que permita seleccionar, mover, redimensionar y ajustar opacidad; guardar perfiles; separación automática de controles cercanos, con reglas para dirección/agachado al estilo del comportamiento de referencia del usuario.
- **HUD:** nueve slots sin números, separación reducida y fondo configurable/transparente; experiencia sobre la barra rápida, nivel a su lado, vida y hambre por encima; notificaciones de recogida solo con icono y cantidad en la esquina inferior derecha.
- **Bloque objetivo:** etiqueta de ancho dinámico según nombre, icono, barra opcional y opacidad de fondo configurable.
- **Inventario:** siete columnas, slots iguales, cuatro espacios de armadura compactos, avatar sin caja grande ni márgenes; comer manteniendo pulsado el alimento y animación de mano visible en primera y tercera persona.
- **Contenedores y fabricación:** cofres, tolvas, hornos y otros paneles con la misma gramática visual; filtros y módulos en una fila, sin bandas innecesarias.
- **Chat:** edición, scroll, emojis, historial y distribución adaptable; sincronización de red en fase 5.
- **Muerte:** composición panorámica, botones cuadrados compactos para reaparecer y volver al menú.
- **Mundos y logros:** creación guiada por módulos coherentes, ajustes de semilla/modo/dificultad/recursos; lista de mundos compacta y logros en cuatro columnas cuando el ancho lo permita.
- Usar escalado por densidad, safe areas y layouts adaptables a móvil/tablet.

### Criterios para cerrar la fase
- Ninguna pantalla tiene una cabecera o franja vacía que consuma la primera línea.
- Las retículas cumplen 4 por fila en avatar/logros cuando el ancho útil lo permite y 7 por fila en inventario.
- Todos los botones responden; iconos tienen estados normal, pulsado, seleccionado y deshabilitado.
- El editor de controles mueve y redimensiona elementos de verdad y conserva la disposición tras reiniciar.
- Opacidad del HUD configurable y persistente.
- Pruebas de interfaz en distintas resoluciones/densidades y uso táctil con múltiples dedos.

## Fase 3 — Generación de mundo y renderizador

**Propósito:** hacer los mundos más ricos, consistentes y rápidos de cargar sin perjudicar teléfonos modestos.

### Trabajo
- Dividir el mundo en chunks con estado y malla independientes; invalidar solo el chunk afectado y sus vecinos necesarios.
- Separar tareas: generación de datos, iluminación/cálculos, construcción de malla y subida GPU; el contexto GL y las llamadas GL permanecen en el hilo correcto.
- Cola de trabajos acotada y priorizada por distancia/dirección de movimiento; cancelar trabajos obsoletos y limitar memoria de chunks lejanos.
- Reutilización de buffers, índices compactos, atlas con mip/filtrado apropiado, instancing cuando convenga y menos draw calls.
- Frustum culling, occlusion culling conservador, LOD, distancia de render configurable y presupuesto dinámico de resolución/FPS.
- Corregir caras invertidas, normales, z-fighting, transparencia, agua y entidades fuera de orientación.
- Terreno determinista con capas de ruido, pendientes, costas, ríos, alturas, cuevas 3D, cavernas conectadas, minerales por profundidad y distribución, vegetación y biomas.
- Generador de estructuras con reglas de ubicación, separación, semilla y validación de terreno: aldeas/campamentos, ruinas, cuevas especiales y estructuras de dimensiones cuando existan.
- Sistema top-down del minimapa basado en datos reales del mundo (no colores aleatorios), con zoom, rotación, marcadores y posición/tamaño/opacidad configurables.
- Agua con superficie, transparencia, animación y comportamiento de flujo gradual; lluvia/tormentas con partículas, sonido y efectos de superficie.
- Iluminación por niveles y sombras escalables; fallback de bajo coste sin sombras dinámicas; ciclo día/noche coherente.
- Añadir métricas de tiempo de generación, malla, subida GPU, memoria por chunk y FPS para medir en dispositivos reales.

### Criterios para cerrar la fase
- Cruzar límites de chunks no causa pausas grandes ni huecos persistentes.
- Romper/colocar un bloque actualiza solo geometría afectada y vecinos necesarios.
- Minimapa es una vista superior fiel y actualizada.
- Biomas/cuevas/estructuras son reproducibles con la misma semilla y no generan apariciones bloqueadas.
- Perfiles bajo/medio/alto sostienen un objetivo estable medido en dispositivo; se documenta FPS, memoria y tiempos de carga.
- Render sin pantallas blancas/negras, geometría de entidades invertida ni fugas de recursos al reanudar.

## Fase 4 — Catálogo, sistemas, cobre y progresión

**Propósito:** transformar el catálogo de bloques en contenido realmente jugable y ampliar la progresión.

### Trabajo
- Registro de datos unificado para bloques, objetos, herramientas, armaduras, recetas, entidades, sonidos, modelos, estados y drops.
- Auditar los bloques ya incorporados: modelo/shape, colisión, textura superior/lateral/inferior, icono de inventario, dureza, herramienta, drops, sonido, luz, transparencia e interacción.
- Mejorar atlas e iconos de minerales; evitar que palos, semillas, herramientas y recursos parezcan cuadrados genéricos.
- Incorporar contenido de cobre completo: mena y variantes profundas que correspondan al catálogo, cobre en bruto/lingote, bloques y sus estados de oxidación, encerado/raspado, decoraciones, antorchas, faroles, cadenas y barrotes.
- Equipo de cobre: pico, hacha, pala, azada, espada, casco, peto, pantalones y botas, con atributos, durabilidad, velocidades, recetas, iconos, modelos en mano y en avatar.
- Cofre de cobre con estados de oxidación/encerado y persistencia de inventario.
- Gólem de cobre invocable: recoger desde el cofre de cobre, buscar cofres válidos, clasificar por tipo, priorizar rutas, manejar cofres llenos, dormir/estatúa/oxidación donde corresponda y evitar duplicación/pérdida.
- Estanterías y otros bloques contenedores con almacenamiento, interacción y representación de los objetos visibles.
- Añadir contenido moderno verificable en paquetes de trabajo, no como IDs vacíos: Copper Age, Mounts of Mayhem, Tiny Takeover, Chaos Cubed y Wilderness Bound; incluir bloques, entidades, biomas, recetas, sonidos y estructuras que sean compatibles con la arquitectura y el alcance del juego.
- Equilibrar progresión: piedra → hierro → cobre como tier según atributos definidos → oro/diamante y materiales avanzados, sin contradicciones en drops/recetas.
- Añadir pruebas de recetas, herramientas, estados de oxidación, guardado de cofres, selección automática del gólem y migración del catálogo.

### Criterios para cerrar la fase
- Cada entrada nueva puede obtenerse, almacenarse, mostrarse, usarse y guardarse.
- Los bloques no son solo texturas: tienen geometría, colisión, dureza, drop, sonido y comportamiento.
- El equipo de cobre tiene atributos y recetas equilibrados y el gólem organiza objetos sin duplicarlos ni perderlos.
- La progresión se puede completar en un mundo nuevo sin comandos ni inventario de prueba.

## Fase 5 — Multijugador, integración y lanzamiento

**Propósito:** integrar todas las fases y publicar una versión instalable que haya pasado pruebas.

### Trabajo
- Descubrimiento automático LAN de Android mediante NSD; partidas locales visibles en módulo LOCAL sin códigos de invitación.
- Protocolo de sesión con versión, capacidades, semilla y configuración; conexión, desconexión, reconexión y tratamiento de errores.
- Estado autoritativo para cambios de bloques, inventario, combate, entidades, drops y cofres; evitar duplicación, pérdida y desincronización.
- Chat compartido, lista de jugadores y estado de conexión; separar LOCAL de servidores externos.
- Límites de seguridad, validación de mensajes, timeouts y límites de paquetes.
- Perfilado completo con métricas antes/después y presupuestos por dispositivo.
- Pruebas de mundo largo, viajes entre chunks, inventarios llenos, cierre forzado, reconexión LAN, guardados grandes, pérdida de contexto GL y consumo de memoria/batería.
- Matriz de compatibilidad Android y ARM; revisar permisos, almacenamiento, firma, ABI, tamaño y actualización.
- Compilar APK release separado del debug, firmar con clave de lanzamiento que controle el propietario, generar checksum y conservar código fuente/release notes.
- Prueba final de instalación limpia y actualización sobre versión anterior, sin borrar mundos.

### Criterios para cerrar la fase
- Dos o más dispositivos pueden descubrirse y jugar en LAN de manera reproducible.
- Bloques, drops, combate y contenedores se sincronizan sin duplicación.
- Todos los sistemas de las fases 1–4 pasan regresión en singleplayer y multiplayer.
- APK release firmado, instalable y probado manualmente; código fuente y notas de versión archivados.

## Orden de ejecución y entregas

| Hito | Resultado obligatorio |
|---|---|
| Fase 1 | APK jugable: motor, controles, minado, drops, jugador y entidades |
| Fase 2 | APK UI: inicio, avatar, ajustes, inventario, HUD y pantallas |
| Fase 3 | APK mundo: terreno, chunks, render, clima y minimapa |
| Fase 4 | APK contenido: catálogo, cobre, gólem y progresión |
| Fase 5 | APK candidata y Minefade 1.0 final |

**La fase 1 está parcialmente iniciada** en esta rama. No se considera cerrada hasta completar los elementos restantes y superar pruebas en dispositivo. No se salta a una fase posterior por haber generado un APK si los criterios de cierre de la fase actual fallan.
