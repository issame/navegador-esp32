#pragma once
// Pantalla de arranque: un mapa plegado con una ruta discontinua y un marcador rojo.
// Se dibuja con primitivas de LovyanGFX (triángulos y círculos), sin imágenes, antes
// de iniciar LVGL.

#include "pantalla.hpp"

namespace arranque
{
  // El dibujo está diseñado en una cuadrícula de 96x96 y se escala y centra en la pantalla.
  constexpr float ESCALA = 1.35f;
  constexpr float DESPLAZAMIENTO_X = 120 - 48 * ESCALA; // centrado horizontal
  constexpr float DESPLAZAMIENTO_Y = 104 - 48 * ESCALA; // algo por encima del centro, deja sitio al texto

  // Pasa una coordenada de la cuadrícula de diseño a píxeles de la pantalla.
  inline int32_t aPantallaX(float x) { return (int32_t)(DESPLAZAMIENTO_X + x * ESCALA + 0.5f); }
  inline int32_t aPantallaY(float y) { return (int32_t)(DESPLAZAMIENTO_Y + y * ESCALA + 0.5f); }
  inline int32_t aPantallaRadio(float r) { return (int32_t)(r * ESCALA); }

  // Rellena un cuadrilátero (vértices en orden) con dos triángulos.
  inline void rellenarCuadrilatero(PantallaRedonda &pantalla,
                                   float x0, float y0, float x1, float y1,
                                   float x2, float y2, float x3, float y3, uint32_t color)
  {
    pantalla.fillTriangle(aPantallaX(x0), aPantallaY(y0), aPantallaX(x1), aPantallaY(y1),
                          aPantallaX(x2), aPantallaY(y2), color);
    pantalla.fillTriangle(aPantallaX(x0), aPantallaY(y0), aPantallaX(x2), aPantallaY(y2),
                          aPantallaX(x3), aPantallaY(y3), color);
  }

  inline void dibujar(PantallaRedonda &pantalla)
  {
    const uint32_t verde = pantalla.color888(0x1D, 0x9E, 0x75);
    const uint32_t verdeOscuro = pantalla.color888(0x0F, 0x6E, 0x56);
    const uint32_t verdeClaro = pantalla.color888(0xE1, 0xF5, 0xEE);
    const uint32_t rojo = pantalla.color888(0xE2, 0x4B, 0x4A);
    const uint32_t gris = pantalla.color888(0xB4, 0xB2, 0xA9);

    pantalla.fillScreen(TFT_BLACK);

    // Mapa plegado en tres paneles; el del centro, más oscuro, da sensación de pliegue.
    rellenarCuadrilatero(pantalla, 10, 26, 34, 16, 34, 72, 10, 82, verde);
    rellenarCuadrilatero(pantalla, 34, 16, 62, 26, 62, 82, 34, 72, verdeOscuro);
    rellenarCuadrilatero(pantalla, 62, 26, 86, 16, 86, 72, 62, 82, verde);

    // Ruta discontinua: puntos a lo largo de una curva suave.
    const float puntosRuta[][2] = {{16, 60}, {24, 56}, {32, 56}, {40, 60}, {48, 58},
                                   {56, 53}, {66, 50}, {76, 48}, {83, 48}};
    for (auto &punto : puntosRuta)
      pantalla.fillCircle(aPantallaX(punto[0]), aPantallaY(punto[1]), 2, verdeClaro);

    // Marcador: círculo + triángulo hacia abajo (forma de gota) con un agujero blanco.
    pantalla.fillCircle(aPantallaX(60), aPantallaY(27), aPantallaRadio(17), rojo);
    pantalla.fillTriangle(aPantallaX(44.5f), aPantallaY(35), aPantallaX(75.5f), aPantallaY(35),
                          aPantallaX(60), aPantallaY(55), rojo);
    pantalla.fillCircle(aPantallaX(60), aPantallaY(27), aPantallaRadio(7), TFT_WHITE);

    // Nombre debajo del icono.
    pantalla.setTextColor(gris);
    pantalla.setTextDatum(textdatum_t::top_center);
    pantalla.setFont(&fonts::FreeSans9pt7b);
    pantalla.drawString("Navegador", ANCHO_PANTALLA / 2, 196);
  }
}
