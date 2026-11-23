#pragma once
// Pantalla de arranque animada: un mapa plegado que se despliega, una ruta que se dibuja
// punto a punto y un marcador rojo que cae con un rebote; después aparecen el nombre y la
// versión, y todo se funde a negro. Dura unos 2,4 s.
//
// Se dibuja con primitivas de LovyanGFX (triángulos y círculos), sin imágenes, antes de
// iniciar LVGL. Cada fotograma se compone en memoria (solo la zona del icono) y se envía
// de una vez para que no parpadee. Si no hay memoria para ello, se dibuja estático.

#include "pantalla.hpp"
#include "version.h"

namespace arranque
{
  // El dibujo está diseñado en una cuadrícula de 96x96 y se escala y centra en la pantalla.
  constexpr float ESCALA = 1.35f;
  constexpr float DESPLAZAMIENTO_X = 120 - 48 * ESCALA; // centrado horizontal
  constexpr float DESPLAZAMIENTO_Y = 104 - 48 * ESCALA; // algo por encima del centro, deja sitio al texto

  // Zona del icono que se redibuja en cada fotograma (en píxeles de pantalla).
  constexpr int ZONA_X = 50, ZONA_Y = 0, ZONA_ANCHO = 140, ZONA_ALTO = 160;

  // Momentos de la animación (ms desde el inicio).
  constexpr uint32_t INICIO_PANELES = 50, PASO_PANELES = 150, DURACION_PANEL = 350;
  constexpr uint32_t INICIO_RUTA = 500, PASO_RUTA = 65;
  constexpr uint32_t INICIO_CAIDA = 1050, DURACION_CAIDA = 600;
  constexpr uint32_t INICIO_POP = 1550, DURACION_POP = 250;
  constexpr uint32_t INICIO_NOMBRE = 1700, INICIO_VERSION = 1950, DURACION_TEXTO = 400;
  constexpr uint32_t INICIO_FUNDIDO = 2400, DURACION_FUNDIDO = 350;
  constexpr uint32_t MS_POR_FOTOGRAMA = 33; // unos 30 fotogramas por segundo
  constexpr float ALTURA_CAIDA = 66;       // el marcador cae desde 66 unidades más arriba

  // Ruta discontinua: puntos a lo largo de una curva suave.
  constexpr float PUNTOS_RUTA[][2] = {{16, 60}, {24, 56}, {32, 56}, {40, 60}, {48, 58},
                                      {56, 53}, {66, 50}, {76, 48}, {83, 48}};

  // Colores
  constexpr uint32_t VERDE = 0x1D9E75, VERDE_OSCURO = 0x0F6E56, VERDE_CLARO = 0xE1F5EE;
  constexpr uint32_t ROJO = 0xE24B4A, GRIS = 0xB4B2A9, GRIS_OSCURO = 0x888780;

  // Pasa una coordenada de la cuadrícula de diseño a píxeles de pantalla.
  inline float aPantallaX(float x) { return DESPLAZAMIENTO_X + x * ESCALA; }
  inline float aPantallaY(float y) { return DESPLAZAMIENTO_Y + y * ESCALA; }

  // ------------------------------------------------------------ curvas de animación

  // Avance 0 … 1 de una fase que empieza en `inicio` y dura `duracion`.
  inline float avance(uint32_t tiempo, uint32_t inicio, uint32_t duracion)
  {
    if (tiempo <= inicio)
      return 0;
    if (tiempo >= inicio + duracion)
      return 1;
    return (float)(tiempo - inicio) / duracion;
  }

  // Frena al final (para desplegar y desvanecer).
  inline float suavizarSalida(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }

  // Se pasa un poco y vuelve (el rebote del marcador al caer).
  inline float conRebote(float t)
  {
    const float c1 = 1.70158f, c3 = c1 + 1;
    float u = t - 1;
    return 1 + c3 * u * u * u + c1 * u * u;
  }

  // Mezcla de negro al color (para los textos que aparecen).
  inline uint32_t atenuar(uint32_t color, float intensidad)
  {
    uint32_t r = ((color >> 16) & 0xFF) * intensidad;
    uint32_t g = ((color >> 8) & 0xFF) * intensidad;
    uint32_t b = (color & 0xFF) * intensidad;
    return (r << 16) | (g << 8) | b;
  }

  // ------------------------------------------------------------ dibujo de un fotograma

  // Lienzo donde se dibuja: la pantalla (dibujo estático) o la memoria (animación).
  template <typename Lienzo>
  struct Dibujante
  {
    Lienzo &lienzo;
    float origenX, origenY; // dónde está la esquina del lienzo en la pantalla

    int x(float xDiseno) const { return (int)(aPantallaX(xDiseno) - origenX + 0.5f); }
    int y(float yDiseno) const { return (int)(aPantallaY(yDiseno) - origenY + 0.5f); }
    uint32_t color(uint32_t rgb) { return lienzo.color888(rgb >> 16, (rgb >> 8) & 0xFF, rgb & 0xFF); }

    // Panel del mapa que se despliega desde su borde izquierdo (despliegue 0 … 1).
    void panel(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
               uint32_t rgb, float despliegue)
    {
      if (despliegue <= 0)
        return;
      float borde = x0; // los paneles empiezan a la izquierda (x0 = x3)
      auto ancho = [&](float xx) { return borde + (xx - borde) * despliegue; };
      uint32_t c = color(rgb);
      lienzo.fillTriangle(x(ancho(x0)), y(y0), x(ancho(x1)), y(y1), x(ancho(x2)), y(y2), c);
      lienzo.fillTriangle(x(ancho(x0)), y(y0), x(ancho(x2)), y(y2), x(ancho(x3)), y(y3), c);
    }

    // Icono completo en el momento `tiempo` (ms). Con tiempo muy alto, queda el dibujo final.
    void icono(uint32_t tiempo)
    {
      // 1. Paneles del mapa, uno tras otro.
      for (int i = 0; i < 3; i++)
      {
        float despliegue = suavizarSalida(avance(tiempo, INICIO_PANELES + i * PASO_PANELES, DURACION_PANEL));
        if (i == 0)
          panel(10, 26, 34, 16, 34, 72, 10, 82, VERDE, despliegue);
        else if (i == 1)
          panel(34, 16, 62, 26, 62, 82, 34, 72, VERDE_OSCURO, despliegue);
        else
          panel(62, 26, 86, 16, 86, 72, 62, 82, VERDE, despliegue);
      }

      // 2. Ruta: aparece punto a punto.
      for (int i = 0; i < 9; i++)
        if (tiempo >= INICIO_RUTA + i * PASO_RUTA)
          lienzo.fillCircle(x(PUNTOS_RUTA[i][0]), y(PUNTOS_RUTA[i][1]), 2, color(VERDE_CLARO));

      // 3. Marcador: cae desde arriba con rebote; luego aparece el punto blanco con un "pop".
      if (tiempo >= INICIO_CAIDA)
      {
        float caida = conRebote(avance(tiempo, INICIO_CAIDA, DURACION_CAIDA));
        float desplazamiento = -ALTURA_CAIDA * (1 - caida);
        uint32_t rojo = color(ROJO);
        lienzo.fillCircle(x(60), y(27 + desplazamiento), (int)(17 * ESCALA), rojo);
        lienzo.fillTriangle(x(44.5f), y(35 + desplazamiento), x(75.5f), y(35 + desplazamiento),
                            x(60), y(55 + desplazamiento), rojo);

        float pop = avance(tiempo, INICIO_POP, DURACION_POP);
        if (pop > 0)
        {
          float tamano = pop < 0.7f ? pop / 0.7f * 1.25f : 1.25f - (pop - 0.7f) / 0.3f * 0.25f;
          lienzo.fillCircle(x(60), y(27 + desplazamiento), (int)(7 * ESCALA * tamano), TFT_WHITE);
        }
      }
    }
  };

  // Nombre y versión debajo del icono, con la intensidad dada (0 = invisible, 1 = normal).
  inline void textos(PantallaRedonda &pantalla, float intensidadNombre, float intensidadVersion,
                     int subidaNombre)
  {
    pantalla.fillRect(60, 192, 120, 44, TFT_BLACK); // borra los textos del fotograma anterior
    pantalla.setTextDatum(textdatum_t::top_center);
    if (intensidadNombre > 0)
    {
      uint32_t c = atenuar(GRIS, intensidadNombre);
      pantalla.setTextColor(pantalla.color888(c >> 16, (c >> 8) & 0xFF, c & 0xFF));
      pantalla.setFont(&fonts::FreeSans9pt7b);
      pantalla.drawString("Navegador", ANCHO_PANTALLA / 2, 196 + subidaNombre);
    }
    if (intensidadVersion > 0)
    {
      uint32_t c = atenuar(GRIS_OSCURO, intensidadVersion);
      pantalla.setTextColor(pantalla.color888(c >> 16, (c >> 8) & 0xFF, c & 0xFF));
      pantalla.setFont(&fonts::Font2); // fuente pequeña integrada (16 px)
      pantalla.drawString("v" VERSION_FIRMWARE, ANCHO_PANTALLA / 2, 218);
    }
  }

  // Dibujo estático (el fotograma final), por si no hay memoria para animar.
  inline void dibujar(PantallaRedonda &pantalla)
  {
    pantalla.fillScreen(TFT_BLACK);
    Dibujante<PantallaRedonda> dibujante{pantalla, 0, 0};
    dibujante.icono(UINT32_MAX);
    textos(pantalla, 1, 1, 0);
  }

  // Reproduce la animación completa (≈2,4 s) y la funde a negro bajando el brillo.
  // `tareaDeFondo` se llama entre fotogramas (para atender el Bluetooth).
  // Al terminar, la pantalla queda negra y con el brillo a 0: quien llama debe subirlo.
  inline void animar(PantallaRedonda &pantalla, uint8_t brillo, void (*tareaDeFondo)())
  {
    pantalla.fillScreen(TFT_BLACK);
    pantalla.setBrightness(brillo);

    LGFX_Sprite fotograma(&pantalla);
    fotograma.setColorDepth(16);
    bool hayMemoria = fotograma.createSprite(ZONA_ANCHO, ZONA_ALTO) != nullptr;
    if (!hayMemoria)
      dibujar(pantalla);

    uint32_t inicio = millis();
    for (;;)
    {
      uint32_t tiempo = millis() - inicio;
      if (tiempo >= INICIO_FUNDIDO + DURACION_FUNDIDO)
        break;

      if (hayMemoria && tiempo < INICIO_FUNDIDO)
      {
        fotograma.fillScreen(TFT_BLACK);
        Dibujante<LGFX_Sprite> dibujante{fotograma, (float)ZONA_X, (float)ZONA_Y};
        dibujante.icono(tiempo);
        fotograma.pushSprite(ZONA_X, ZONA_Y);

        // Los textos solo se redibujan mientras aparecen (y un fotograma más).
        bool textosCambiando = tiempo >= INICIO_NOMBRE &&
                               tiempo <= INICIO_VERSION + DURACION_TEXTO + MS_POR_FOTOGRAMA;
        if (textosCambiando)
        {
          float nombre = suavizarSalida(avance(tiempo, INICIO_NOMBRE, DURACION_TEXTO));
          float version = avance(tiempo, INICIO_VERSION, DURACION_TEXTO);
          textos(pantalla, nombre, version, (int)(8 * (1 - nombre)));
        }
      }

      // Fundido final: se baja el brillo hasta apagar.
      if (tiempo >= INICIO_FUNDIDO)
      {
        float restante = 1 - avance(tiempo, INICIO_FUNDIDO, DURACION_FUNDIDO);
        pantalla.setBrightness((uint8_t)(brillo * restante));
      }

      if (tareaDeFondo)
        tareaDeFondo();
      uint32_t ocupado = millis() - inicio - tiempo;
      if (ocupado < MS_POR_FOTOGRAMA)
        delay(MS_POR_FOTOGRAMA - ocupado);
    }

    pantalla.setBrightness(0);
    if (hayMemoria)
      fotograma.deleteSprite();
  }
}
