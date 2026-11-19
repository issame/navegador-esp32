#pragma once
// Pantalla de arranque: icono de un mapa plegado con un marcador, dibujado con
// primitivas (sin imágenes), antes de iniciar LVGL.

#include "pantalla.hpp"

namespace arranque
{
  // Diseño en una cuadrícula de 96x96, escalado y centrado en la pantalla.
  constexpr float ESCALA = 1.35f;
  constexpr float OX = 120 - 48 * ESCALA;
  constexpr float OY = 104 - 48 * ESCALA;

  inline int32_t X(float x) { return (int32_t)(OX + x * ESCALA + 0.5f); }
  inline int32_t Y(float y) { return (int32_t)(OY + y * ESCALA + 0.5f); }

  inline void cuadrilatero(LGFX &t, float x0, float y0, float x1, float y1,
                           float x2, float y2, float x3, float y3, uint32_t c)
  {
    t.fillTriangle(X(x0), Y(y0), X(x1), Y(y1), X(x2), Y(y2), c);
    t.fillTriangle(X(x0), Y(y0), X(x2), Y(y2), X(x3), Y(y3), c);
  }

  inline void dibujar(LGFX &t)
  {
    const uint32_t verde = t.color888(0x1D, 0x9E, 0x75);
    const uint32_t verdeOscuro = t.color888(0x0F, 0x6E, 0x56);
    const uint32_t verdeClaro = t.color888(0xE1, 0xF5, 0xEE);
    const uint32_t rojo = t.color888(0xE2, 0x4B, 0x4A);
    const uint32_t gris = t.color888(0xB4, 0xB2, 0xA9);

    t.fillScreen(TFT_BLACK);

    // Mapa plegado en tres paneles
    cuadrilatero(t, 10, 26, 34, 16, 34, 72, 10, 82, verde);
    cuadrilatero(t, 34, 16, 62, 26, 62, 82, 34, 72, verdeOscuro);
    cuadrilatero(t, 62, 26, 86, 16, 86, 72, 62, 82, verde);

    // Ruta discontinua
    const float ruta[][2] = {{16, 60}, {24, 56}, {32, 56}, {40, 60}, {48, 58}, {56, 53}, {66, 50}, {76, 48}, {83, 48}};
    for (auto &p : ruta)
      t.fillCircle(X(p[0]), Y(p[1]), 2, verdeClaro);

    // Marcador
    t.fillCircle(X(60), Y(27), (int32_t)(17 * ESCALA), rojo);
    t.fillTriangle(X(44.5f), Y(35), X(75.5f), Y(35), X(60), Y(55), rojo);
    t.fillCircle(X(60), Y(27), (int32_t)(7 * ESCALA), TFT_WHITE);

    // Nombre
    t.setTextColor(gris);
    t.setTextDatum(textdatum_t::top_center);
    t.setFont(&fonts::FreeSans9pt7b);
    t.drawString("Navegador", 120, 196);
  }
}
