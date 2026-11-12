# Minefade 0.5.2 — corrección de arranque, interfaz y audio

## Corrección prioritaria
- La vista OpenGL se crea después de onResume() de Android. La nueva versión activa explícitamente GLSurfaceView al crearla si la Activity ya está reanudada; sin esto el hilo EGL podía permanecer en pausa, impedir onSurfaceCreated() y dejar la entrada al mundo bloqueada.
- La primera carga genera el chunk de aparición síncronamente y los chunks vecinos quedan en streaming asíncrono.
- Las entidades ambientales iniciales se colocan dentro del chunk de aparición.

## Interfaz
- Página de mundos con tarjeta principal y acción clara para entrar.
- Ajustes con volumen de efectos en tiempo real.
- HUD con mayor contraste y guía visual no interceptora para el área de movimiento.
- Menú de pausa con «Continuar» y «Guardar y salir».

## Audio
- Efectos locales para recogida, salto y pasos, además de rotura, colocación y clic.
- SoundPool no intenta reproducir muestras hasta que Android confirma que están cargadas.
- Volumen maestro actualizable en tiempo real.
- Pasos limitados por intervalo y condicionados a que el jugador esté caminando en el suelo.

## Límites de esta revisión
Esta versión mejora el motor C++20/OpenGL ES/RmlUi existente. No declara completados el multijugador LAN, inventario extendido, IA avanzada, sombras dinámicas ni migración a Rust. La compilación automatizada valida pruebas nativas y empaquetado Android; la prueba final de entrada a mundos y controles debe hacerse en dispositivo real.
