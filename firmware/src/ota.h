#pragma once
// Actualización del firmware por Bluetooth (OTA) desde la app Android.
//
// Servicio BLE propio, independiente del de ChronosESP32:
//   - Control (escritura, lectura y notificación):
//       escribir  01 + tamaño (4 bytes, little endian) + MD5 (16 bytes)  → empezar
//       escribir  02                                                      → terminar
//       escribir  03                                                      → cancelar
//     Estado (lectura / notificación): estado, porcentaje, código de error.
//   - Datos (escritura): el archivo .bin del firmware, por orden, en trozos.
//
// El firmware nuevo se escribe en la partición libre. Solo si llega completo y su MD5
// coincide se marca para arrancar, así que un corte a medias no estropea la placa.

#include <Arduino.h>
#include <NimBLEDevice.h>

namespace ota
{
  static const char *UUID_SERVICIO = "c8659210-af91-4ad3-a995-a58d6fd26145";
  static const char *UUID_CONTROL = "c8659211-af91-4ad3-a995-a58d6fd26145";
  static const char *UUID_DATOS = "c8659212-af91-4ad3-a995-a58d6fd26145";

  enum Estado : uint8_t
  {
    INACTIVA = 0,
    RECIBIENDO = 1,
    COMPLETADA = 2, // la placa se reinicia con el firmware nuevo
    FALLIDA = 3,
  };

  enum CodigoError : uint8_t
  {
    SIN_ERROR = 0,
    NO_SE_PUEDE_EMPEZAR = 1, // tamaño excesivo o sin partición libre
    FALLO_DE_ESCRITURA = 2,
    TAMANO_INCORRECTO = 3,   // llegaron más o menos bytes de los anunciados
    VERIFICACION_FALLIDA = 4, // el MD5 no coincide o la imagen no es válida
    ORDEN_DESCONOCIDA = 5,
  };

  // Crea el servicio en el servidor BLE. Hay que llamarlo antes de empezar a anunciarse.
  void registrarServicio(NimBLEServer *servidor);

  bool enCurso();             // se está recibiendo un firmware
  Estado estado();
  CodigoError ultimoError();
  int porcentaje();           // 0 … 100

  // Llamar en loop(): reinicia la placa poco después de completar la actualización.
  void atender();
}
