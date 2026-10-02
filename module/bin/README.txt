Coloca aquí el binario "rclone" para arm64 (o la arquitectura de tu dispositivo)
antes de empaquetar el módulo. No se versiona en git por tamaño.

Descarga oficial: https://rclone.org/downloads/

También va acá "fusermount3" (binario estático), que Android no trae de
fábrica y rclone lo necesita para montar FUSE aunque se corra como root.
El CI lo compila automáticamente desde el código oficial de libfuse
(https://github.com/libfuse/libfuse) usando un cross-compiler musl para
aarch64, en vez de depender de un binario de terceros ya compilado.
