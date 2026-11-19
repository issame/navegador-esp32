# Navegador ESP32

Reloj e indicaciones de navegación (Google Maps y OsmAnd) en una placa
**ESP32-C3 con pantalla redonda de 1,28" (GC9A01, 240×240)**, controlada desde una app
Android propia por Bluetooth LE.

- **`firmware/`**: firmware de la placa (PlatformIO + LVGL 9).
- **`app-android/`**: app Android *Navegador ESP32* (Kotlin).

## Funciones

### Pantalla (placa ESP32-C3)

- **Pantalla de arranque** con un icono de mapa plegado y un marcador (2 s).
- **Reloj** con la hora en grande, el día de la semana y la fecha en castellano
  ("miércoles", "19 de noviembre"). Muestra `--:--` y "Conecta el móvil" hasta recibir la hora.
- **Icono de Bluetooth** en el reloj: gris sin conexión, azul con el móvil conectado.
- **Navegación automática**: al empezar una ruta cambia sola a la pantalla de navegación y,
  al terminar (o si se pierde la conexión), vuelve al reloj.
- **Pantalla de navegación**:
  - arriba, hora de llegada y tiempo y distancia restantes ("Llegada 14:51", "19 min · 8,4 km");
  - en el centro, la flecha de la maniobra tal como la dibuja la app de navegación;
  - debajo, la distancia a la maniobra en grande;
  - abajo, la indicación (como mucho 2 líneas, dentro de la parte visible del círculo).
- **Arco de progreso** en el borde que se va completando al acercarse a la maniobra.
- **Aviso de maniobra inminente**: a 100 m o menos, el arco se vuelve naranja y parpadea,
  y la distancia se pinta en naranja.
- **Caracteres de castellano y catalán** (`á é í ó ú à è ò ï ü ñ ç ŀ · ¿ ¡ ’ € …`) con la
  fuente Montserrat.
- **Actualización del firmware por Bluetooth**, sin cables, desde la app. Si se corta a
  medias o el archivo llega dañado, la placa sigue con el firmware anterior.

### App Android

- **Búsqueda y emparejado** de la placa (solo muestra placas con este firmware). La recuerda
  y se **reconecta sola** cuando la pierde.
- **Envío de la hora** al conectar, cada hora y cuando cambia la hora del móvil (por ejemplo,
  en el cambio de horario).
- **Lectura de la navegación** de la notificación de **Google Maps**, **OsmAnd+** (también
  OsmAnd~ de F-Droid) y **OsmAnd**: distancia a la maniobra, indicación, tiempo y distancia restantes, hora de
  llegada y flecha de la maniobra.
- **Envío eficiente**: solo manda datos cuando cambian, y la flecha solo cuando es otra.
- **Funciona en segundo plano** con una notificación fija que indica el estado de la conexión.
- **Ahorro de batería**: avisa si Android puede cerrar la app y lleva al ajuste para evitarlo.
- **Actualizar firmware**: elige un `.bin` en el móvil y se envía a la placa por Bluetooth,
  con el progreso en la app y en la pantalla.
- **Probar navegación**: envía una ruta de prueba con acentos y una flecha, para comprobar
  la pantalla sin salir a la calle.
- **Registro**: muestra lo que llega de la app de navegación tal cual y lo que se envía a
  la placa. Se puede compartir, para ajustar la lectura si una app cambia el formato.
- **Toda la interfaz en castellano.**

## Cómo funciona

1. La app lee la notificación fija que la app de navegación muestra mientras guía una ruta.
2. La envía por Bluetooth LE a la placa, junto con la fecha y la hora.
3. La placa muestra el reloj y, en cuanto empieza una ruta, cambia a la pantalla de navegación.

### Protocolo BLE de navegación

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

### Protocolo BLE de actualización del firmware

Servicio propio `c8659210-af91-4ad3-a995-a58d6fd26145`, independiente del anterior:

| Característica | Uso |
|---|---|
| Control `c8659211-…` | Escribir `01 + tamaño (4 bytes LE) + MD5 (16 bytes)` para empezar, `02` para terminar, `03` para cancelar. Avisa con 3 bytes: estado, porcentaje y código de error. |
| Datos `c8659212-…` | El archivo `.bin`, por orden, en trozos |

El firmware nuevo se escribe en la partición libre (la tabla tiene dos huecos de aplicación)
y solo se activa si llega completo y su MD5 coincide.

## Firmware

Requiere [PlatformIO](https://platformio.org/).

```bash
cd firmware
pio run -t upload
```

El archivo para actualizar desde el móvil queda en `firmware/.pio/build/esp32c3/firmware.bin`.

**La primera vez hay que grabarlo por USB**, porque cambia la tabla de particiones (añade el
segundo hueco que necesita la actualización por Bluetooth). Si el ordenador no detecta la
placa: usa un cable USB-C de datos y entra en modo de descarga (mantén **BOOT**, pulsa y
suelta **RST**, suelta **BOOT**). Después de grabar, pulsa **RST** sin tocar BOOT.

La placa se anuncia por Bluetooth como **"Navegador ESP32"**.

Las fuentes de `firmware/src/fuentes` se generaron con
[lv_font_conv](https://github.com/lvgl/lv_font_conv) 1.5.2 y se ajustaron a mano para
LVGL 9 (la comprobación `LV_VERSION_CHECK(8, 0, 0)` del archivo generado es falsa en LVGL 9).

### Archivos del firmware

| Archivo | Qué hace |
|---|---|
| `src/main.cpp` | Arranque, conexión BLE, progreso de la maniobra y bucle principal |
| `src/ui.cpp` | Pantallas de reloj, navegación (con arco y aviso) y actualización (LVGL) |
| `src/ota.cpp` | Actualización del firmware por Bluetooth |
| `src/distancia.h` | Convierte "250 m", "1,2 km", "0.3 mi"… a metros |
| `src/arranque.hpp` | Icono del mapa de la pantalla de arranque |
| `src/pantalla.hpp` | Pines y configuración del panel GC9A01 |
| `src/fuentes/` | Fuentes con caracteres de castellano y catalán |
| `partitions.csv` | Tabla de particiones con dos huecos de aplicación |

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

Al abrirla por primera vez:

1. Concede los permisos de Bluetooth.
2. Activa *Navegador ESP32* en **Acceso a notificaciones** (necesario para leer la navegación).
3. Pulsa **Desactivar** en *Ahorro de batería*, para que Android no corte la conexión.
4. Pulsa **Buscar pantalla** y elige la placa.

### Archivos de la app

| Archivo | Qué hace |
|---|---|
| `NavigationListener.kt` | Lee la notificación de navegación de Maps u OsmAnd |
| `NotificationParser.kt` | Saca distancia, indicación, tiempo, distancia restante y llegada de sus textos |
| `IconConverter.kt`, `IconBits.kt` | Convierten la flecha a 48×48 a 1 bit |
| `Protocol.kt` | Construye y trocea los paquetes BLE de hora y navegación |
| `FirmwareUpdateProtocol.kt` | Órdenes, comprobaciones y estados de la actualización del firmware |
| `BleService.kt` | Mantiene la conexión con la placa y envía hora, navegación y firmware |
| `Hub.kt` | Estado compartido entre las partes de la app y registro |
| `MainActivity.kt`, `LogActivity.kt` | Pantallas de la app |
| `Prefs.kt`, `Perms.kt` | Placa emparejada, permisos y ahorro de batería |

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
