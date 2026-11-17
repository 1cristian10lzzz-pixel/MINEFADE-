# Minefade 2.0.2 — fases 1 y 2

## Fase 1 — estabilidad, inicio y recuperación del mundo

- Un fallo de inicialización de RmlUi ya no cancela un mundo que el motor GLES y la simulación hayan podido iniciar correctamente.
- El error original del HUD se conserva en el diagnóstico nativo, incluyendo el mensaje del backend de OpenGL ES.
- Si RmlUi no está disponible después de iniciar el motor, Android instala un HUD táctil de recuperación con joystick, salto, agacharse, perspectiva y pausa.
- Los toques que no corresponden a botones del HUD de recuperación se reenvían al hilo GL para girar la cámara y realizar acciones de juego.
- El área de movimiento se calcula con los límites reales del joystick y respeta los controles para zurdos; el resto de la pantalla no queda bloqueado como zona de movimiento.
- El botón de reintento conserva el mundo seleccionado, su ruta de guardado, semilla y opciones, en lugar de iniciar la partida predeterminada.
- El texto largo del diagnóstico se puede desplazar y copiar.
- El guardado del mundo y el ciclo de llamadas JNI permanecen serializados en el hilo GL.

## Fase 2 — navegación e interfaces

- Menú principal más compacto: botones de navegación más cortos y sin eslóganes redundantes sobre el fondo ilustrado.
- Cabeceras interiores reducidas; se elimina la segunda línea descriptiva repetida.
- Ajustes rediseñados en dos paneles: categorías en una barra vertical izquierda y controles desplazables a la derecha.
- Switches, selectores y barras de ajuste más compactos. Cada control ofrece un icono de información que muestra su descripción al tocarlo.
- Navegador de mods: salida, búsqueda e importación en una sola línea; tarjetas en dos columnas; desaparece la tarjeta de ejemplo.
- Avatar: se recupera espacio del editor quitando rótulos de vista previa redundantes y compactando pestañas y opciones.
- Multijugador LAN: resultados en el área principal y acciones pequeñas de crear/buscar abajo a la derecha; retirada la tarjeta que anunciaba que los servidores públicos no estaban disponibles.
- Logros: diez objetivos visibles y cinco tarjetas por fila, con indicadores de progreso basados en las estadísticas existentes.
- Acciones de mundo con icono de ajustes en vez de los tres puntos.

## Comprobaciones automatizadas

El workflow ejecuta un test de regresión de inicio Android que comprueba el camino de HUD de emergencia, el reintento del mundo, la zona exacta del joystick y los contratos principales de la fase 2. Además ejecuta los tests nativos, catálogo, audio, mods y perfiles shader, compila Android y verifica firma, paquete, atlas y bibliotecas ARM32/ARM64.

## Límites y validación física

- Esta actualización no puede confirmar el resultado del primer arranque en el Mali-G57 físico del usuario; esa comprobación debe realizarse instalando el APK en el teléfono.
- La reconstrucción visual completa de todas las piezas del avatar y las miniaturas personalizadas de mods no forma parte de esta corrección de estabilidad.
- La interfaz de emergencia es una ruta de recuperación cuando RmlUi falla; no significa que se haya reparado la causa interna del fallo de RmlUi en todos los controladores.
