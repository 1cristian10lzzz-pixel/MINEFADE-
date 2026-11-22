# Minefade 2.0.5 — Plan de renovación en tres fases

## Fase 1 — Estabilidad del núcleo
- Geometría exterior de escaleras, paneles, vallas y andamios sin caras coplanares internas.
- Entrada multitáctil identificada por pointer ID y cancelación de todos los dedos al recibir ACTION_CANCEL.
- Preferencias coherentes en el HUD RmlUi de recuperación.
- Guardar y salir solo abandona la partida cuando el guardado termina correctamente; después vuelve al menú de mundos.
- Ajustes básicos de calidad, distancia de renderizado, agua, sombras de contacto y controles.
- Tres vistas: primera persona, tercera persona trasera y tercera persona frontal.
- Semilla aleatoria cuando el campo se deja vacío y validación explícita de semillas inválidas.

## Fase 2 — Interfaces y personalización
- Creador de avatar por categorías (cuerpo, rostro, piel, pelo, ropa, pantalones, calzado, accesorios y armadura), con modelo 3D y animaciones.
- Navegador de mundos compacto con búsqueda.
- Creación de mundos separada en Juego, Mundo y Más, con modos, dificultad, trucos, semilla, estructuras y reglas.
- Multijugador: crear partida abre configuración previa a la creación.
- Ajustes agrupados en Vídeo/Gráficos, Shaders, Audio, Controles, Idioma y Accesibilidad; subpáginas y descripciones inspiradas en Iris y Minecraft.
- Localización real, no solo nombres de idioma; revisión de español de España y variantes regionales.

## Fase 3 — Mundo y renderizado
- Revisar agua, transparencia, alpha-cutout y movimiento de hojas mediante pases separados y pruebas de profundidad.
- Modelos propios de flores, vegetación y entidades; pipeline de variaciones de textura/modelo y animaciones.
- Auditoría de estructuras y generación procedural con semillas reproducibles.
- Mejorar streaming de chunks, culling, LOD, buffers GPU, límites de memoria y tiempos de frame.
- Compatibilidad de mods y shaders con validación de recursos.
- Sistema persistente de cadáver y recuperación de objetos al morir, con reglas para multijugador.

## Criterio de aceptación
Cada fase debe pasar las pruebas automatizadas y una comprobación en un dispositivo Android físico. Una compilación CI correcta demuestra que el APK se construyó y firmó; no sustituye la prueba visual/táctil en el teléfono.
