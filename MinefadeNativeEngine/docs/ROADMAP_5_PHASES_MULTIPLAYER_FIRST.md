# Minefade 1.0 — plan de cinco fases (multijugador primero)

**Prioridad acordada:** comenzar por un multijugador LAN real y simple. Las cinco fases se ejecutan en orden; un sistema no se considera terminado solo por mostrar una pantalla o un botón.

## Fase 1 — Multijugador cooperativo LAN

Objetivo: dos o más teléfonos Android en la misma Wi-Fi pueden crear, descubrir y jugar en una partida local.

- Crear una sala desde el teléfono anfitrión y anunciarla con Android Network Service Discovery (NSD/mDNS).
- Buscar salas automáticamente sin códigos de acceso y resolver IP/puerto con NSD.
- Unirse mediante TCP con límite inicial de ocho jugadores.
- Sincronizar posición y orientación de los jugadores; representar los avatares remotos con el modelo voxel del juego.
- Usar una semilla compartida para el terreno inicial.
- Replicar eventos de rotura y colocación de bloques entre clientes.
- Manejar salida, pérdida de conexión y cierre de sala sin bloquear el juego.
- Añadir después de la primera conexión: lista de jugadores, chat, sincronización de drops/inventarios, guardado autoritativo del anfitrión, reconexión y validación de protocolo.

**Estado de la primera entrega 0.6.0:** base LAN implementada en código: NSD, servidor TCP, búsqueda, unión, sincronización de avatares y eventos de edición de bloques. No se ha verificado todavía entre dos teléfonos físicos. Persistencia compartida del mundo, chat, drops/inventario y reconexión todavía están pendientes; por eso la Fase 1 sigue ABIERTA.

## Fase 2 — Todas las interfaces y controles

- Rediseñar inicio, mundos, avatar, ajustes, inventario, cofres, fabricación, chat, muerte y HUD.
- Unificar estilo, iconografía, escala, animaciones y estados de botones.
- Editor táctil real para mover, redimensionar y guardar controles.
- Menús LAN con sala, jugadores conectados, estado de host y chat.
- Mantener interfaces compactas, sin cabeceras vacías ni paneles recortados.

## Fase 3 — Migración del núcleo a Rust y jugabilidad

- Migrar progresivamente mundo, jugador, físicas, entidades, inventario, combate, objetos y guardado a Rust.
- Mantener JNI y el adaptador C++/RmlUi solo donde la compatibilidad del renderizador lo requiera.
- Medir rendimiento y conservar compatibilidad con los mundos existentes.
- No sustituir el motor completo en un único cambio sin regresiones y pruebas de datos.

## Fase 4 — Mundo, renderizado y contenido

- Chunks independientes, generación por prioridad, mallas parciales y presupuestos GPU.
- Biomas, cuevas, minerales, estructuras y generación determinista.
- Agua, lluvia, clima, iluminación, sombras escalables, partículas y minimapa real.
- Texturas, modelos, animaciones, efectos de sonido y música original.
- Perfiles gráficos adaptados a Android de gama baja, media y alta.

## Fase 5 — Estabilidad, integración y lanzamiento

- Pruebas en dos o más teléfonos reales, reconexión, pérdida de Wi-Fi y latencia.
- Seguridad del protocolo LAN, límites de paquetes, validación de datos y control del anfitrión.
- Regresión de singleplayer, compatibilidad de guardados y rendimiento prolongado.
- APK release firmado, instalación limpia y actualización sin borrar mundos.
- Documentación y cierre verificable de cada sistema.

## Reglas de aceptación

1. No declarar el multijugador completo hasta probar sincronización y persistencia entre dispositivos reales.
2. No presentar «Servidores públicos» como funcionales sin infraestructura, autenticación y mantenimiento.
3. Mantener el modo individual independiente de las salas LAN.
4. No sobrescribir los mundos individuales con datos de una sesión multijugador.
5. Generar APKs de revisión aislados hasta validar la versión estable.
