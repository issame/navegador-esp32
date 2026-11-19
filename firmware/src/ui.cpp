#include "ui.h"

// Colores
#define C_FONDO 0x000000
#define C_TEXTO 0xFFFFFF
#define C_SECUNDARIO 0xB4B2A9
#define C_FECHA 0xD3D1C7
#define C_VERDE 0x5DCAA5
#define C_AZUL 0x378ADD
#define C_APAGADO 0x5F5E5A

// Flecha: 48x48 de origen, ampliada a 72x72 (x1,5) para verla bien de un vistazo.
#define FLECHA_ORIGEN 48
#define FLECHA_LADO 72

static const char *DIAS[] = {"domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"};
static const char *MESES[] = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
                              "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

static lv_obj_t *pReloj, *pNav;
static lv_obj_t *lblBt, *lblHora, *lblDiaSemana, *lblFecha;
static lv_obj_t *lblLlegada, *lblResto, *cvFlecha, *lblManiobra, *lblIndicacion;

LV_DRAW_BUF_DEFINE_STATIC(bufFlecha, FLECHA_LADO, FLECHA_LADO, LV_COLOR_FORMAT_I1);

// Último estado pintado, para no redibujar si no cambia nada.
static int ultMinuto = -1, ultHora = -1, ultDia = -1;
static bool ultSincronizada = false;

static lv_obj_t *pantalla()
{
  lv_obj_t *s = lv_obj_create(NULL);
  lv_obj_remove_flag(s, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_style_bg_color(s, lv_color_hex(C_FONDO), 0);
  lv_obj_set_style_bg_opa(s, LV_OPA_COVER, 0);
  return s;
}

static lv_obj_t *etiqueta(lv_obj_t *padre, const lv_font_t *fuente, uint32_t color)
{
  lv_obj_t *l = lv_label_create(padre);
  lv_obj_set_style_text_font(l, fuente, 0);
  lv_obj_set_style_text_color(l, lv_color_hex(color), 0);
  lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
  lv_label_set_text(l, "");
  return l;
}

static void crearReloj()
{
  pReloj = pantalla();

  lblBt = etiqueta(pReloj, &lv_font_montserrat_20, C_APAGADO);
  lv_label_set_text(lblBt, LV_SYMBOL_BLUETOOTH);
  lv_obj_align(lblBt, LV_ALIGN_TOP_MID, 0, 30);

  lblHora = etiqueta(pReloj, &fuente_reloj_64, C_TEXTO);
  lv_obj_align(lblHora, LV_ALIGN_CENTER, 0, -14);

  lblDiaSemana = etiqueta(pReloj, &fuente_es_20, C_VERDE);
  lv_obj_align(lblDiaSemana, LV_ALIGN_CENTER, 0, 36);

  lblFecha = etiqueta(pReloj, &fuente_es_20, C_FECHA);
  lv_obj_align(lblFecha, LV_ALIGN_CENTER, 0, 62);
}

static void crearNavegacion()
{
  pNav = pantalla();

  // Arriba: hora de llegada y, debajo, tiempo y distancia restantes.
  lblLlegada = etiqueta(pNav, &fuente_es_16, C_SECUNDARIO);
  lv_obj_align(lblLlegada, LV_ALIGN_TOP_MID, 0, 26);

  lblResto = etiqueta(pNav, &fuente_es_16, C_SECUNDARIO);
  lv_obj_align(lblResto, LV_ALIGN_TOP_MID, 0, 46);

  // Centro: flecha de la maniobra (blanco sobre negro).
  LV_DRAW_BUF_INIT_STATIC(bufFlecha);
  cvFlecha = lv_canvas_create(pNav);
  lv_canvas_set_draw_buf(cvFlecha, &bufFlecha);
  lv_canvas_set_palette(cvFlecha, 0, lv_color32_make(0, 0, 0, 255));
  lv_canvas_set_palette(cvFlecha, 1, lv_color32_make(255, 255, 255, 255));
  lv_obj_align(cvFlecha, LV_ALIGN_TOP_MID, 0, 70);
  lv_obj_add_flag(cvFlecha, LV_OBJ_FLAG_HIDDEN);

  // Distancia a la maniobra, en grande.
  lblManiobra = etiqueta(pNav, &fuente_es_28, C_TEXTO);
  lv_obj_align(lblManiobra, LV_ALIGN_TOP_MID, 0, 144);

  // Indicación: como mucho 2 líneas, dentro de la parte visible del círculo.
  lblIndicacion = etiqueta(pNav, &fuente_es_16, C_TEXTO);
  lv_obj_set_width(lblIndicacion, 176);
  lv_obj_set_height(lblIndicacion, 42);
  lv_label_set_long_mode(lblIndicacion, LV_LABEL_LONG_DOT);
  lv_obj_align(lblIndicacion, LV_ALIGN_TOP_MID, 0, 178);
}

void ui_init()
{
  crearReloj();
  crearNavegacion();
  ui_reloj(false, 0, 0, 0, 1, 1);
  lv_screen_load(pReloj);
}

void ui_reloj(bool sincronizada, int hora, int minuto, int diaSemana, int dia, int mes)
{
  if (sincronizada == ultSincronizada && minuto == ultMinuto && hora == ultHora && dia == ultDia && ultMinuto != -1)
    return;
  ultSincronizada = sincronizada;
  ultMinuto = minuto;
  ultHora = hora;
  ultDia = dia;

  if (!sincronizada)
  {
    lv_label_set_text(lblHora, "--:--");
    lv_label_set_text(lblDiaSemana, "");
    lv_label_set_text(lblFecha, "Conecta el móvil");
    return;
  }
  lv_label_set_text_fmt(lblHora, "%02d:%02d", hora, minuto);
  lv_label_set_text(lblDiaSemana, DIAS[constrain(diaSemana, 0, 6)]);
  lv_label_set_text_fmt(lblFecha, "%d de %s", dia, MESES[constrain(mes, 1, 12) - 1]);
}

void ui_conexion(bool conectado)
{
  lv_obj_set_style_text_color(lblBt, lv_color_hex(conectado ? C_AZUL : C_APAGADO), 0);
}

void ui_navegacion(const String &maniobra, const String &indicacion,
                   const String &llegada, const String &duracion, const String &distancia,
                   bool conIcono)
{
  // "Llegada 14:51" (si el texto ya trae "Llegada", se deja tal cual).
  String l = llegada;
  l.trim();
  if (l.length() > 0 && !l.startsWith("Llegada") && !l.startsWith("llegada"))
    l = String("Llegada ") + l;
  lv_label_set_text(lblLlegada, l.c_str());

  String resto = duracion;
  if (distancia.length() > 0)
  {
    if (resto.length() > 0)
      resto += " · ";
    resto += distancia;
  }
  lv_label_set_text(lblResto, resto.c_str());

  lv_label_set_text(lblManiobra, maniobra.c_str());
  lv_label_set_text(lblIndicacion, indicacion.c_str());

  if (!conIcono)
    lv_obj_add_flag(cvFlecha, LV_OBJ_FLAG_HIDDEN);

  if (lv_screen_active() != pNav)
    lv_screen_load_anim(pNav, LV_SCR_LOAD_ANIM_FADE_IN, 300, 0, false);
}

void ui_flecha(const uint8_t *bits)
{
  if (bits == nullptr)
  {
    lv_obj_add_flag(cvFlecha, LV_OBJ_FLAG_HIDDEN);
    return;
  }
  // Ampliación por vecino más próximo de 48 a 72 px, escribiendo los bits directamente.
  for (int y = 0; y < FLECHA_LADO; y++)
  {
    uint8_t *fila = (uint8_t *)lv_draw_buf_goto_xy(&bufFlecha, 0, y);
    memset(fila, 0, (FLECHA_LADO + 7) / 8);
    int sy = y * FLECHA_ORIGEN / FLECHA_LADO;
    for (int x = 0; x < FLECHA_LADO; x++)
    {
      int sx = x * FLECHA_ORIGEN / FLECHA_LADO;
      int i = sy * FLECHA_ORIGEN + sx;
      if ((bits[i / 8] >> (7 - (i % 8))) & 1)
        fila[x / 8] |= 0x80 >> (x % 8);
    }
  }
  lv_obj_remove_flag(cvFlecha, LV_OBJ_FLAG_HIDDEN);
  lv_obj_invalidate(cvFlecha);
}

void ui_mostrar_reloj()
{
  if (lv_screen_active() != pReloj)
    lv_screen_load_anim(pReloj, LV_SCR_LOAD_ANIM_FADE_IN, 300, 0, false);
}
