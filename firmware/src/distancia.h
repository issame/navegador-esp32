#pragma once
// Conversión de los textos de distancia que envía la app ("250 m", "1,2 km", "0.3 mi",
// "800 ft") a metros, para calcular el progreso hacia la maniobra.

#include <Arduino.h>

// Devuelve la distancia en metros, o -1 si el texto no es una distancia reconocible.
inline float distanciaEnMetros(const String &texto)
{
  String limpio = texto;
  limpio.trim();
  limpio.toLowerCase();

  // Parte numérica: dígitos y separadores ("1,2", "0.3", "1.200").
  unsigned int finNumero = 0;
  while (finNumero < limpio.length() &&
         (isDigit(limpio[finNumero]) || limpio[finNumero] == ',' || limpio[finNumero] == '.'))
    finNumero++;
  if (finNumero == 0)
    return -1;
  String numero = limpio.substring(0, finNumero);
  String unidad = limpio.substring(finNumero);
  unidad.trim();

  // En kilómetros y millas el separador es decimal ("1,2 km"). En metros, pies y yardas
  // solo puede ser de miles ("1.200 m"), así que se quita.
  bool unidadConDecimales = unidad.startsWith("km") || unidad.startsWith("mi");
  if (unidadConDecimales)
    numero.replace(',', '.');
  else
  {
    numero.replace(".", "");
    numero.replace(",", "");
  }
  float valor = numero.toFloat();

  if (unidad.startsWith("km"))
    return valor * 1000.0f;
  if (unidad.startsWith("mi"))
    return valor * 1609.34f;
  if (unidad.startsWith("ft"))
    return valor * 0.3048f;
  if (unidad.startsWith("yd"))
    return valor * 0.9144f;
  if (unidad.startsWith("m"))
    return valor;
  return -1;
}
