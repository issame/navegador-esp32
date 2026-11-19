# Navegador ESP32

Reloj e indicaciones de Google Maps en una placa **ESP32-C3 con pantalla redonda de 1,28"
(GC9A01, 240×240)**, controlada desde una app Android propia por Bluetooth LE.

- **`firmware/`** — firmware de la placa (PlatformIO + LVGL 9).
- **`app-android/`** — app Android *Navegador ESP32* (Kotlin).

## Cómo funciona

1. La app lee la notificación fija que Google Maps muestra mientras navegas
   (distancia a la maniobra, indicación, tiempo y distancia restantes, hora de llegada
   y la flecha de la maniobra).
2. La envía por BLE a la placa, junto con la fecha y la hora.
3. La placa muestra el reloj y, en cuanto empieza una ruta, cambia sola a la pantalla
   de navegación. Al terminar la ruta (o si se pierde la conexión) vuelve al reloj.

### Protocolo BLE

Se usa el protocolo de la librería [ChronosESP32](https://github.com/fbiego/chronos-esp32)
(servicio tipo Nordic UART `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, se escribe en
`6e400002-…`). Paquetes: `AB len_hi len_lo FE|FF comando datos…`, con `len` = longitud − 3.
Si no caben en una escritura se trocean en 20 bytes + trozos de `índice + 19 bytes`.

| Comando | Contenido |
|---|---|
| `0x93` | Hora: año (2 bytes), mes, día, hora, minuto, segundo |
| `0xEF` | Navegación: estado (`00` inactiva / `80` activa), hay icono, CRC del icono y 6 textos terminados en 0 (distancia a la maniobra, duración, distancia, llegada, indicación, velocidad) |
| `0xEE` | Flecha 48×48 a 1 bit, en 3 trozos de 96 bytes |

Los tests de la app (`app-android/app/src/test`) incluyen una copia en Kotlin del código
de recepción de la librería para comprobar los paquetes byte a byte.

## Pantallas de la placa

- **Arranque**: icono de un mapa plegado con un marcador (2 s).
- **Reloj**: hora, día de la semana y fecha en castellano. Icono de Bluetooth gris
  (sin conexión) o azul (móvil conectado). Hasta recibir la hora muestra `--:--`.
- **Navegación**: llegada, tiempo y distancia restantes arriba; flecha en el centro;
  distancia a la maniobra en grande; indicación abajo (máx. 2 líneas).

Las fuentes (Montserrat) incluyen los caracteres de castellano y catalán
(`á é í ó ú à è ò ï ü ñ ç ŀ · ¿ ¡ ’ € …`).

## Firmware

Requiere [PlatformIO](https://platformio.org/).

```bash
cd firmware
pio run -t upload
```

Si el ordenador no detecta la placa: usa un cable USB-C de datos y entra en modo de
descarga (mantén **BOOT**, pulsa y suelta **RST**, suelta **BOOT**). Después de grabar,
pulsa **RST** sin tocar BOOT para arrancar el firmware.

La placa se anuncia por Bluetooth como **"Navegador ESP32"**.

Las fuentes de `firmware/src/fuentes` se generaron con
[lv_font_conv](https://github.com/lvgl/lv_font_conv) 1.5.2 y se ajustaron a mano para
LVGL 9 (la comprobación `LV_VERSION_CHECK(8, 0, 0)` del archivo generado es falsa en LVGL 9).

## App Android

Requiere el SDK de Android (API 35) y JDK 17 o superior (sirve el de Android Studio).

```bash
cd app-android
./gradlew testDebugUnitTest assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Para la versión **release** (`./gradlew assembleRelease`) hace falta una clave de firma en
`app-android/firma/` (no se sube a git): un almacén `navegador.jks` y un archivo
`firma.properties` con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`. Guarda esa
clave: las actualizaciones de la app deben firmarse siempre con la misma.

### Archivos de la app

| Archivo | Qué hace |
|---|---|
| `MapsListener.kt` | Lee la notificación de navegación de Google Maps |
| `MapsParser.kt` | Saca distancia, indicación, tiempo, distancia restante y llegada de sus textos |
| `IconConverter.kt`, `IconBits.kt` | Convierten la flecha de Maps a 48×48 a 1 bit |
| `Protocol.kt` | Construye y trocea los paquetes BLE |
| `BleService.kt` | Mantiene la conexión con la placa y envía hora y navegación |
| `Hub.kt` | Estado compartido entre las partes de la app y registro |
| `MainActivity.kt`, `LogActivity.kt` | Pantallas de la app |
| `Prefs.kt`, `Perms.kt` | Placa emparejada y permisos |

### Archivos del firmware

| Archivo | Qué hace |
|---|---|
| `src/main.cpp` | Arranque, conexión BLE y bucle principal |
| `src/ui.cpp` | Pantallas de reloj y navegación (LVGL) |
| `src/arranque.hpp` | Icono del mapa de la pantalla de arranque |
| `src/pantalla.hpp` | Pines y configuración del panel GC9A01 |
| `src/fuentes/` | Fuentes con caracteres de castellano y catalán |

Al abrirla por primera vez:

1. Concede los permisos de Bluetooth.
2. Activa *Navegador ESP32* en **Acceso a notificaciones** (necesario para leer Maps).
3. Pulsa **Buscar pantalla** y elige la placa.

**Probar navegación** envía una ruta de prueba con acentos y una flecha. **Ver registro**
muestra lo que llega de Maps tal cual y lo que se envía a la placa; sirve para ajustar la
lectura si una versión de Maps cambia el formato de la notificación.

## Créditos y licencias de terceros

- Configuración de pantalla (`firmware/src/pantalla.hpp`) y `firmware/include/lv_conf.h`
  basados en [esp32-c3-mini](https://github.com/fbiego/esp32-c3-mini) de Felix Biego —
  MIT, ver `licencias/MIT-esp32-c3-mini.txt`.
- Librería [ChronosESP32](https://github.com/fbiego/chronos-esp32) de Felix Biego
  (dependencia del firmware y base del simulador de los tests) — MIT, ver
  `licencias/MIT-ChronosESP32.txt`.
- Fuente [Montserrat](https://github.com/JulietaUla/Montserrat) — SIL Open Font License 1.1,
  ver `licencias/OFL-Montserrat.txt`.
- [LVGL](https://lvgl.io), [LovyanGFX](https://github.com/lovyan03/LovyanGFX) y
  [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino) se descargan como dependencias.
