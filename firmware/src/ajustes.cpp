// Ajustes de la pantalla (ver ajustes.h).

#include "ajustes.h"
#include <Preferences.h>

namespace ajustes
{
  // Memoria permanente de la placa (NVS): espacio y claves.
  static const char *ESPACIO_NVS = "ajustes";
  static const char *CLAVE_BRILLO = "brillo";
  static const char *CLAVE_AUTOMATICO = "auto";
  static const char *CLAVE_AHORRO = "ahorro";

  // Frecuencias de la CPU. 80 MHz es lo mínimo con el Bluetooth funcionando.
  static const uint32_t CPU_NORMAL_MHZ = 160;
  static const uint32_t CPU_AHORRO_MHZ = 80;

  // Horas de la curva automática (en minutos desde medianoche).
  static const int EMPIEZA_A_SUBIR = 7 * 60;
  static const int BRILLO_PLENO_DESDE = 9 * 60;
  static const int EMPIEZA_A_BAJAR = 19 * 60;
  static const int BRILLO_MINIMO_DESDE = 22 * 60;

  static Preferences memoria;
  static int brillo = 80;
  static bool automatico = false;
  static bool ahorro = false;

  static int limitarBrillo(int porcentaje)
  {
    return constrain(porcentaje, BRILLO_MINIMO, BRILLO_MAXIMO);
  }

  static void aplicarVelocidadCpu()
  {
    setCpuFrequencyMhz(ahorro ? CPU_AHORRO_MHZ : CPU_NORMAL_MHZ);
    Serial.printf("CPU a %u MHz\n", (unsigned)getCpuFrequencyMhz());
  }

  void cargar()
  {
    memoria.begin(ESPACIO_NVS, false);
    brillo = limitarBrillo(memoria.getInt(CLAVE_BRILLO, 80));
    automatico = memoria.getBool(CLAVE_AUTOMATICO, false);
    ahorro = memoria.getBool(CLAVE_AHORRO, false);
    aplicarVelocidadCpu();
  }

  int brilloManual() { return brillo; }
  bool brilloAutomatico() { return automatico; }
  bool modoAhorro() { return ahorro; }

  void cambiarBrilloManual(int porcentaje)
  {
    brillo = limitarBrillo(porcentaje);
    automatico = false;
    memoria.putInt(CLAVE_BRILLO, brillo);
    memoria.putBool(CLAVE_AUTOMATICO, false);
  }

  void cambiarBrilloAutomatico(bool activado)
  {
    automatico = activado;
    memoria.putBool(CLAVE_AUTOMATICO, activado);
  }

  void cambiarModoAhorro(bool activado)
  {
    // Solo se guarda (ver ajustes.h): se aplicará en el próximo arranque.
    ahorro = activado;
    memoria.putBool(CLAVE_AHORRO, activado);
  }

  int brilloSegunHora(int hora, int minuto)
  {
    int ahora = hora * 60 + minuto;
    int rango = BRILLO_MAXIMO - BRILLO_MINIMO;
    if (ahora < EMPIEZA_A_SUBIR || ahora >= BRILLO_MINIMO_DESDE)
      return BRILLO_MINIMO;
    if (ahora < BRILLO_PLENO_DESDE) // amanecer: sube poco a poco
      return BRILLO_MINIMO + rango * (ahora - EMPIEZA_A_SUBIR) / (BRILLO_PLENO_DESDE - EMPIEZA_A_SUBIR);
    if (ahora < EMPIEZA_A_BAJAR)
      return BRILLO_MAXIMO;
    // atardecer: baja poco a poco
    return BRILLO_MAXIMO - rango * (ahora - EMPIEZA_A_BAJAR) / (BRILLO_MINIMO_DESDE - EMPIEZA_A_BAJAR);
  }

  int brilloActual(bool horaConocida, int hora, int minuto)
  {
    // Sin hora (antes de conectar el móvil) el automático no sabe qué toca: se usa el manual.
    if (automatico && horaConocida)
      return brilloSegunHora(hora, minuto);
    return brillo;
  }

  bool esDeNoche(int hora)
  {
    return hora >= 22 || hora < 7;
  }
}
