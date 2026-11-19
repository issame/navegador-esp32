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
#define COLOR_ARCO_FONDO 0x2C2C2A
#define COLOR_ARCO_PROGRESO 0x1D9E75
#define COLOR_AVISO 0xEF9F27

// ------------------------------------------------------------------ arco de progreso

// El arco ocupa el borde de la pantalla redonda.
#define DIAMETRO_ARCO 236
#define GROSOR_ARCO 6
#define ESCALA_ARCO 1000 // el arco va de 0 a 1000 (fracción recorrida x 1000)
#define PERIODO_PARPADEO_MS 500

// ------------------------------------------------------------------ flecha

// La flecha llega a 48x48 y se amplía a 72x72 (x1,5) para verla bien de un vistazo.
// Solo se muestran las flechas que manda la app de navegación: la última se conserva
// hasta que llega otra o termina la ruta.
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
static lv_obj_t *arcoProgreso;
static bool avisoActivo = false;

static lv_obj_t *pantallaActualizacion;
static lv_obj_t *etiquetaActualizacion;
static lv_obj_t *barraActualizacion;

// Lo último que se pintó en el reloj, para no redibujar si no cambia nada.
static bool relojPintado = false;
static bool horaSincronizadaPintada = false;
static int horaPintada = -1, minutoPintado = -1, diaPintado = -1;

// ------------------------------------------------------------------ ayudas

// Con distancia a la maniobra, la indicación va debajo de ella y tiene 2 líneas. Sin
// distancia, sube a su hueco y tiene hasta 4 líneas. La flecha no se mueve nunca.
#define ANCHO_INDICACION 176
#define ALTURA_LINEA 20
#define Y_INDICACION_CON_DISTANCIA 178
#define Y_INDICACION_SIN_DISTANCIA 150

static void colocarIndicacion(bool hayDistancia)
{
  int lineas = hayDistancia ? 2 : 4;
  lv_obj_set_height(etiquetaIndicacion, lineas * ALTURA_LINEA + 2);
  lv_obj_align(etiquetaIndicacion, LV_ALIGN_TOP_MID, 0,
               hayDistancia ? Y_INDICACION_CON_DISTANCIA : Y_INDICACION_SIN_DISTANCIA);
}

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

  // Indicación: 176 px de ancho (con "…" si no cabe) para que no se corte por los bordes
  // del círculo. Su posición y altura dependen de si hay distancia (colocarIndicacion).
  etiquetaIndicacion = crearEtiqueta(pantallaNavegacion, &fuente_es_16, COLOR_TEXTO);
  lv_obj_set_width(etiquetaIndicacion, ANCHO_INDICACION);
  lv_label_set_long_mode(etiquetaIndicacion, LV_LABEL_LONG_DOT);
  colocarIndicacion(true);

  // Borde: arco que se completa al acercarse a la maniobra. Empieza arriba (270°) y
  // avanza en el sentido de las agujas del reloj. Solo se muestra, no se puede tocar.
  arcoProgreso = lv_arc_create(pantallaNavegacion);
  lv_obj_set_size(arcoProgreso, DIAMETRO_ARCO, DIAMETRO_ARCO);
  lv_obj_center(arcoProgreso);
  lv_arc_set_rotation(arcoProgreso, 270);
  lv_arc_set_bg_angles(arcoProgreso, 0, 360);
  lv_arc_set_range(arcoProgreso, 0, ESCALA_ARCO);
  lv_arc_set_value(arcoProgreso, 0);
  lv_obj_remove_style(arcoProgreso, NULL, LV_PART_KNOB);
  lv_obj_remove_flag(arcoProgreso, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_width(arcoProgreso, GROSOR_ARCO, LV_PART_MAIN);
  lv_obj_set_style_arc_color(arcoProgreso, lv_color_hex(COLOR_ARCO_FONDO), LV_PART_MAIN);
  lv_obj_set_style_arc_width(arcoProgreso, GROSOR_ARCO, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(arcoProgreso, lv_color_hex(COLOR_ARCO_PROGRESO), LV_PART_INDICATOR);
  lv_obj_add_flag(arcoProgreso, LV_OBJ_FLAG_HIDDEN);
}

static void crearPantallaActualizacion()
{
  pantallaActualizacion = crearPantallaVacia();

  lv_obj_t *titulo = crearEtiqueta(pantallaActualizacion, &fuente_es_20, COLOR_TEXTO);
  lv_label_set_text(titulo, "Actualizando\nfirmware");
  lv_obj_align(titulo, LV_ALIGN_CENTER, 0, -40);

  barraActualizacion = lv_bar_create(pantallaActualizacion);
  lv_obj_set_size(barraActualizacion, 160, 10);
  lv_obj_align(barraActualizacion, LV_ALIGN_CENTER, 0, 10);
  lv_bar_set_range(barraActualizacion, 0, 100);
  lv_obj_set_style_bg_color(barraActualizacion, lv_color_hex(COLOR_ARCO_FONDO), LV_PART_MAIN);
  lv_obj_set_style_bg_color(barraActualizacion, lv_color_hex(COLOR_ARCO_PROGRESO), LV_PART_INDICATOR);

  etiquetaActualizacion = crearEtiqueta(pantallaActualizacion, &fuente_es_16, COLOR_TEXTO_SECUNDARIO);
  lv_obj_set_width(etiquetaActualizacion, 180);
  lv_label_set_long_mode(etiquetaActualizacion, LV_LABEL_LONG_WRAP);
  lv_obj_align(etiquetaActualizacion, LV_ALIGN_CENTER, 0, 46);
}

void ui_init()
{
  crearPantallaReloj();
  crearPantallaNavegacion();
  crearPantallaActualizacion();
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
                           const String &distanciaRestante)
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
  colocarIndicacion(distanciaManiobra.length() > 0);

  // La flecha no se toca aquí: se pinta cuando llega una nueva (ui_mostrar_flecha) y se
  // conserva aunque esta actualización no traiga flecha.

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

// ------------------------------------------------------------------ progreso y aviso de maniobra

// Animación de parpadeo: cambia la opacidad del arco.
static void cambiarOpacidadArco(void *arco, int32_t opacidad)
{
  lv_obj_set_style_arc_opa((lv_obj_t *)arco, (lv_opa_t)opacidad, LV_PART_INDICATOR);
}

static void activarAviso(bool activar)
{
  if (activar == avisoActivo)
    return;
  avisoActivo = activar;

  uint32_t colorArco = activar ? COLOR_AVISO : COLOR_ARCO_PROGRESO;
  lv_obj_set_style_arc_color(arcoProgreso, lv_color_hex(colorArco), LV_PART_INDICATOR);
  lv_obj_set_style_text_color(etiquetaDistanciaManiobra, lv_color_hex(activar ? COLOR_AVISO : COLOR_TEXTO), 0);

  lv_anim_delete(arcoProgreso, cambiarOpacidadArco);
  if (activar)
  {
    lv_anim_t parpadeo;
    lv_anim_init(&parpadeo);
    lv_anim_set_var(&parpadeo, arcoProgreso);
    lv_anim_set_exec_cb(&parpadeo, cambiarOpacidadArco);
    lv_anim_set_values(&parpadeo, LV_OPA_COVER, LV_OPA_20);
    lv_anim_set_duration(&parpadeo, PERIODO_PARPADEO_MS);
    lv_anim_set_reverse_duration(&parpadeo, PERIODO_PARPADEO_MS);
    lv_anim_set_repeat_count(&parpadeo, LV_ANIM_REPEAT_INFINITE);
    lv_anim_start(&parpadeo);
  }
  else
  {
    cambiarOpacidadArco(arcoProgreso, LV_OPA_COVER);
  }
}

void ui_mostrar_progreso_maniobra(float fraccionRecorrida, bool aviso)
{
  if (fraccionRecorrida < 0)
  {
    lv_obj_add_flag(arcoProgreso, LV_OBJ_FLAG_HIDDEN);
    activarAviso(false);
    return;
  }
  if (fraccionRecorrida > 1)
    fraccionRecorrida = 1;
  lv_arc_set_value(arcoProgreso, (int32_t)(fraccionRecorrida * ESCALA_ARCO));
  lv_obj_remove_flag(arcoProgreso, LV_OBJ_FLAG_HIDDEN);
  activarAviso(aviso);
}

// ------------------------------------------------------------------ actualización del firmware

void ui_mostrar_actualizacion(int porcentaje)
{
  lv_bar_set_value(barraActualizacion, porcentaje, LV_ANIM_OFF);
  lv_label_set_text_fmt(etiquetaActualizacion, "%d %%\nNo desconectes el móvil", porcentaje);
  if (lv_screen_active() != pantallaActualizacion)
    lv_screen_load(pantallaActualizacion);
}

void ui_mostrar_error_actualizacion(int codigoError)
{
  lv_label_set_text_fmt(etiquetaActualizacion, "Error %d\nEl firmware anterior sigue instalado", codigoError);
  if (lv_screen_active() != pantallaActualizacion)
    lv_screen_load(pantallaActualizacion);
}
