// Interfaz LVGL: pantalla de reloj y pantalla de navegación (ver ui.h).
//
// Distribución pensada para una pantalla redonda de 240 px: los textos largos van en la
// franja central, que es la más ancha del círculo.

#include "ui.h"

// ------------------------------------------------------------------ colores

#define COLOR_FONDO 0x000000
#define COLOR_TEXTO 0xFFFFFF
#define COLOR_TEXTO_SECUNDARIO 0xB4B2A9
#define COLOR_FECHA 0xD3D1C7
#define COLOR_DIA_SEMANA 0x5DCAA5
#define COLOR_BLUETOOTH_CONECTADO 0x378ADD
#define COLOR_BLUETOOTH_DESCONECTADO 0x5F5E5A

// ------------------------------------------------------------------ flecha

// La flecha llega a 48x48 y se amplía a 72x72 (x1,5) para verla bien de un vistazo.
#define LADO_FLECHA_RECIBIDA 48
#define LADO_FLECHA_EN_PANTALLA 72

// Lienzo de 1 bit por píxel (paleta: 0 = negro, 1 = blanco) para la flecha.
LV_DRAW_BUF_DEFINE_STATIC(bufferFlecha, LADO_FLECHA_EN_PANTALLA, LADO_FLECHA_EN_PANTALLA, LV_COLOR_FORMAT_I1);

// ------------------------------------------------------------------ textos

static const char *NOMBRES_DIAS[] = {"domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"};
static const char *NOMBRES_MESES[] = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
                                      "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

// ------------------------------------------------------------------ objetos de la interfaz

static lv_obj_t *pantallaReloj;
static lv_obj_t *etiquetaBluetooth;
static lv_obj_t *etiquetaHora;
static lv_obj_t *etiquetaDiaSemana;
static lv_obj_t *etiquetaFecha;

static lv_obj_t *pantallaNavegacion;
static lv_obj_t *etiquetaLlegada;
static lv_obj_t *etiquetaTiempoYDistancia;
static lv_obj_t *lienzoFlecha;
static lv_obj_t *etiquetaDistanciaManiobra;
static lv_obj_t *etiquetaIndicacion;

// Lo último que se pintó en el reloj, para no redibujar si no cambia nada.
static bool relojPintado = false;
static bool horaSincronizadaPintada = false;
static int horaPintada = -1, minutoPintado = -1, diaPintado = -1;

// ------------------------------------------------------------------ ayudas

static lv_obj_t *crearPantallaVacia()
{
  lv_obj_t *pantalla = lv_obj_create(NULL);
  lv_obj_remove_flag(pantalla, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_style_bg_color(pantalla, lv_color_hex(COLOR_FONDO), 0);
  lv_obj_set_style_bg_opa(pantalla, LV_OPA_COVER, 0);
  return pantalla;
}

static lv_obj_t *crearEtiqueta(lv_obj_t *pantalla, const lv_font_t *fuente, uint32_t color)
{
  lv_obj_t *etiqueta = lv_label_create(pantalla);
  lv_obj_set_style_text_font(etiqueta, fuente, 0);
  lv_obj_set_style_text_color(etiqueta, lv_color_hex(color), 0);
  lv_obj_set_style_text_align(etiqueta, LV_TEXT_ALIGN_CENTER, 0);
  lv_label_set_text(etiqueta, "");
  return etiqueta;
}

// ------------------------------------------------------------------ creación

static void crearPantallaReloj()
{
  pantallaReloj = crearPantallaVacia();

  // Arriba: icono de Bluetooth (de la fuente integrada de LVGL, que incluye los símbolos).
  etiquetaBluetooth = crearEtiqueta(pantallaReloj, &lv_font_montserrat_20, COLOR_BLUETOOTH_DESCONECTADO);
  lv_label_set_text(etiquetaBluetooth, LV_SYMBOL_BLUETOOTH);
  lv_obj_align(etiquetaBluetooth, LV_ALIGN_TOP_MID, 0, 30);

  // Centro: hora en grande.
  etiquetaHora = crearEtiqueta(pantallaReloj, &fuente_reloj_64, COLOR_TEXTO);
  lv_obj_align(etiquetaHora, LV_ALIGN_CENTER, 0, -14);

  // Debajo: día de la semana y fecha.
  etiquetaDiaSemana = crearEtiqueta(pantallaReloj, &fuente_es_20, COLOR_DIA_SEMANA);
  lv_obj_align(etiquetaDiaSemana, LV_ALIGN_CENTER, 0, 36);

  etiquetaFecha = crearEtiqueta(pantallaReloj, &fuente_es_20, COLOR_FECHA);
  lv_obj_align(etiquetaFecha, LV_ALIGN_CENTER, 0, 62);
}

static void crearPantallaNavegacion()
{
  pantallaNavegacion = crearPantallaVacia();

  // Arriba: hora de llegada y, debajo, tiempo y distancia restantes.
  etiquetaLlegada = crearEtiqueta(pantallaNavegacion, &fuente_es_16, COLOR_TEXTO_SECUNDARIO);
  lv_obj_align(etiquetaLlegada, LV_ALIGN_TOP_MID, 0, 26);

  etiquetaTiempoYDistancia = crearEtiqueta(pantallaNavegacion, &fuente_es_16, COLOR_TEXTO_SECUNDARIO);
  lv_obj_align(etiquetaTiempoYDistancia, LV_ALIGN_TOP_MID, 0, 46);

  // Centro: flecha de la maniobra, blanca sobre negro. Oculta hasta que llegue una.
  LV_DRAW_BUF_INIT_STATIC(bufferFlecha);
  lienzoFlecha = lv_canvas_create(pantallaNavegacion);
  lv_canvas_set_draw_buf(lienzoFlecha, &bufferFlecha);
  lv_canvas_set_palette(lienzoFlecha, 0, lv_color32_make(0, 0, 0, 255));
  lv_canvas_set_palette(lienzoFlecha, 1, lv_color32_make(255, 255, 255, 255));
  lv_obj_align(lienzoFlecha, LV_ALIGN_TOP_MID, 0, 70);
  lv_obj_add_flag(lienzoFlecha, LV_OBJ_FLAG_HIDDEN);

  // Distancia a la maniobra, en grande.
  etiquetaDistanciaManiobra = crearEtiqueta(pantallaNavegacion, &fuente_es_28, COLOR_TEXTO);
  lv_obj_align(etiquetaDistanciaManiobra, LV_ALIGN_TOP_MID, 0, 144);

  // Indicación: 176 px de ancho y como mucho 2 líneas (con "…" si no cabe) para que
  // no se corte por los bordes del círculo.
  etiquetaIndicacion = crearEtiqueta(pantallaNavegacion, &fuente_es_16, COLOR_TEXTO);
  lv_obj_set_width(etiquetaIndicacion, 176);
  lv_obj_set_height(etiquetaIndicacion, 42);
  lv_label_set_long_mode(etiquetaIndicacion, LV_LABEL_LONG_DOT);
  lv_obj_align(etiquetaIndicacion, LV_ALIGN_TOP_MID, 0, 178);
}

void ui_init()
{
  crearPantallaReloj();
  crearPantallaNavegacion();
  ui_actualizar_reloj(false, 0, 0, 0, 1, 1);
  lv_screen_load(pantallaReloj);
}

// ------------------------------------------------------------------ reloj

void ui_actualizar_reloj(bool horaSincronizada, int hora, int minuto, int diaSemana, int dia, int mes)
{
  bool sinCambios = relojPintado && horaSincronizada == horaSincronizadaPintada &&
                    hora == horaPintada && minuto == minutoPintado && dia == diaPintado;
  if (sinCambios)
    return;
  relojPintado = true;
  horaSincronizadaPintada = horaSincronizada;
  horaPintada = hora;
  minutoPintado = minuto;
  diaPintado = dia;

  if (!horaSincronizada)
  {
    lv_label_set_text(etiquetaHora, "--:--");
    lv_label_set_text(etiquetaDiaSemana, "");
    lv_label_set_text(etiquetaFecha, "Conecta el móvil");
    return;
  }
  lv_label_set_text_fmt(etiquetaHora, "%02d:%02d", hora, minuto);
  lv_label_set_text(etiquetaDiaSemana, NOMBRES_DIAS[constrain(diaSemana, 0, 6)]);
  lv_label_set_text_fmt(etiquetaFecha, "%d de %s", dia, NOMBRES_MESES[constrain(mes, 1, 12) - 1]);
}

void ui_mostrar_conexion(bool conectado)
{
  uint32_t color = conectado ? COLOR_BLUETOOTH_CONECTADO : COLOR_BLUETOOTH_DESCONECTADO;
  lv_obj_set_style_text_color(etiquetaBluetooth, lv_color_hex(color), 0);
}

void ui_volver_al_reloj()
{
  if (lv_screen_active() != pantallaReloj)
    lv_screen_load_anim(pantallaReloj, LV_SCR_LOAD_ANIM_FADE_IN, 300, 0, false);
}

// ------------------------------------------------------------------ navegación

void ui_mostrar_navegacion(const String &distanciaManiobra, const String &indicacion,
                           const String &horaLlegada, const String &tiempoRestante,
                           const String &distanciaRestante, bool hayFlecha)
{
  // "Llegada 14:51". Si el texto ya trae la palabra "Llegada" se deja tal cual.
  String textoLlegada = horaLlegada;
  textoLlegada.trim();
  bool yaTraeLlegada = textoLlegada.startsWith("Llegada") || textoLlegada.startsWith("llegada");
  if (textoLlegada.length() > 0 && !yaTraeLlegada)
    textoLlegada = String("Llegada ") + textoLlegada;
  lv_label_set_text(etiquetaLlegada, textoLlegada.c_str());

  // "19 min · 8,4 km" (o solo lo que haya).
  String textoTiempoYDistancia = tiempoRestante;
  if (distanciaRestante.length() > 0)
  {
    if (textoTiempoYDistancia.length() > 0)
      textoTiempoYDistancia += " · ";
    textoTiempoYDistancia += distanciaRestante;
  }
  lv_label_set_text(etiquetaTiempoYDistancia, textoTiempoYDistancia.c_str());

  lv_label_set_text(etiquetaDistanciaManiobra, distanciaManiobra.c_str());
  lv_label_set_text(etiquetaIndicacion, indicacion.c_str());

  // La flecha se muestra cuando llega (ui_mostrar_flecha); aquí solo se oculta si no hay.
  if (!hayFlecha)
    lv_obj_add_flag(lienzoFlecha, LV_OBJ_FLAG_HIDDEN);

  if (lv_screen_active() != pantallaNavegacion)
    lv_screen_load_anim(pantallaNavegacion, LV_SCR_LOAD_ANIM_FADE_IN, 300, 0, false);
}

void ui_mostrar_flecha(const uint8_t *bitsFlecha)
{
  if (bitsFlecha == nullptr)
  {
    lv_obj_add_flag(lienzoFlecha, LV_OBJ_FLAG_HIDDEN);
    return;
  }

  // Ampliación por "vecino más próximo": cada píxel de pantalla toma el valor del píxel
  // de origen que le corresponde. Se escribe directamente en el búfer del lienzo, que
  // usa el mismo orden de bits (el bit alto es el píxel de la izquierda).
  for (int filaPantalla = 0; filaPantalla < LADO_FLECHA_EN_PANTALLA; filaPantalla++)
  {
    uint8_t *bytesFila = (uint8_t *)lv_draw_buf_goto_xy(&bufferFlecha, 0, filaPantalla);
    memset(bytesFila, 0, (LADO_FLECHA_EN_PANTALLA + 7) / 8);
    int filaOrigen = filaPantalla * LADO_FLECHA_RECIBIDA / LADO_FLECHA_EN_PANTALLA;

    for (int columnaPantalla = 0; columnaPantalla < LADO_FLECHA_EN_PANTALLA; columnaPantalla++)
    {
      int columnaOrigen = columnaPantalla * LADO_FLECHA_RECIBIDA / LADO_FLECHA_EN_PANTALLA;
      int indicePixelOrigen = filaOrigen * LADO_FLECHA_RECIBIDA + columnaOrigen;
      bool encendido = (bitsFlecha[indicePixelOrigen / 8] >> (7 - indicePixelOrigen % 8)) & 1;
      if (encendido)
        bytesFila[columnaPantalla / 8] |= 0x80 >> (columnaPantalla % 8);
    }
  }
  lv_obj_remove_flag(lienzoFlecha, LV_OBJ_FLAG_HIDDEN);
  lv_obj_invalidate(lienzoFlecha); // avisar a LVGL de que hay que redibujarla
}
