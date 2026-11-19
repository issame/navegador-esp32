// Actualización del firmware por Bluetooth (ver ota.h).

#include "ota.h"
#include <Update.h>

namespace ota
{
  // Órdenes que la app escribe en la característica de control.
  static const uint8_t ORDEN_EMPEZAR = 0x01;
  static const uint8_t ORDEN_TERMINAR = 0x02;
  static const uint8_t ORDEN_CANCELAR = 0x03;

  // Espera antes de reiniciar, para que la app reciba el aviso de "completada".
  static const uint32_t ESPERA_ANTES_DE_REINICIAR_MS = 1500;

  static NimBLECharacteristic *caracteristicaControl = nullptr;

  // Estas variables las cambia la tarea de Bluetooth y las lee loop().
  static volatile Estado estadoActual = INACTIVA;
  static volatile CodigoError errorActual = SIN_ERROR;
  static volatile uint32_t bytesEsperados = 0;
  static volatile uint32_t bytesRecibidos = 0;
  static volatile uint32_t momentoCompletada = 0;

  static int calcularPorcentaje()
  {
    if (bytesEsperados == 0)
      return 0;
    return (int)((uint64_t)bytesRecibidos * 100 / bytesEsperados);
  }

  // Publica el estado (estado, porcentaje, error) para que la app lo lea o lo reciba.
  static void publicarEstado()
  {
    if (caracteristicaControl == nullptr)
      return;
    uint8_t valor[3] = {(uint8_t)estadoActual, (uint8_t)calcularPorcentaje(), (uint8_t)errorActual};
    caracteristicaControl->setValue(valor, sizeof(valor));
    caracteristicaControl->notify();
  }

  static void fallar(CodigoError codigo)
  {
    if (Update.isRunning())
      Update.abort();
    estadoActual = FALLIDA;
    errorActual = codigo;
    Serial.printf("OTA: error %d\n", codigo);
    publicarEstado();
  }

  static void empezar(const uint8_t *datos, size_t longitud)
  {
    // 01 + tamaño (4 bytes, little endian) + MD5 (16 bytes)
    if (longitud < 1 + 4 + 16)
      return fallar(ORDEN_DESCONOCIDA);
    if (Update.isRunning())
      Update.abort(); // un intento anterior a medias

    uint32_t tamano = datos[1] | (datos[2] << 8) | (datos[3] << 16) | ((uint32_t)datos[4] << 24);
    char md5Hex[33];
    for (int i = 0; i < 16; i++)
      sprintf(&md5Hex[i * 2], "%02x", datos[5 + i]);

    bytesEsperados = tamano;
    bytesRecibidos = 0;
    errorActual = SIN_ERROR;
    if (!Update.begin(tamano) || !Update.setMD5(md5Hex))
      return fallar(NO_SE_PUEDE_EMPEZAR);

    estadoActual = RECIBIENDO;
    Serial.printf("OTA: empezando, %u bytes\n", (unsigned)tamano);
    publicarEstado();
  }

  static void terminar()
  {
    if (estadoActual != RECIBIENDO)
      return fallar(ORDEN_DESCONOCIDA);
    if (bytesRecibidos != bytesEsperados)
      return fallar(TAMANO_INCORRECTO);
    // end(true) comprueba el MD5 y la imagen, y marca la partición nueva para arrancar.
    if (!Update.end(true))
      return fallar(VERIFICACION_FALLIDA);

    estadoActual = COMPLETADA;
    momentoCompletada = millis();
    Serial.println("OTA: completada, reiniciando");
    publicarEstado();
  }

  // Avisos de escritura en la característica de control.
  class AlEscribirControl : public NimBLECharacteristicCallbacks
  {
    void onWrite(NimBLECharacteristic *caracteristica, NimBLEConnInfo &conexion) override
    {
      NimBLEAttValue valor = caracteristica->getValue();
      if (valor.length() == 0)
        return;
      switch (valor.data()[0])
      {
      case ORDEN_EMPEZAR:
        empezar(valor.data(), valor.length());
        break;
      case ORDEN_TERMINAR:
        terminar();
        break;
      case ORDEN_CANCELAR:
        if (Update.isRunning())
          Update.abort();
        estadoActual = INACTIVA;
        Serial.println("OTA: cancelada");
        publicarEstado();
        break;
      default:
        fallar(ORDEN_DESCONOCIDA);
      }
    }
  };

  // Avisos de escritura en la característica de datos: cada trozo va directo a la flash.
  class AlEscribirDatos : public NimBLECharacteristicCallbacks
  {
    void onWrite(NimBLECharacteristic *caracteristica, NimBLEConnInfo &conexion) override
    {
      if (estadoActual != RECIBIENDO)
        return;
      NimBLEAttValue valor = caracteristica->getValue();
      size_t escritos = Update.write((uint8_t *)valor.data(), valor.length());
      if (escritos != valor.length())
        return fallar(FALLO_DE_ESCRITURA);
      int porcentajeAnterior = calcularPorcentaje();
      bytesRecibidos += escritos;
      if (bytesRecibidos > bytesEsperados)
        return fallar(TAMANO_INCORRECTO);
      if (calcularPorcentaje() != porcentajeAnterior)
        publicarEstado(); // un aviso por cada 1 %, no por cada trozo
    }
  };

  static AlEscribirControl avisosControl;
  static AlEscribirDatos avisosDatos;

  void registrarServicio(NimBLEServer *servidor)
  {
    NimBLEService *servicio = servidor->createService(UUID_SERVICIO);
    caracteristicaControl = servicio->createCharacteristic(
        UUID_CONTROL, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    caracteristicaControl->setCallbacks(&avisosControl);
    NimBLECharacteristic *caracteristicaDatos = servicio->createCharacteristic(
        UUID_DATOS, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    caracteristicaDatos->setCallbacks(&avisosDatos);

    uint8_t inicial[3] = {INACTIVA, 0, SIN_ERROR};
    caracteristicaControl->setValue(inicial, sizeof(inicial));
  }

  bool enCurso() { return estadoActual == RECIBIENDO; }
  Estado estado() { return estadoActual; }
  CodigoError ultimoError() { return errorActual; }
  int porcentaje() { return calcularPorcentaje(); }

  void atender()
  {
    if (estadoActual == COMPLETADA && millis() - momentoCompletada > ESPERA_ANTES_DE_REINICIAR_MS)
      ESP.restart();
  }
}
