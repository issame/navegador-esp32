#pragma once
// Interfaz LVGL de la placa: reloj, navegación, brillo, llegada y actualización.
// Todos los textos están en castellano.
//
// Navegación entre pantallas con el dedo:
//   reloj o navegación  --desliza a la izquierda-->  brillo
//   brillo              --desliza a la derecha o 10 s sin tocar-->  navegación (si hay ruta) o reloj

#include <Arduino.h>
#include <lvgl.h>

// Crea las pantallas y muestra el reloj. `alCambiarBrillo` se llama cuando el usuario
// cambia algún ajuste de brillo, para que se aplique en la retroiluminación.
void ui_init(void (*alCambiarBrillo)());

// Tareas periódicas de la interfaz (vuelta automática desde la pantalla de brillo).
void ui_atender();

// ---------------------------------------------------------------- reloj

// Actualiza el reloj (solo redibuja si algo ha cambiado).
// Si `horaSincronizada` es false se muestra "--:--" hasta recibir la hora del móvil.
// diaSemana: 0 = domingo … 6 = sábado. mes: 1 … 12.
void ui_actualizar_reloj(bool horaSincronizada, int hora, int minuto, int diaSemana, int dia, int mes);

// Icono de Bluetooth del reloj: gris sin conexión, azul con el móvil conectado.
// Sin conexión también se oculta la batería del móvil.
void ui_mostrar_conexion(bool conectado);

// Batería del móvil junto al icono de Bluetooth (porcentaje e icono; rayo si carga).
void ui_mostrar_bateria_movil(int porcentaje, bool cargando);

// ---------------------------------------------------------------- navegación

// Rellena la pantalla de navegación y la muestra si se está en el reloj (no interrumpe la
// pantalla de brillo). Sin distancia a la maniobra, la indicación sube y puede ocupar hasta
// 4 líneas. La flecha se mantiene (ver ui_mostrar_flecha).
void ui_mostrar_navegacion(const String &distanciaManiobra, const String &indicacion,
                           const String &horaLlegada, const String &tiempoRestante,
                           const String &distanciaRestante);

// Pinta la flecha de la maniobra: 48x48 a 1 bit por píxel (288 bytes), con el bit
// más alto de cada byte a la izquierda. nullptr la oculta (solo al terminar la ruta).
void ui_mostrar_flecha(const uint8_t *bitsFlecha);

// Arco alrededor del borde que se completa al acercarse a la maniobra.
// fraccionRecorrida: 0 = acaba de empezar el tramo, 1 = en la maniobra; negativo lo oculta.
// aviso: maniobra inminente; el arco se vuelve naranja, parpadea y la distancia también
// se pinta en naranja.
void ui_mostrar_progreso_maniobra(float fraccionRecorrida, bool aviso);

// Termina la navegación y vuelve al reloj (si se está en la navegación o en la llegada).
void ui_volver_al_reloj();

// Pantalla de "Has llegado" con la hora de llegada. No interrumpe la pantalla de brillo.
void ui_mostrar_llegada(int hora, int minuto);

// ---------------------------------------------------------------- brillo y modo noche

// Refresca la pantalla de brillo con el brillo que se está aplicando y si es de noche.
void ui_refrescar_brillo(int brilloActual, bool deNoche);

// Modo noche: los textos blancos pasan a ámbar suave. Los colores con significado
// (aviso naranja, arco verde, Bluetooth azul) no cambian.
void ui_establecer_modo_noche(bool activado);

// ---------------------------------------------------------------- actualización del firmware

void ui_mostrar_actualizacion(int porcentaje);
void ui_mostrar_error_actualizacion(int codigoError);
