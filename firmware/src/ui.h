#pragma once
// Interfaz LVGL de la placa: pantalla de reloj y pantalla de navegación.
// Todos los textos están en castellano.

#include <Arduino.h>
#include <lvgl.h>

// Crea las dos pantallas y muestra el reloj.
void ui_init();

// Actualiza el reloj (solo redibuja si algo ha cambiado).
// Si `horaSincronizada` es false se muestra "--:--" hasta recibir la hora del móvil.
// diaSemana: 0 = domingo … 6 = sábado. mes: 1 … 12.
void ui_actualizar_reloj(bool horaSincronizada, int hora, int minuto, int diaSemana, int dia, int mes);

// Icono de Bluetooth del reloj: gris sin conexión, azul con el móvil conectado.
void ui_mostrar_conexion(bool conectado);

// Rellena la pantalla de navegación y la muestra (con un fundido) si no estaba visible.
// Los textos vacíos se dejan en blanco. `hayFlecha` indica si la ruta tiene flecha.
void ui_mostrar_navegacion(const String &distanciaManiobra, const String &indicacion,
                           const String &horaLlegada, const String &tiempoRestante,
                           const String &distanciaRestante, bool hayFlecha);

// Pinta la flecha de la maniobra: 48x48 a 1 bit por píxel (288 bytes), con el bit
// más alto de cada byte a la izquierda. nullptr la oculta.
void ui_mostrar_flecha(const uint8_t *bitsFlecha);

// Vuelve a la pantalla de reloj (con un fundido) si no estaba visible.
void ui_volver_al_reloj();
