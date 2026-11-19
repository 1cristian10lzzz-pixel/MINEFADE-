# Fase 1 — Fundación y estabilidad del streaming

Estado de esta entrega: **implementada en código; pendiente de resultado de compilación CI y prueba física Android**.

## Cambio de esta fase

La generación asíncrona ahora etiqueta cada grupo de solicitudes con una época de generación. Al moverse el jugador a otra zona, la nueva petición reemplaza la anterior. Las tareas antiguas que ya estaban ejecutándose no se pueden detener de forma portable, pero sus resultados se descartan al completarse y ya no pueden añadir chunks fuera de la zona actualmente solicitada.

También se corrige un caso sutil: si una petición nueva necesita un chunk que todavía está siendo generado por un worker antiguo, el worker antiguo ya no cuenta como trabajo válido de la solicitud actual. El chunk se vuelve a poner en la cola con la época nueva. Un resultado obsoleto tampoco elimina la marca de cola de la petición nueva.

Los fallos de tareas antiguas no desactivan el pool de workers ni fuerzan generación síncrona de terreno que ya no se necesita. Los fallos de una tarea actual conservan el fallback síncrono existente. PendingChunkCount refleja ahora la cola actual y los workers de la época actual, no el trabajo obsoleto pendiente de terminar.

## Límites preservados

- No cambia el tamaño de chunk, la generación determinista ni la seed.
- No cambia IDs de bloques ni los formatos MFVOX001/002/003.
- No se borra ni se expulsa ningún chunk ya almacenado.
- El límite de dos workers y el radio máximo de ocho chunks siguen vigentes.
- No se realizan llamadas OpenGL fuera del hilo que ya administra el contexto.
- La reutilización de capacidad de VBO añadida anteriormente se conserva.

## Prueba añadida

WorldSmokeTests.cpp cubre tres casos: reemplazo por una zona remota, reenvío del mismo chunk mientras sigue en vuelo una solicitud antigua y el camino de bombeo por frames utilizado durante la partida. Se verifica que:

1. La época nueva es superior a la anterior.
2. La región solicitada contiene un solo chunk objetivo.
3. El resultado de al menos un worker obsoleto se ha descartado.
4. No quedan tareas pendientes de la solicitud actual.
5. Solo se ha insertado el chunk de la región nueva.
6. La región anterior no se ha repoblado accidentalmente.\n7. Una solicitud nueva del mismo identificador de chunk no se pierde detrás de un worker antiguo.\n8. El camino PumpChunkGeneration descarta resultados antiguos y termina la generación de la región actual.

La suite existente sigue comprobando generación determinista, guardado/carga, revisiones de malla y LOD actual. El test previo de reutilización GPU continúa ejecutándose en run-world-tests.sh.

## Lo que esta fase no declara terminado

La construcción de mallas todavía no es un servicio persistente desacoplado del render loop; el upload GPU mantiene la sincronización por chunk. Tampoco se consideran resueltos el mundo blanco, la franja negra ni los artefactos visuales sin una prueba de dispositivo. Esos trabajos pertenecen a la fase 2 y a la validación física final.