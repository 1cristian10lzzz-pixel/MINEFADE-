# Minefade Review7 — auditoría de las mejoras 1–100

Esta auditoría diferencia lo que ya existía en Review6, lo nuevo de Review7 y lo que todavía no debe darse por terminado. No se marca una función como completa solo porque exista un botón o una preferencia: tiene que estar conectada al comportamiento real.

## Cambios funcionales añadidos en Review7

- **#23 — Errores útiles y recuperación:** el inicio comunica la fase que falló y la causa nativa devuelta por C++, en vez de afirmar que OpenGL ES no es compatible.
- **#135 (extra) — Diagnóstico copiable:** la ventana de error tiene una acción para copiar el informe, incluyendo OpenGL, GLSL, GPU, fabricante y límite máximo de textura.
- **Recuperación de memoria:** si el diagnóstico apunta a buffers, mallas o `GL_OUT_OF_MEMORY`, Minefade realiza un único segundo intento con calidad mínima, menor distancia de chunks, ondas desactivadas y sombras desactivadas. Solo guarda ese perfil reducido si el segundo intento funciona.
- **Shader de reserva:** si el shader de mundo completo no compila, no enlaza o pierde uniformes requeridos, se intenta un shader ES 3.0 reducido que conserva el dibujo texturizado de bloques.
- **#73 — Sensibilidad independiente:** ahora hay ajustes horizontales y verticales separados, conservando el ajuste anterior como valor heredado.
- **#74 — Invertir eje vertical:** se conserva y sigue conectado al giro de cámara nativo.
- **#81 — Mano izquierda:** el área real de movimiento se desplaza a la derecha y el botón de salto se refleja a la izquierda; el HUD se reconstruye con esa distribución.
- **#86 — Zona muerta de joystick:** selector persistente del 5–30%; el motor ignora desviaciones pequeñas alrededor del centro y normaliza progresivamente el resto del recorrido.
- **#56 (parcial) — Visibilidad del HUD:** se puede ocultar la información superior (FPS/chunks/día) sin ocultar la barra rápida ni los controles.
- **#100 (parcial) — Preferencias de controles persistentes:** los controles se guardan automáticamente y se añadió restauración de sus valores predeterminados. Todavía no hay perfiles exportables/importables.

## Funciones que ya estaban presentes en Review6 y se conservan

- **Menús y navegación:** #1, #2, #3, #6, #7, #8, #9, #13 (adaptación parcial), #14, #16, #17 (iconografía propia parcial), #18, #19, #20, #25, #27, #29, #38 (animación parcial), #40, #42 (etiquetas en controles principales), #47, #49 y #50 (manejo táctil; todavía sin mando).
- **HUD y controles:** #53 (escala global del HUD, no escala por elemento), #54, #55, #58, #59, #60, #62 (zona de gesto separada parcialmente de su dibujo), #63, #64 (lógica básica probada; falta validar direcciones en teléfono), #66 y #80.
- **Accesibilidad:** #74, #78 (vibración de menú, no vibración completa de juego), #91, #92, #94 y #97 (inmersión horizontal; falta validación en recortes concretos).
- **Persistencia:** #100 (preferencias individuales guardadas, sin gestor completo de perfiles).

## Pendientes del bloque 1–100

No se consideran terminados todavía: #4, #5, #10, #11, #12, #15, #21, #22, #24, #26 (falta acceso a ajustes desde pausa), #28 (la carga existe, pero falta progreso real medido), #30, #31–#37 (gestión completa de varios mundos, búsqueda, orden, miniaturas, metadatos y acciones), #39, #41, #43–#46, #48, #51–#52 (editor de posición del HUD y distribución independiente por orientación), #56 (ocultar todos los indicadores, no solo la información superior), #57, #61, #65, #67–#72 (cruceta seleccionable, reubicar salto, agacharse, correr e interacción contextual), #75–#77, #79, #82–#85, #87–#90 (captura sin controles, HUD mínimo/ampliado, lado de mano completo para inventario y mando/remapeo), #93, #95–#96, #98–#99 y la parte de perfiles de #100.

## Pruebas y límites conocidos

- La generación del mundo con la semilla inicial y la búsqueda de una posición segura pasan la prueba nativa de escritorio disponible.
- Se añadió una prueba para comprobar que un archivo de mundo truncado no modifica parcialmente los chunks, la posición ni el inventario que ya estaban cargados.
- La prueba automatizada no sustituye una prueba visual/táctil en un Android Mali-G57.
- El shader de reserva reduce efectos avanzados, pero mantiene el atlas y el renderizado básico. No representa una solución a una GPU sin GLES 3.0; la captura ya demuestra que ese no es el caso.
- No se migran ni se borran mundos de instalaciones anteriores. La APK Review7 usa un identificador separado para no forzar la desinstalación de Review6 ni sobrescribir sus datos.
