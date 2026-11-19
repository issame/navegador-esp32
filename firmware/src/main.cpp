// Navegador ESP32
// Reloj + indicaciones de Google Maps en la placa ESP32-C3 con pantalla redonda de 1,28".
// La app Android "Navegador ESP32" envía la hora y la navegación por BLE usando el
// protocolo de la librería ChronosESP32.

#include <Arduino.h>
#include <ChronosESP32.h>
#include <lvgl.h>

#include "pantalla.hpp"
#include "arranque.hpp"
#include "ui.h"

static LGFX tft;
static ChronosESP32 reloj("Navegador ESP32", CS_240x240_128_CTF);

// LVGL dibuja en dos búferes de 40 líneas.
static const uint32_t LINEAS_BUFFER = 40;
static uint16_t buffer1[SCREEN_WIDTH * LINEAS_BUFFER];
static uint16_t buffer2[SCREEN_WIDTH * LINEAS_BUFFER];

// Las funciones de la librería BLE se llaman desde otra tarea: solo se activan
// banderas aquí y el trabajo con LVGL se hace en loop().
static volatile bool hayConexionNueva = false;
static volatile bool conectado = false;
static volatile bool hayNavegacionNueva = false;
static volatile bool hayFlechaNueva = false;
static volatile bool horaSincronizada = false;

static uint32_t crcFlecha = 0xFFFFFFFF;
static uint32_t ultimoReloj = 0;

// ------------------------------------------------------------------ LVGL

static void volcar(lv_display_t *disp, const lv_area_t *area, uint8_t *px)
{
  uint32_t w = lv_area_get_width(area);
  uint32_t h = lv_area_get_height(area);
  lv_draw_sw_rgb565_swap(px, w * h);
  if (tft.getStartCount() == 0)
    tft.endWrite();
  tft.pushImageDMA(area->x1, area->y1, w, h, (uint16_t *)px);
  lv_display_flush_ready(disp);
}

// El GC9A01 necesita zonas con coordenadas pares.
static void redondear(lv_event_t *e)
{
  lv_area_t *a = lv_event_get_invalidated_area(e);
  a->x1 = (a->x1 >> 1) << 1;
  a->y1 = (a->y1 >> 1) << 1;
  a->x2 = ((a->x2 >> 1) << 1) + 1;
  a->y2 = ((a->y2 >> 1) << 1) + 1;
}

static uint32_t milisegundos() { return millis(); }

// ------------------------------------------------------------------ BLE

static void alCambiarConexion(bool estado)
{
  conectado = estado;
  hayConexionNueva = true;
}

static void alRecibirAjuste(Config tipo, uint32_t a, uint32_t b)
{
  switch (tipo)
  {
  case CF_TIME:
    if (a == 1)
      horaSincronizada = true;
    break;
  case CF_NAV_DATA:
    hayNavegacionNueva = true;
    break;
  case CF_NAV_ICON:
    if (a == 2) // llegó el último de los 3 trozos
      hayFlechaNueva = true;
    break;
  default:
    break;
  }
}

static void procesarNavegacion()
{
  Navigation nav = reloj.getNavigation();
  if (!nav.active)
  {
    Serial.println("Navegación terminada");
    crcFlecha = 0xFFFFFFFF;
    ui_flecha(nullptr);
    ui_mostrar_reloj();
    return;
  }
  String maniobra = nav.title;
  String indicacion = nav.directions;
  if (!nav.isNavigation)
  {
    // Información general (sin maniobra): el título va como indicación.
    indicacion = nav.title;
    maniobra = "";
  }
  Serial.printf("Navegación: %s | %s | %s %s %s\n", maniobra.c_str(), indicacion.c_str(),
                nav.eta.c_str(), nav.duration.c_str(), nav.distance.c_str());
  ui_navegacion(maniobra, indicacion, nav.eta, nav.duration, nav.distance, nav.hasIcon);
}

static void procesarFlecha()
{
  Navigation &nav = reloj.getNavigation();
  if (!nav.active || nav.iconCRC == crcFlecha)
    return;
  crcFlecha = nav.iconCRC;
  ui_flecha(nav.icon);
}

static void actualizarReloj()
{
  if (!horaSincronizada)
  {
    ui_reloj(false, 0, 0, 0, 1, 1);
    return;
  }
  ui_reloj(true, reloj.getHour(true), reloj.getMinute(), reloj.getDayofWeek(),
           reloj.getDay(), reloj.getMonth() + 1);
}

// ------------------------------------------------------------------ arranque

void setup()
{
  Serial.begin(115200);
  Serial.println("Navegador ESP32: arrancando");

  tft.init();
  tft.initDMA();
  tft.startWrite();
  tft.setBrightness(200);
  arranque::dibujar(tft);
  uint32_t inicio = millis();

  // BLE mientras se ve la pantalla de arranque.
  reloj.setConnectionCallback(alCambiarConexion);
  reloj.setConfigurationCallback(alRecibirAjuste);
  reloj.set24Hour(true);
  reloj.begin();
  Serial.printf("BLE: %s\n", reloj.getAddress().c_str());

  lv_init();
  lv_tick_set_cb(milisegundos);
  lv_display_t *disp = lv_display_create(SCREEN_WIDTH, SCREEN_HEIGHT);
  lv_display_set_color_format(disp, LV_COLOR_FORMAT_RGB565);
  lv_display_set_flush_cb(disp, volcar);
  lv_display_set_buffers(disp, buffer1, buffer2, sizeof(buffer1), LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_add_event_cb(disp, redondear, LV_EVENT_INVALIDATE_AREA, NULL);

  ui_init();
  ui_conexion(false);

  while (millis() - inicio < 2000)
  {
    reloj.loop();
    delay(10);
  }
  lv_obj_invalidate(lv_screen_active()); // sustituye la pantalla de arranque
}

void loop()
{
  lv_timer_handler();
  reloj.loop();

  if (hayConexionNueva)
  {
    hayConexionNueva = false;
    Serial.println(conectado ? "Móvil conectado" : "Móvil desconectado");
    ui_conexion(conectado);
  }
  if (hayNavegacionNueva)
  {
    hayNavegacionNueva = false;
    procesarNavegacion();
  }
  if (hayFlechaNueva)
  {
    hayFlechaNueva = false;
    procesarFlecha();
  }
  if (millis() - ultimoReloj >= 500)
  {
    ultimoReloj = millis();
    actualizarReloj();
  }

  delay(5);
}
