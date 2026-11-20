#pragma once
// Ajustes de la pantalla que elige el usuario en la pantalla de Brillo, guardados en la
// memoria de la placa (NVS) para que se conserven al apagarla:
//   - brillo manual (10 … 100 %)
//   - brillo automático según la hora
//   - modo ahorro (CPU a 80 MHz)
// También calcula el brillo automático y si es de noche (modo noche).

#include <Arduino.h>

namespace ajustes
{
  const int BRILLO_MINIMO = 10;  // %
  const int BRILLO_MAXIMO = 100; // %
  const int PASO_BRILLO = 10;    // % por pulsación de − / +

  // Lee los ajustes guardados. Llamar una vez al arrancar, antes de usar el resto.
  void cargar();

  int brilloManual();
  bool brilloAutomatico();
  bool modoAhorro();

  // Cambian el ajuste, lo aplican y lo guardan.
  void cambiarBrilloManual(int porcentaje); // también desactiva el automático
  void cambiarBrilloAutomatico(bool activado);
  void cambiarModoAhorro(bool activado);

  // Brillo que toca ahora mismo (manual, o automático según la hora si se conoce).
  int brilloActual(bool horaConocida, int hora, int minuto);

  // Curva del brillo automático: 10 % de 22:00 a 7:00, sube hasta el 100 % a las 9:00,
  // 100 % hasta las 19:00 y baja hasta el 10 % a las 22:00, sin saltos.
  int brilloSegunHora(int hora, int minuto);

  // De 22:00 a 7:00 es de noche (colores cálidos y luna).
  bool esDeNoche(int hora);
}
