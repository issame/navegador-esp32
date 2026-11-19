#pragma once
#include <Arduino.h>
#include <lvgl.h>

// Interfaz LVGL: pantalla de reloj y pantalla de navegación. Todo en castellano.

void ui_init();

// Reloj. Si `sincronizada` es false se muestra "--:--" hasta recibir la hora del móvil.
// diaSemana: 0 = domingo … 6 = sábado; mes: 1 … 12.
void ui_reloj(bool sincronizada, int hora, int minuto, int diaSemana, int dia, int mes);

// Icono de Bluetooth: gris sin conexión, azul con el móvil conectado.
void ui_conexion(bool conectado);

// Muestra la pantalla de navegación con los datos recibidos.
void ui_navegacion(const String &maniobra, const String &indicacion,
                   const String &llegada, const String &duracion, const String &distancia,
                   bool conIcono);

// Flecha de la maniobra: 48x48 a 1 bit por píxel (288 bytes). nullptr la oculta.
void ui_flecha(const uint8_t *bits48);

// Vuelve a la pantalla de reloj.
void ui_mostrar_reloj();
