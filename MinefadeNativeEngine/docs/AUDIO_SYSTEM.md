# Sistema de banda sonora de Minefade

## Reproducción

Minefade mantiene los efectos cortos actuales en Android SoundPool. La banda sonora utiliza un canal independiente de SoLoud, fijado al commit e82fd32c1f62183922f08c14c814a02b58db1873 y compilado estáticamente con el backend Android OpenSL ES.

El proceso de compilación genera una pista WAV ambiental original de unos 53 segundos, con pads suaves, arpegio y transiciones armónicas. La pista se incluye dentro de los recursos del APK y se copia al almacenamiento privado de la aplicación. SoLoud la reproduce localmente en bucle. No hay radio, streaming, solicitudes de red ni una pista de ruido como sustituto si falta el archivo. Si el backend de audio no está disponible, se conserva el juego y su ruta de efectos de sonido existente.

## Controles

En Ajustes → Audio:
- Música ambiental: activar o desactivar la banda sonora.
- Volumen de música: nivel independiente de los efectos.
- Volumen de efectos, efectos de juego y sonidos de interfaz: mantienen sus ajustes existentes.

La música se pausa y reanuda junto con el ciclo de vida de la superficie nativa. La entrada inicial utiliza un fundido de volumen para evitar un comienzo brusco.

## Dependencia y licencia

SoLoud: https://github.com/jarikomppa/soloud  
Commit fijado: e82fd32c1f62183922f08c14c814a02b58db1873  
Licencia: zlib/libpng. El aviso completo se conserva en third_party/SoLoud-LICENSE.txt.

La banda sonora es generada por el proyecto en el momento de compilar el APK y no requiere Internet durante el juego.
