#pragma once
// Flechas vectoriales de las maniobras, dibujadas con líneas, arcos y triángulos
// suavizados en un lienzo de 80x80 px. Se ven nítidas y cambian de color con el modo noche.
//
// Códigos de maniobra (los manda la app en el campo "velocidad" del paquete):
//   C      recto                     TU     cambio de sentido
//   TL/TR  izquierda / derecha       KL/KR  mantenerse izquierda / derecha (carril)
//   TSLL/TSLR  ligeramente           EXITL/EXITR  salida (de autopista)
//   TSHL/TSHR  bruscamente           RNDB1…RNDB8  rotonda, salida n;  RNDBX  salir de la rotonda

#include <lvgl.h>

#define LADO_FLECHA_VECTORIAL 80

// Dibuja la flecha de `codigo` en `lienzo` (RGB565 de 80x80) con el color dado.
// Devuelve false si el código no se conoce (entonces se usa la flecha de la app).
// En las rotondas, `numeroSalida` recibe el número de salida (o 0 si no aplica).
bool flechas_dibujar(lv_obj_t *lienzo, const char *codigo, lv_color_t color, int *numeroSalida);
