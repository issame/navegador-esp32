// Navegador ESP32
//
// Reloj e indicaciones de Google Maps u OsmAnd en la placa ESP32-C3 con pantalla redonda de 1,28".
// La app Android "Navegador ESP32" envía la hora y la navegación por Bluetooth LE usando
// el protocolo de la librería ChronosESP32, que se encarga de recibir y decodificar los
// paquetes. Este archivo une las piezas: pantalla y táctil (LovyanGFX), interfaz (LVGL),
// BLE, ajustes de brillo (ajustes.cpp) y actualización del firmware por Bluetooth (ota.cpp).

#include <Arduino.h>
#include <ChronosESP32.h>
#include <NimBLEDevice.h>
#include <lvgl.h>

#include "pantalla.hpp"
#include "arranque.hpp"
#include "ui.h"
#include "ota.h"
#include "distancia.h"
#include "version.h"
#include "ajustes.h"

static PantallaRedonda pantalla;

// Nombre con el que la placa se anuncia por Bluetooth.
static const char *NOMBRE_BLUETOOTH = "Navegador ESP32";

// Enlace BLE con el móvil. Además guarda la hora (hereda de ESP32Time) y la última
// navegación recibida.
static ChronosESP32 enlaceMovil(NOMBRE_BLUETOOTH, CS_240x240_128_CTF);

// LVGL dibuja por franjas en dos búferes de 40 líneas: mientras uno se envía por DMA
// a la pantalla, se dibuja en el otro.
static const uint32_t LINEAS_POR_BUFFER = 40;
static uint16_t bufferDibujo1[ANCHO_PANTALLA * LINEAS_POR_BUFFER];
static uint16_t bufferDibujo2[ANCHO_PANTALLA * LINEAS_POR_BUFFER];

static const uint32_t DURACION_ARRANQUE_MS = 2000;
static const uint32_t INTERVALO_RELOJ_MS = 500;

// A partir de esta distancia a la maniobra se avisa (arco naranja que parpadea).
static const float DISTANCIA_AVISO_METROS = 100;

// Tiempo que se deja ver un error de actualización antes de volver al reloj.
static const uint32_t DURACION_ERROR_ACTUALIZACION_MS = 5000;

// Tiempo que se ve la pantalla de "Has llegado".
static const uint32_t DURACION_LLEGADA_MS = 4000;

// La app avisa de la llegada con una navegación "informativa" cuyo título es este texto.
static const char *MARCA_LLEGADA = "Has llegado";

// La librería BLE llama a sus funciones de aviso desde otra tarea. Ahí solo se activan
// estas banderas; el trabajo con LVGL (que no admite varias tareas) se hace en loop().
static volatile bool hayCambioDeConexion = false;
static volatile bool movilConectado = false;
static volatile bool hayNavegacionNueva = false;
static volatile bool hayFlechaNueva = false;
static volatile bool horaSincronizada = false;
static volatile bool hayBateriaNueva = false;
static volatile int bateriaMovil = 0;
static volatile bool movilCargando = false;

static uint32_t crcFlechaMostrada = 0xFFFFFFFF; // para no redibujar la misma flecha
static uint32_t ultimaActualizacionReloj = 0;

// Tramo actual (de una maniobra a la siguiente), para calcular el arco de progreso:
// se recuerda la distancia a la que empezó y se compara con la que queda.
static String indicacionDelTramo;
static float distanciaInicialDelTramo = -1;

// Viaje actual, para el arco del viaje y el resumen de la llegada.
static bool rutaEnCurso = false;
static uint32_t momentoInicioRuta = 0;
static float distanciaInicialDelViaje = -1; // metros; -1 si la app no la manda
static int ultimoSegundoMostrado = -1;

// Brillo aplicado ahora mismo (%), para no tocar la retroiluminación si no cambia.
static int brilloAplicado = -1;

// Momento en que se mostró "Has llegado" (0 = no se está mostrando).
static uint32_t momentoLlegada = 0;

// Lo último mostrado de la actualización por Bluetooth.
static int porcentajeActualizacionMostrado = -1;
static bool errorActualizacionMostrado = false;
static uint32_t momentoErrorActualizacion = 0;

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

// LVGL pregunta por el táctil: se le pasa si hay un dedo y dónde.
static void leerTactil(lv_indev_t *dispositivo, lv_indev_data_t *datos)
{
  uint16_t x, y;
  if (pantalla.getTouch(&x, &y))
  {
    datos->state = LV_INDEV_STATE_PRESSED;
    datos->point.x = x;
    datos->point.y = y;
  }
  else
  {
    datos->state = LV_INDEV_STATE_RELEASED;
  }
}

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

  lv_indev_t *tactil = lv_indev_create();
  lv_indev_set_type(tactil, LV_INDEV_TYPE_POINTER);
  lv_indev_set_read_cb(tactil, leerTactil);
}

// ------------------------------------------------------------------ brillo y modo noche

// Aplica el brillo que toca (manual o automático según la hora) y el modo noche.
// Se llama cada medio segundo y cuando el usuario cambia algo en la pantalla de brillo.
static void aplicarBrilloYModoNoche()
{
  int hora = horaSincronizada ? enlaceMovil.getHour(true) : 12;
  int minuto = horaSincronizada ? enlaceMovil.getMinute() : 0;
  int brillo = ajustes::brilloActual(horaSincronizada, hora, minuto);
  if (brillo != brilloAplicado)
  {
    brilloAplicado = brillo;
    pantalla.setBrightness(brillo * 255 / 100);
  }
  bool deNoche = horaSincronizada && ajustes::esDeNoche(hora);
  ui_refrescar_brillo(brillo, deNoche);
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
  case CF_PBAT: // batería del móvil: valorA = cargando (1/0), valorB = porcentaje
    movilCargando = valorA == 1;
    bateriaMovil = (int)valorB;
    hayBateriaNueva = true;
    break;
  default:
    break; // el resto de datos de Chronos (clima, notificaciones…) no se usan
  }
}

// ------------------------------------------------------------------ trabajo en loop()

// ¿Se ha llegado a la maniobra? Google Maps quita la distancia en ese momento pero
// mantiene la misma indicación: entonces se muestra "Ahora".
static bool estaEnLaManiobra(const String &distanciaManiobra, const String &indicacion)
{
  return distanciaManiobra.length() == 0 && distanciaInicialDelTramo > 0 && indicacion == indicacionDelTramo;
}

// Calcula cuánto se ha recorrido del tramo actual y si la maniobra es inminente.
static void actualizarProgresoManiobra(const String &distanciaManiobra, const String &indicacion, bool ahora)
{
  if (ahora)
  {
    ui_mostrar_progreso_maniobra(1, true); // arco lleno, naranja y parpadeando
    return;
  }
  float metrosRestantes = distanciaEnMetros(distanciaManiobra);
  if (metrosRestantes < 0)
  {
    indicacionDelTramo = indicacion;
    distanciaInicialDelTramo = -1;
    ui_mostrar_progreso_maniobra(-1, false); // sin distancia no hay progreso que mostrar
    return;
  }

  // Empieza un tramo nuevo si cambia la indicación o si la distancia crece
  // (por ejemplo, porque Maps ha recalculado la ruta).
  bool tramoNuevo = indicacion != indicacionDelTramo || metrosRestantes > distanciaInicialDelTramo;
  if (tramoNuevo)
  {
    indicacionDelTramo = indicacion;
    distanciaInicialDelTramo = metrosRestantes;
  }

  float fraccionRecorrida = 0;
  if (distanciaInicialDelTramo > 0)
    fraccionRecorrida = 1.0f - metrosRestantes / distanciaInicialDelTramo;
  bool maniobraInminente = metrosRestantes <= DISTANCIA_AVISO_METROS;
  ui_mostrar_progreso_maniobra(fraccionRecorrida, maniobraInminente);
}

// Borra lo que se recordaba de la ruta (flecha, tramo y arco) al terminar.
static void olvidarRuta()
{
  crcFlechaMostrada = 0xFFFFFFFF;
  indicacionDelTramo = "";
  distanciaInicialDelTramo = -1;
  rutaEnCurso = false;
  distanciaInicialDelViaje = -1;
  ui_mostrar_flecha(nullptr);
  ui_mostrar_progreso_maniobra(-1, false);
  ui_mostrar_progreso_viaje(-1);
}

// Progreso del viaje completo a partir de la distancia restante (solo si la app la manda).
static void actualizarProgresoViaje(const String &distanciaRestante)
{
  float metrosRestantes = distanciaEnMetros(distanciaRestante);
  if (metrosRestantes < 0)
  {
    ui_mostrar_progreso_viaje(-1);
    return;
  }
  // Si la distancia crece (ruta recalculada), se toma como nuevo total.
  if (distanciaInicialDelViaje <= 0 || metrosRestantes > distanciaInicialDelViaje)
    distanciaInicialDelViaje = metrosRestantes;
  ui_mostrar_progreso_viaje(1.0f - metrosRestantes / distanciaInicialDelViaje);
}

// "24 min · 9,0 km" (la distancia solo si se conoce).
static String resumenDelViaje()
{
  uint32_t minutos = (millis() - momentoInicioRuta + 30000) / 60000;
  if (minutos < 1)
    minutos = 1;
  String resumen = String(minutos) + " min";
  if (distanciaInicialDelViaje > 0)
  {
    char distancia[16];
    if (distanciaInicialDelViaje >= 1000)
      snprintf(distancia, sizeof(distancia), "%.1f km", distanciaInicialDelViaje / 1000.0f);
    else
      snprintf(distancia, sizeof(distancia), "%d m", (int)distanciaInicialDelViaje);
    String texto = distancia;
    texto.replace(".", ","); // coma decimal
    resumen += " · " + texto;
  }
  return resumen;
}

static void mostrarNavegacionRecibida()
{
  Navigation navegacion = enlaceMovil.getNavigation(); // copia: la tarea BLE puede cambiarla
  if (!navegacion.active)
  {
    Serial.println("Navegación terminada");
    olvidarRuta();
    // Si se está mostrando "Has llegado", se vuelve al reloj cuando termine su tiempo.
    if (momentoLlegada == 0)
      ui_volver_al_reloj();
    return;
  }

  // Llegada al destino: la app la avisa con una navegación informativa "Has llegado".
  if (!navegacion.isNavigation && navegacion.title == MARCA_LLEGADA)
  {
    Serial.println("Llegada al destino");
    String resumen = rutaEnCurso ? resumenDelViaje() : String("");
    olvidarRuta();
    ui_mostrar_llegada(enlaceMovil.getHour(true), enlaceMovil.getMinute(), resumen);
    momentoLlegada = millis();
    if (momentoLlegada == 0)
      momentoLlegada = 1; // 0 significa "sin llegada"
    return;
  }
  momentoLlegada = 0; // empieza otra ruta
  if (!rutaEnCurso)
  {
    rutaEnCurso = true;
    momentoInicioRuta = millis();
  }

  String distanciaManiobra = navegacion.title;
  String indicacion = navegacion.directions;
  if (!navegacion.isNavigation)
  {
    // Información general sin maniobra: el título se muestra como indicación.
    indicacion = navegacion.title;
    distanciaManiobra = "";
  }
  // La app manda el tipo de maniobra (para la flecha vectorial) en el campo "velocidad".
  String maniobra = navegacion.speed;
  bool ahora = estaEnLaManiobra(distanciaManiobra, indicacion);
  Serial.printf("Navegación: %s | %s [%s] | %s %s %s\n", ahora ? "Ahora" : distanciaManiobra.c_str(),
                indicacion.c_str(), maniobra.c_str(), navegacion.eta.c_str(), navegacion.duration.c_str(),
                navegacion.distance.c_str());
  ui_mostrar_navegacion(distanciaManiobra, ahora, indicacion, navegacion.eta, navegacion.duration, maniobra);
  actualizarProgresoManiobra(distanciaManiobra, indicacion, ahora);
  actualizarProgresoViaje(navegacion.distance);
}

static void mostrarFlechaRecibida()
{
  Navigation &navegacion = enlaceMovil.getNavigation();
  if (!navegacion.active || navegacion.iconCRC == crcFlechaMostrada)
    return;
  crcFlechaMostrada = navegacion.iconCRC;
  ui_mostrar_flecha(navegacion.icon);
}

// Muestra el progreso de la actualización por Bluetooth y, si falla, el error.
static void atenderActualizacion()
{
  ota::atender(); // reinicia la placa al completarse

  ota::Estado estado = ota::estado();
  if (estado == ota::RECIBIENDO || estado == ota::COMPLETADA)
  {
    errorActualizacionMostrado = false;
    int porcentaje = ota::porcentaje();
    if (porcentaje != porcentajeActualizacionMostrado)
    {
      porcentajeActualizacionMostrado = porcentaje;
      ui_mostrar_actualizacion(porcentaje);
    }
    return;
  }
  if (estado == ota::FALLIDA && !errorActualizacionMostrado)
  {
    errorActualizacionMostrado = true;
    momentoErrorActualizacion = millis();
    porcentajeActualizacionMostrado = -1;
    ui_mostrar_error_actualizacion(ota::ultimoError());
    return;
  }
  // Pasado un rato tras el error, se vuelve al reloj.
  if (errorActualizacionMostrado && momentoErrorActualizacion != 0 &&
      millis() - momentoErrorActualizacion > DURACION_ERROR_ACTUALIZACION_MS)
  {
    momentoErrorActualizacion = 0;
    ui_volver_al_reloj();
  }
}

static void actualizarReloj()
{
  if (!horaSincronizada)
  {
    ui_actualizar_reloj(false, 0, 0, 0, 1, 1);
    return;
  }
  int segundo = enlaceMovil.getSecond();
  if (segundo != ultimoSegundoMostrado)
  {
    ultimoSegundoMostrado = segundo;
    ui_actualizar_segundos(segundo);
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
  Serial.println("Navegador ESP32 v" VERSION_FIRMWARE ": arrancando");

  // Ajustes guardados (brillo y modo ahorro, que fija la velocidad de la CPU).
  ajustes::cargar();

  pantalla.init();
  pantalla.initDMA();
  pantalla.startWrite();
  // Aún no se sabe la hora: se arranca con el brillo manual guardado.
  pantalla.setBrightness(ajustes::brilloManual() * 255 / 100);
  arranque::dibujar(pantalla);
  uint32_t inicioArranque = millis();

  // El Bluetooth arranca mientras se ve la pantalla de arranque. Primero se crea el
  // servidor con el servicio de actualización; después ChronosESP32 añade el suyo al
  // mismo servidor (NimBLE reutiliza el dispositivo y el servidor ya creados) y empieza
  // a anunciarse con los dos.
  NimBLEDevice::init(NOMBRE_BLUETOOTH);
  ota::registrarServicio(NimBLEDevice::createServer());
  enlaceMovil.setConnectionCallback(alCambiarConexion);
  enlaceMovil.setConfigurationCallback(alRecibirDatos);
  enlaceMovil.set24Hour(true);
  enlaceMovil.begin();
  Serial.printf("BLE: %s\n", enlaceMovil.getAddress().c_str());

  iniciarLvgl();
  ui_init(aplicarBrilloYModoNoche);
  ui_mostrar_conexion(false);
  aplicarBrilloYModoNoche();

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
  if (hayBateriaNueva)
  {
    hayBateriaNueva = false;
    ui_mostrar_bateria_movil(bateriaMovil, movilCargando);
  }
  atenderActualizacion();
  if (ota::enCurso())
  {
    // Durante la actualización solo se muestra su progreso; la navegación pendiente se
    // atiende al terminar (las banderas siguen activas).
    delay(5);
    return;
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
    aplicarBrilloYModoNoche();
  }
  if (momentoLlegada != 0 && millis() - momentoLlegada > DURACION_LLEGADA_MS)
  {
    momentoLlegada = 0;
    ui_volver_al_reloj();
  }
  ui_atender(); // vuelta automática desde la pantalla de brillo

  delay(5);
}
