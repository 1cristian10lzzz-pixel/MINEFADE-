# Minefade Shader Profile v1

El formato `*.mfshader` v1 es un perfil de ajustes para el shader OpenGL ES integrado de Minefade. No ejecuta código, no carga bibliotecas ni scripts y no reemplaza directamente los programas nativos.

Ejemplo:

```ini
format=1
id=minefade_vibrant
name=Minefade Vibrante
version=1.0
water.waves=true
lava.animation=true
leaves.wind=true
leaves.strength=0.04
```

Claves permitidas: `water.waves`, `lava.animation`, `leaves.wind` y `leaves.strength` (0.00–0.12). El manifiesto requiere `format`, `id`, `name` y `version`. Los archivos están limitados a 64 KiB y 128 líneas.

En v1, el perfil modifica parámetros y efectos del shader integrado: ondas de agua, animación de lava y viento de hojas. Los paquetes que sustituyan programas GLSL completos, materiales y texturas quedan fuera de v1; se incorporarán cuando exista validación de uniforms, versión GLES y recuperación segura por GPU.
