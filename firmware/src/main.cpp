// Navegador ESP32
//
// Reloj e indicaciones de Google Maps en la placa ESP32-C3 con pantalla redonda de 1,28".
// La app Android "Navegador ESP32" envía la hora y la navegación por Bluetooth LE usando
// el protocolo de la librería ChronosESP32, que se encarga de recibir y decodificar los
// paquetes. Este archivo une las tres piezas: pantalla (LovyanGFX), interfaz (LVGL) y BLE.

#include <Arduino.h>
#include <ChronosESP32.h>
#include <lvgl.h>

#include "pantalla.hpp"
#include "arranque.hpp"
#include "ui.h"

static PantallaRedonda pantalla;

// Enlace BLE con el móvil. Además guarda la hora (hereda de ESP32Time) y la última
// navegación recibida.
static ChronosESP32 enlaceMovil("Navegador ESP32", CS_240x240_128_CTF);

// LVGL dibuja por franjas en dos búferes de 40 líneas: mientras uno se envía por DMA
// a la pantalla, se dibuja en el otro.
static const uint32_t LINEAS_POR_BUFFER = 40;
static uint16_t bufferDibujo1[ANCHO_PANTALLA * LINEAS_POR_BUFFER];
static uint16_t bufferDibujo2[ANCHO_PANTALLA * LINEAS_POR_BUFFER];

static const uint8_t BRILLO_PANTALLA = 200; // 0 … 255
static const uint32_t DURACION_ARRANQUE_MS = 2000;
static const uint32_t INTERVALO_RELOJ_MS = 500;

// La librería BLE llama a sus funciones de aviso desde otra tarea. Ahí solo se activan
// estas banderas; el trabajo con LVGL (que no admite varias tareas) se hace en loop().
static volatile bool hayCambioDeConexion = false;
static volatile bool movilConectado = false;
static volatile bool hayNavegacionNueva = false;
static volatile bool hayFlechaNueva = false;
static volatile bool horaSincronizada = false;

static uint32_t crcFlechaMostrada = 0xFFFFFFFF; // para no redibujar la misma flecha
static uint32_t ultimaActualizacionReloj = 0;

// ------------------------------------------------------------------ LVGL ↔ pantalla

// LVGL ha terminado de dibujar una zona: se envía a la pantalla por DMA.
static void enviarZonaAPantalla(lv_display_t *display, const lv_area_t *zona, uint8_t *pixeles)
{
  uint32_t ancho = lv_area_get_width(zona);
  uint32_t alto = lv_area_get_height(zona);
  lv_draw_sw_rgb565_swap(pixeles, ancho * alto); // la pantalla espera el byte alto primero
  if (pantalla.getStartCount() == 0)
    pantalla.endWrite();
  pantalla.pushImageDMA(zona->x1, zona->y1, ancho, alto, (uint16_t *)pixeles);
  lv_display_flush_ready(display);
}

// El GC9A01 da problemas con zonas que empiezan o acaban en coordenada impar:
// se amplía cada zona a coordenadas pares.
static void ajustarZonaACoordenadasPares(lv_event_t *evento)
{
  lv_area_t *zona = lv_event_get_invalidated_area(evento);
  zona->x1 = (zona->x1 >> 1) << 1;
  zona->y1 = (zona->y1 >> 1) << 1;
  zona->x2 = ((zona->x2 >> 1) << 1) + 1;
  zona->y2 = ((zona->y2 >> 1) << 1) + 1;
}

static uint32_t milisegundosDesdeArranque() { return millis(); }

static void iniciarLvgl()
{
  lv_init();
  lv_tick_set_cb(milisegundosDesdeArranque);
  lv_display_t *display = lv_display_create(ANCHO_PANTALLA, ALTO_PANTALLA);
  lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
  lv_display_set_flush_cb(display, enviarZonaAPantalla);
  lv_display_set_buffers(display, bufferDibujo1, bufferDibujo2, sizeof(bufferDibujo1),
                         LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_add_event_cb(display, ajustarZonaACoordenadasPares, LV_EVENT_INVALIDATE_AREA, NULL);
}

// ------------------------------------------------------------------ avisos de la librería BLE
// (se ejecutan en la tarea de Bluetooth: solo activan banderas)

static void alCambiarConexion(bool conectado)
{
  movilConectado = conectado;
  hayCambioDeConexion = true;
}

static void alRecibirDatos(Config tipo, uint32_t valorA, uint32_t valorB)
{
  switch (tipo)
  {
  case CF_TIME:
    if (valorA == 1) // la librería avisa antes (0) y después (1) de poner la hora
      horaSincronizada = true;
    break;
  case CF_NAV_DATA:
    hayNavegacionNueva = true;
    break;
  case CF_NAV_ICON:
    if (valorA == 2) // la flecha llega en 3 trozos (0, 1, 2): este es el último
      hayFlechaNueva = true;
    break;
  default:
    break; // el resto de datos de Chronos (clima, notificaciones…) no se usan
  }
}

// ------------------------------------------------------------------ trabajo en loop()

static void mostrarNavegacionRecibida()
{
  Navigation navegacion = enlaceMovil.getNavigation(); // copia: la tarea BLE puede cambiarla
  if (!navegacion.active)
  {
    Serial.println("Navegación terminada");
    crcFlechaMostrada = 0xFFFFFFFF;
    ui_mostrar_flecha(nullptr);
    ui_volver_al_reloj();
    return;
  }

  String distanciaManiobra = navegacion.title;
  String indicacion = navegacion.directions;
  if (!navegacion.isNavigation)
  {
    // Información general sin maniobra: el título se muestra como indicación.
    indicacion = navegacion.title;
    distanciaManiobra = "";
  }
  Serial.printf("Navegación: %s | %s | %s %s %s\n", distanciaManiobra.c_str(), indicacion.c_str(),
                navegacion.eta.c_str(), navegacion.duration.c_str(), navegacion.distance.c_str());
  ui_mostrar_navegacion(distanciaManiobra, indicacion, navegacion.eta, navegacion.duration,
                        navegacion.distance, navegacion.hasIcon);
}

static void mostrarFlechaRecibida()
{
  Navigation &navegacion = enlaceMovil.getNavigation();
  if (!navegacion.active || navegacion.iconCRC == crcFlechaMostrada)
    return;
  crcFlechaMostrada = navegacion.iconCRC;
  ui_mostrar_flecha(navegacion.icon);
}

static void actualizarReloj()
{
  if (!horaSincronizada)
  {
    ui_actualizar_reloj(false, 0, 0, 0, 1, 1);
    return;
  }
  ui_actualizar_reloj(true,
                      enlaceMovil.getHour(true), // formato 24 h
                      enlaceMovil.getMinute(),
                      enlaceMovil.getDayofWeek(), // 0 = domingo
                      enlaceMovil.getDay(),
                      enlaceMovil.getMonth() + 1); // la librería cuenta los meses desde 0
}

// ------------------------------------------------------------------ Arduino

void setup()
{
  Serial.begin(115200);
  Serial.println("Navegador ESP32: arrancando");

  pantalla.init();
  pantalla.initDMA();
  pantalla.startWrite();
  pantalla.setBrightness(BRILLO_PANTALLA);
  arranque::dibujar(pantalla);
  uint32_t inicioArranque = millis();

  // El Bluetooth arranca mientras se ve la pantalla de arranque.
  enlaceMovil.setConnectionCallback(alCambiarConexion);
  enlaceMovil.setConfigurationCallback(alRecibirDatos);
  enlaceMovil.set24Hour(true);
  enlaceMovil.begin();
  Serial.printf("BLE: %s\n", enlaceMovil.getAddress().c_str());

  iniciarLvgl();
  ui_init();
  ui_mostrar_conexion(false);

  // Completar los 2 s de la pantalla de arranque sin desatender el Bluetooth.
  while (millis() - inicioArranque < DURACION_ARRANQUE_MS)
  {
    enlaceMovil.loop();
    delay(10);
  }
}

void loop()
{
  lv_timer_handler(); // LVGL redibuja lo que haya cambiado
  enlaceMovil.loop(); // tareas periódicas de la librería BLE

  if (hayCambioDeConexion)
  {
    hayCambioDeConexion = false;
    Serial.println(movilConectado ? "Móvil conectado" : "Móvil desconectado");
    ui_mostrar_conexion(movilConectado);
  }
  if (hayNavegacionNueva)
  {
    hayNavegacionNueva = false;
    mostrarNavegacionRecibida();
  }
  if (hayFlechaNueva)
  {
    hayFlechaNueva = false;
    mostrarFlechaRecibida();
  }
  if (millis() - ultimaActualizacionReloj >= INTERVALO_RELOJ_MS)
  {
    ultimaActualizacionReloj = millis();
    actualizarReloj();
  }

  delay(5);
}
