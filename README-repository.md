# Minefade Native Engine 0.5.0

Proyecto Android nativo de Minefade, basado en C++20, OpenGL ES 3 y RmlUi 6.3. Esta rama es una reconstrucción independiente; no reemplaza ni conserva automáticamente todas las funciones del APK Alpha anterior.

El flujo de GitHub Actions compilará el APK debug después de que el código fuente esté completo. Las pruebas de escritorio verifican el mundo voxel y el catálogo de bloques, pero no sustituyen una instalación y prueba en un teléfono.

## Estado conocido

- Catálogo de 284 bloques y atlas procedimental de 576x486.
- Mundo voxel con chunks, raycast, guardado/carga e inventario básico.
- Host Android JNI y renderizador OpenGL ES 3.
- Integración RmlUi 6.3 con backend OpenGL ES propio.
- Pendiente: confirmar build Android con NDK real, instalar y probar APK, recuperar/integrar las funciones completas del APK Alpha, LAN, clima avanzado, modelos y sombras dinámicas.

Las texturas del atlas se generan durante la CI a partir del catálogo fuente. No se incluyen aquí archivos binarios de textura ni los APK de referencia.
