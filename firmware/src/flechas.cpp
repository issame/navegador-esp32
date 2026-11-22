// Flechas vectoriales de las maniobras (ver flechas.h).
//
// Coordenadas en el lienzo de 80x80: (0,0) arriba a la izquierda, la flecha "entra" por
// abajo. Ángulos como en LVGL: 0° = derecha, 90° = abajo, 180° = izquierda, 270° = arriba.

#include "flechas.h"
#include <math.h>
#include <string.h>
#include <stdlib.h>

#define GROSOR_TRAZO 9
#define LARGO_PUNTA 20
#define MEDIO_ANCHO_PUNTA 13
#define COLOR_CARRIL_QUE_SE_DEJA 0x5F5E5A

namespace
{
  lv_layer_t capa;
  lv_color_t colorTrazo;

  void linea(float x1, float y1, float x2, float y2, lv_color_t color, int grosor = GROSOR_TRAZO)
  {
    lv_draw_line_dsc_t trazo;
    lv_draw_line_dsc_init(&trazo);
    trazo.color = color;
    trazo.width = grosor;
    trazo.round_start = 1;
    trazo.round_end = 1;
    trazo.p1.x = x1;
    trazo.p1.y = y1;
    trazo.p2.x = x2;
    trazo.p2.y = y2;
    lv_draw_line(&capa, &trazo);
  }

  // Línea discontinua gris: el carril que se deja.
  void carrilQueSeDeja(float x, float yInicio, float yFin)
  {
    for (float y = yInicio; y > yFin; y -= 14)
      linea(x, y, x, fmaxf(y - 7, yFin), lv_color_hex(COLOR_CARRIL_QUE_SE_DEJA), 5);
  }

  // Punta de flecha: triángulo con la punta en (x, y) apuntando hacia `angulo` grados.
  // Devuelve en (baseX, baseY) el centro de su base, donde debe acabar el trazo.
  void punta(float x, float y, float angulo, float *baseX, float *baseY)
  {
    float radianes = angulo * (float)M_PI / 180.0f;
    float dx = cosf(radianes), dy = sinf(radianes);
    float bx = x - LARGO_PUNTA * dx, by = y - LARGO_PUNTA * dy;

    lv_draw_triangle_dsc_t triangulo;
    lv_draw_triangle_dsc_init(&triangulo);
    triangulo.color = colorTrazo;
    triangulo.p[0].x = x;
    triangulo.p[0].y = y;
    triangulo.p[1].x = bx - MEDIO_ANCHO_PUNTA * dy;
    triangulo.p[1].y = by + MEDIO_ANCHO_PUNTA * dx;
    triangulo.p[2].x = bx + MEDIO_ANCHO_PUNTA * dy;
    triangulo.p[2].y = by - MEDIO_ANCHO_PUNTA * dx;
    lv_draw_triangle(&capa, &triangulo);

    // La base se mete un poco dentro del triángulo para que no se vea la unión.
    *baseX = bx + 4 * dx;
    *baseY = by + 4 * dy;
  }

  // Trazo en dos tramos (entrada vertical desde abajo y giro) acabado en punta.
  // espejo = true dibuja la versión hacia la derecha a partir de la de la izquierda.
  void giro(float xEntrada, float yCodo, float xPunta, float yPunta, float angulo, bool espejo)
  {
    if (espejo)
    {
      xEntrada = 80 - xEntrada;
      xPunta = 80 - xPunta;
      angulo = 180 - angulo;
    }
    float baseX, baseY;
    punta(xPunta, yPunta, angulo, &baseX, &baseY);
    linea(xEntrada, 74, xEntrada, yCodo, colorTrazo);
    linea(xEntrada, yCodo, baseX, baseY, colorTrazo);
  }

  void arco(float cx, float cy, int radio, float inicio, float fin, int grosor)
  {
    lv_draw_arc_dsc_t dsc;
    lv_draw_arc_dsc_init(&dsc);
    dsc.color = colorTrazo;
    dsc.width = grosor;
    dsc.center.x = cx;
    dsc.center.y = cy;
    dsc.radius = radio;
    dsc.start_angle = inicio;
    dsc.end_angle = fin;
    dsc.rounded = 1;
    lv_draw_arc(&capa, &dsc);
  }

  // Rotonda: círculo, entrada desde abajo y salida hacia el ángulo de la salida n.
  // Se circula en sentido contrario a las agujas del reloj (conducción por la derecha):
  // 1.ª salida a la derecha, 2.ª recto, 3.ª a la izquierda, 4.ª y siguientes casi de vuelta.
  void rotonda(int salida)
  {
    const float cx = 40, cy = 40, radio = 17;
    arco(cx, cy, (int)radio + 4, 0, 360, 7);
    linea(cx, 76, cx, cy + radio + 4, colorTrazo);

    float angulo;
    switch (salida)
    {
    case 1: angulo = 0; break;   // derecha
    case 2: angulo = 270; break; // arriba (recto)
    case 3: angulo = 180; break; // izquierda
    default: angulo = 140; break; // abajo a la izquierda
    }
    float radianes = angulo * (float)M_PI / 180.0f;
    float baseX, baseY;
    punta(cx + 38 * cosf(radianes), cy + 38 * sinf(radianes), angulo, &baseX, &baseY);
    linea(cx + (radio + 4) * cosf(radianes), cy + (radio + 4) * sinf(radianes), baseX, baseY, colorTrazo);
  }
}

bool flechas_dibujar(lv_obj_t *lienzo, const char *codigo, lv_color_t color, int *numeroSalida)
{
  *numeroSalida = 0;
  if (codigo == nullptr || codigo[0] == '\0')
    return false;

  lv_canvas_fill_bg(lienzo, lv_color_black(), LV_OPA_COVER);
  lv_canvas_init_layer(lienzo, &capa);
  colorTrazo = color;
  bool conocido = true;

  if (strcmp(codigo, "C") == 0)
  {
    float baseX, baseY;
    punta(40, 6, 270, &baseX, &baseY);
    linea(40, 74, baseX, baseY, colorTrazo);
  }
  else if (strcmp(codigo, "TL") == 0 || strcmp(codigo, "TR") == 0)
    giro(50, 40, 8, 40, 180, codigo[1] == 'R');
  else if (strcmp(codigo, "TSLL") == 0 || strcmp(codigo, "TSLR") == 0)
    giro(48, 50, 16, 12, 225, codigo[3] == 'R');
  else if (strcmp(codigo, "TSHL") == 0 || strcmp(codigo, "TSHR") == 0)
    giro(52, 30, 18, 66, 135, codigo[3] == 'R');
  else if (strcmp(codigo, "KL") == 0 || strcmp(codigo, "EXITL") == 0)
  {
    carrilQueSeDeja(62, 74, 8);
    giro(44, 48, 18, 12, 235, false);
  }
  else if (strcmp(codigo, "KR") == 0 || strcmp(codigo, "EXITR") == 0)
  {
    carrilQueSeDeja(18, 74, 8);
    giro(44, 48, 18, 12, 235, true);
  }
  else if (strcmp(codigo, "TU") == 0)
  {
    // Sube por la derecha, da la vuelta por arriba y baja por la izquierda.
    float baseX, baseY;
    linea(54, 74, 54, 36, colorTrazo);
    arco(40, 36, 14 + GROSOR_TRAZO / 2, 180, 360, GROSOR_TRAZO);
    punta(26, 72, 90, &baseX, &baseY);
    linea(26, 36, baseX, baseY, colorTrazo);
  }
  else if (strncmp(codigo, "RNDB", 4) == 0)
  {
    int salida = codigo[4] == 'X' ? 2 : atoi(codigo + 4); // "salir de la rotonda": recto
    if (salida < 1)
      salida = 1;
    rotonda(salida);
    *numeroSalida = codigo[4] == 'X' ? 0 : salida;
  }
  else
  {
    conocido = false;
  }

  lv_canvas_finish_layer(lienzo, &capa);
  return conocido;
}
