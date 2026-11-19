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
// Los textos vacíos se dejan en blanco. Sin distancia a la maniobra, la indicación sube
// y puede ocupar hasta 4 líneas. La flecha se mantiene (ver ui_mostrar_flecha).
void ui_mostrar_navegacion(const String &distanciaManiobra, const String &indicacion,
                           const String &horaLlegada, const String &tiempoRestante,
                           const String &distanciaRestante);

// Pinta la flecha de la maniobra: 48x48 a 1 bit por píxel (288 bytes), con el bit
// más alto de cada byte a la izquierda. nullptr la oculta (solo al terminar la ruta).
void ui_mostrar_flecha(const uint8_t *bitsFlecha);

// Vuelve a la pantalla de reloj (con un fundido) si no estaba visible.
void ui_volver_al_reloj();

// Arco alrededor del borde que se completa al acercarse a la maniobra.
// fraccionRecorrida: 0 = acaba de empezar el tramo, 1 = en la maniobra; negativo lo oculta.
// aviso: maniobra inminente; el arco se vuelve naranja, parpadea y la distancia también
// se pinta en naranja.
void ui_mostrar_progreso_maniobra(float fraccionRecorrida, bool aviso);

// Pantalla de actualización del firmware por Bluetooth.
void ui_mostrar_actualizacion(int porcentaje);
void ui_mostrar_error_actualizacion(int codigoError);
