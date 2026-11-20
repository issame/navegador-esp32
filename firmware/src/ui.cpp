// Interfaz LVGL: reloj, navegación, brillo, llegada y actualización (ver ui.h).
//
// Distribución pensada para una pantalla redonda de 240 px: los textos largos van en la
// franja central, que es la más ancha del círculo.

#include "ui.h"
#include "ajustes.h"

// ------------------------------------------------------------------ colores

#define COLOR_FONDO 0x000000
#define COLOR_TEXTO 0xFFFFFF
#define COLOR_TEXTO_SECUNDARIO 0xB4B2A9
#define COLOR_FECHA 0xD3D1C7
#define COLOR_DIA_SEMANA 0x5DCAA5
#define COLOR_BLUETOOTH_CONECTADO 0x378ADD
#define COLOR_APAGADO 0x5F5E5A
#define COLOR_ARCO_FONDO 0x2C2C2A
#define COLOR_ARCO_PROGRESO 0x1D9E75
#define COLOR_AVISO 0xEF9F27
#define COLOR_SOL 0xFAC775
#define COLOR_LUNA 0x85B7EB
#define COLOR_ACTIVADO 0x1D9E75

// Modo noche: los blancos y grises de texto pasan a tonos ámbar.
#define COLOR_TEXTO_NOCHE 0xFAC775
#define COLOR_SECUNDARIO_NOCHE 0xBA7517

// ------------------------------------------------------------------ medidas

// Arco de progreso de la maniobra, en el borde de la pantalla.
#define DIAMETRO_ARCO 236
#define GROSOR_ARCO 6
#define ESCALA_ARCO 1000 // el arco va de 0 a 1000 (fracción recorrida x 1000)
#define PERIODO_PARPADEO_MS 500

// Rueda de brillo: 270° (de 135° a 45°), abierta por abajo.
#define DIAMETRO_RUEDA 226
#define GROSOR_RUEDA 10

// Indicación: con distancia a la maniobra va debajo de ella y tiene 2 líneas. Sin
// distancia, sube a su hueco y tiene hasta 4 líneas. La flecha no se mueve nunca.
#define ANCHO_INDICACION 176
#define ALTURA_LINEA 20
#define Y_INDICACION_CON_DISTANCIA 178
#define Y_INDICACION_SIN_DISTANCIA 150

// La flecha llega a 48x48 y se amplía a 72x72 (x1,5) para verla bien de un vistazo.
// Solo se muestran las flechas que manda la app de navegación: la última se conserva
// hasta que llega otra o termina la ruta.
#define LADO_FLECHA_RECIBIDA 48
#define LADO_FLECHA_EN_PANTALLA 72

// Sin tocar la pantalla de brillo durante este tiempo, se vuelve a la anterior.
#define TIEMPO_VUELTA_DESDE_BRILLO_MS 10000

#define DURACION_FUNDIDO_MS 300

// Lienzo de 1 bit por píxel (paleta: 0 = negro, 1 = color del texto) para la flecha.
LV_DRAW_BUF_DEFINE_STATIC(bufferFlecha, LADO_FLECHA_EN_PANTALLA, LADO_FLECHA_EN_PANTALLA, LV_COLOR_FORMAT_I1);

// ------------------------------------------------------------------ textos

static const char *NOMBRES_DIAS[] = {"domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"};
static const char *NOMBRES_MESES[] = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
                                      "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

// ------------------------------------------------------------------ objetos de la interfaz

static lv_obj_t *pantallaReloj;
static lv_obj_t *etiquetaBluetooth;
static lv_obj_t *etiquetaIconoBateria;
static lv_obj_t *etiquetaPorcentajeBateria;
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

static lv_obj_t *pantallaBrillo;
static lv_obj_t *etiquetaTituloBrillo;
static lv_obj_t *iconoSol;
static lv_obj_t *iconoLuna;
static lv_obj_t *etiquetaValorBrillo;
static lv_obj_t *ruedaBrillo;
static lv_obj_t *botonMenos;
static lv_obj_t *botonMas;
static lv_obj_t *botonAuto;
static lv_obj_t *etiquetaBotonAuto;
static lv_obj_t *casillaAhorro;

static lv_obj_t *pantallaLlegada;
static lv_obj_t *etiquetaHasLlegado;
static lv_obj_t *etiquetaHoraLlegada;

static lv_obj_t *pantallaActualizacion;
static lv_obj_t *etiquetaActualizacion;
static lv_obj_t *barraActualizacion;

// Puntos de página (abajo) de cada pantalla, para recolorearlos en modo noche.
static lv_obj_t *puntoActivoReloj, *puntoActivoNavegacion, *puntoActivoBrillo;

// ------------------------------------------------------------------ estado

static void (*avisarCambioDeBrillo)() = nullptr;
static bool navegacionActiva = false;
static bool avisoActivo = false;
static bool modoNoche = false;
static bool movilConectado = false;
static int brilloMostrado = -1;

// Lo último que se pintó en el reloj, para no redibujar si no cambia nada.
static bool relojPintado = false;
static bool horaSincronizadaPintada = false;
static int horaPintada = -1, minutoPintado = -1, diaPintado = -1;

static uint32_t colorTexto() { return modoNoche ? COLOR_TEXTO_NOCHE : COLOR_TEXTO; }
static uint32_t colorSecundario() { return modoNoche ? COLOR_SECUNDARIO_NOCHE : COLOR_TEXTO_SECUNDARIO; }
static uint32_t colorFecha() { return modoNoche ? COLOR_TEXTO_NOCHE : COLOR_FECHA; }

// ------------------------------------------------------------------ ayudas de creación

static lv_obj_t *crearPantallaVacia()
{
  lv_obj_t *pantalla = lv_obj_create(NULL);
  lv_obj_remove_flag(pantalla, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_style_bg_color(pantalla, lv_color_hex(COLOR_FONDO), 0);
  lv_obj_set_style_bg_opa(pantalla, LV_OPA_COVER, 0);
  return pantalla;
}

static lv_obj_t *crearEtiqueta(lv_obj_t *padre, const lv_font_t *fuente, uint32_t color)
{
  lv_obj_t *etiqueta = lv_label_create(padre);
  lv_obj_set_style_text_font(etiqueta, fuente, 0);
  lv_obj_set_style_text_color(etiqueta, lv_color_hex(color), 0);
  lv_obj_set_style_text_align(etiqueta, LV_TEXT_ALIGN_CENTER, 0);
  lv_label_set_text(etiqueta, "");
  return etiqueta;
}

static lv_obj_t *crearCirculo(lv_obj_t *padre, int diametro, uint32_t color)
{
  lv_obj_t *circulo = lv_obj_create(padre);
  lv_obj_remove_style_all(circulo);
  lv_obj_set_size(circulo, diametro, diametro);
  lv_obj_set_style_radius(circulo, LV_RADIUS_CIRCLE, 0);
  lv_obj_set_style_bg_color(circulo, lv_color_hex(color), 0);
  lv_obj_set_style_bg_opa(circulo, LV_OPA_COVER, 0);
  lv_obj_remove_flag(circulo, LV_OBJ_FLAG_CLICKABLE);
  return circulo;
}

// Dos puntos abajo que indican la página: 0 = reloj/navegación, 1 = brillo.
// Devuelve el punto encendido.
static lv_obj_t *crearPuntosDePagina(lv_obj_t *pantalla, int paginaActiva)
{
  lv_obj_t *puntoEncendido = nullptr;
  for (int pagina = 0; pagina < 2; pagina++)
  {
    lv_obj_t *punto = crearCirculo(pantalla, 7, pagina == paginaActiva ? colorTexto() : COLOR_APAGADO);
    lv_obj_align(punto, LV_ALIGN_BOTTOM_MID, pagina == 0 ? -7 : 7, -12);
    if (pagina == paginaActiva)
      puntoEncendido = punto;
  }
  return puntoEncendido;
}

static void colocarIndicacion(bool hayDistancia)
{
  int lineas = hayDistancia ? 2 : 4;
  lv_obj_set_height(etiquetaIndicacion, lineas * ALTURA_LINEA + 2);
  lv_obj_align(etiquetaIndicacion, LV_ALIGN_TOP_MID, 0,
               hayDistancia ? Y_INDICACION_CON_DISTANCIA : Y_INDICACION_SIN_DISTANCIA);
}

// ------------------------------------------------------------------ cambio de pantalla

// Pantalla a la que se vuelve desde el brillo y la llegada.
static lv_obj_t *pantallaPrincipal()
{
  return navegacionActiva ? pantallaNavegacion : pantallaReloj;
}

static void cargarPantalla(lv_obj_t *pantalla, lv_screen_load_anim_t animacion)
{
  if (lv_screen_active() != pantalla)
    lv_screen_load_anim(pantalla, animacion, DURACION_FUNDIDO_MS, 0, false);
}

static void refrescarControlesBrillo();

static void abrirBrillo()
{
  refrescarControlesBrillo();
  cargarPantalla(pantallaBrillo, LV_SCR_LOAD_ANIM_MOVE_LEFT);
}

static void salirDeBrillo()
{
  cargarPantalla(pantallaPrincipal(), LV_SCR_LOAD_ANIM_MOVE_RIGHT);
}

// Gestos: deslizar a la izquierda abre el brillo; a la derecha, sale de él.
static void alHacerGesto(lv_event_t *evento)
{
  lv_indev_t *dispositivo = lv_indev_active();
  lv_dir_t direccion = lv_indev_get_gesture_dir(dispositivo);
  lv_obj_t *pantalla = lv_screen_active();
  if (direccion == LV_DIR_LEFT && (pantalla == pantallaReloj || pantalla == pantallaNavegacion))
  {
    lv_indev_wait_release(dispositivo); // que el mismo gesto no cuente dos veces
    abrirBrillo();
  }
  else if (direccion == LV_DIR_RIGHT && pantalla == pantallaBrillo)
  {
    lv_indev_wait_release(dispositivo);
    salirDeBrillo();
  }
}

// ------------------------------------------------------------------ pantalla de reloj

static void crearPantallaReloj()
{
  pantallaReloj = crearPantallaVacia();

  // Arriba: Bluetooth y batería del móvil en una fila centrada. Los iconos vienen de la
  // fuente integrada de LVGL, que incluye los símbolos.
  lv_obj_t *filaEstado = lv_obj_create(pantallaReloj);
  lv_obj_remove_style_all(filaEstado);
  lv_obj_set_size(filaEstado, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(filaEstado, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(filaEstado, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(filaEstado, 6, 0);
  lv_obj_remove_flag(filaEstado, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_align(filaEstado, LV_ALIGN_TOP_MID, 0, 28);

  etiquetaBluetooth = crearEtiqueta(filaEstado, &lv_font_montserrat_20, COLOR_APAGADO);
  lv_label_set_text(etiquetaBluetooth, LV_SYMBOL_BLUETOOTH);
  etiquetaIconoBateria = crearEtiqueta(filaEstado, &lv_font_montserrat_20, COLOR_TEXTO_SECUNDARIO);
  etiquetaPorcentajeBateria = crearEtiqueta(filaEstado, &fuente_es_16, COLOR_TEXTO_SECUNDARIO);
  lv_obj_add_flag(etiquetaIconoBateria, LV_OBJ_FLAG_HIDDEN);
  lv_obj_add_flag(etiquetaPorcentajeBateria, LV_OBJ_FLAG_HIDDEN);

  // Centro: hora en grande.
  etiquetaHora = crearEtiqueta(pantallaReloj, &fuente_reloj_64, COLOR_TEXTO);
  lv_obj_align(etiquetaHora, LV_ALIGN_CENTER, 0, -14);

  // Debajo: día de la semana y fecha.
  etiquetaDiaSemana = crearEtiqueta(pantallaReloj, &fuente_es_20, COLOR_DIA_SEMANA);
  lv_obj_align(etiquetaDiaSemana, LV_ALIGN_CENTER, 0, 36);

  etiquetaFecha = crearEtiqueta(pantallaReloj, &fuente_es_20, COLOR_FECHA);
  lv_obj_align(etiquetaFecha, LV_ALIGN_CENTER, 0, 62);

  puntoActivoReloj = crearPuntosDePagina(pantallaReloj, 0);
  lv_obj_add_event_cb(pantallaReloj, alHacerGesto, LV_EVENT_GESTURE, NULL);
}

// ------------------------------------------------------------------ pantalla de navegación

static void crearPantallaNavegacion()
{
  pantallaNavegacion = crearPantallaVacia();

  // Arriba: hora de llegada y, debajo, tiempo y distancia restantes.
  etiquetaLlegada = crearEtiqueta(pantallaNavegacion, &fuente_es_16, COLOR_TEXTO_SECUNDARIO);
  lv_obj_align(etiquetaLlegada, LV_ALIGN_TOP_MID, 0, 26);

  etiquetaTiempoYDistancia = crearEtiqueta(pantallaNavegacion, &fuente_es_16, COLOR_TEXTO_SECUNDARIO);
  lv_obj_align(etiquetaTiempoYDistancia, LV_ALIGN_TOP_MID, 0, 46);

  // Centro: flecha de la maniobra sobre negro. Oculta hasta que llegue una.
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

  puntoActivoNavegacion = crearPuntosDePagina(pantallaNavegacion, 0);
  lv_obj_add_event_cb(pantallaNavegacion, alHacerGesto, LV_EVENT_GESTURE, NULL);
}

// ------------------------------------------------------------------ pantalla de brillo

static void aplicarNuevoBrillo()
{
  if (avisarCambioDeBrillo)
    avisarCambioDeBrillo();
  refrescarControlesBrillo();
}

// La rueda: al moverla se pasa a manual con ese brillo.
static void alMoverRueda(lv_event_t *evento)
{
  ajustes::cambiarBrilloManual(lv_arc_get_value(ruedaBrillo));
  aplicarNuevoBrillo();
}

// − / +: de 10 en 10 %, partiendo del brillo que se ve ahora.
static void alPulsarMenosOMas(lv_event_t *evento)
{
  int cambio = lv_event_get_target(evento) == botonMas ? ajustes::PASO_BRILLO : -ajustes::PASO_BRILLO;
  int base = brilloMostrado > 0 ? brilloMostrado : ajustes::brilloManual();
  // Redondear a la decena para que los pasos queden en 10, 20, 30…
  int redondeado = ((base + ajustes::PASO_BRILLO / 2) / ajustes::PASO_BRILLO) * ajustes::PASO_BRILLO;
  ajustes::cambiarBrilloManual(redondeado + cambio);
  aplicarNuevoBrillo();
}

static void alPulsarAuto(lv_event_t *evento)
{
  bool activar = !ajustes::brilloAutomatico();
  if (!activar)
    ajustes::cambiarBrilloManual(brilloMostrado > 0 ? brilloMostrado : ajustes::brilloManual());
  else
    ajustes::cambiarBrilloAutomatico(true);
  aplicarNuevoBrillo();
}

static void alCambiarAhorro(lv_event_t *evento)
{
  ajustes::cambiarModoAhorro(lv_obj_has_state(casillaAhorro, LV_STATE_CHECKED));
}

static lv_obj_t *crearBotonRedondo(lv_obj_t *padre, const char *simbolo, int desplazamientoX)
{
  lv_obj_t *boton = lv_button_create(padre);
  lv_obj_set_size(boton, 36, 36);
  lv_obj_set_style_radius(boton, LV_RADIUS_CIRCLE, 0);
  lv_obj_set_style_bg_color(boton, lv_color_hex(COLOR_ARCO_FONDO), 0);
  lv_obj_set_style_shadow_width(boton, 0, 0);
  lv_obj_align(boton, LV_ALIGN_CENTER, desplazamientoX, -6);
  lv_obj_t *etiqueta = crearEtiqueta(boton, &lv_font_montserrat_20, COLOR_TEXTO);
  lv_label_set_text(etiqueta, simbolo);
  lv_obj_center(etiqueta);
  lv_obj_add_event_cb(boton, alPulsarMenosOMas, LV_EVENT_CLICKED, NULL);
  return boton;
}

static void crearPantallaBrillo()
{
  pantallaBrillo = crearPantallaVacia();

  // Rueda de brillo en el borde (10 … 100 %). Solo responde cerca del aro, para que los
  // toques en el centro sirvan para los gestos y los botones.
  ruedaBrillo = lv_arc_create(pantallaBrillo);
  lv_obj_set_size(ruedaBrillo, DIAMETRO_RUEDA, DIAMETRO_RUEDA);
  lv_obj_center(ruedaBrillo);
  lv_arc_set_bg_angles(ruedaBrillo, 135, 45);
  lv_arc_set_range(ruedaBrillo, ajustes::BRILLO_MINIMO, ajustes::BRILLO_MAXIMO);
  lv_obj_add_flag(ruedaBrillo, LV_OBJ_FLAG_ADV_HITTEST);
  lv_obj_set_style_arc_width(ruedaBrillo, GROSOR_RUEDA, LV_PART_MAIN);
  lv_obj_set_style_arc_color(ruedaBrillo, lv_color_hex(COLOR_ARCO_FONDO), LV_PART_MAIN);
  lv_obj_set_style_arc_width(ruedaBrillo, GROSOR_RUEDA, LV_PART_INDICATOR);
  lv_obj_set_style_bg_color(ruedaBrillo, lv_color_hex(COLOR_TEXTO), LV_PART_KNOB);
  lv_obj_set_style_pad_all(ruedaBrillo, 2, LV_PART_KNOB);
  lv_obj_add_event_cb(ruedaBrillo, alMoverRueda, LV_EVENT_VALUE_CHANGED, NULL);

  etiquetaTituloBrillo = crearEtiqueta(pantallaBrillo, &fuente_es_16, COLOR_TEXTO_SECUNDARIO);
  lv_label_set_text(etiquetaTituloBrillo, "Brillo");
  lv_obj_align(etiquetaTituloBrillo, LV_ALIGN_TOP_MID, 0, 34);

  // Icono: sol de día, luna de noche (un círculo con otro negro encima, desplazado).
  iconoSol = crearCirculo(pantallaBrillo, 24, COLOR_SOL);
  lv_obj_align(iconoSol, LV_ALIGN_TOP_MID, 0, 58);
  iconoLuna = crearCirculo(pantallaBrillo, 24, COLOR_LUNA);
  lv_obj_align(iconoLuna, LV_ALIGN_TOP_MID, 0, 58);
  lv_obj_t *sombraLuna = crearCirculo(iconoLuna, 20, COLOR_FONDO);
  lv_obj_set_pos(sombraLuna, 8, -4);

  etiquetaValorBrillo = crearEtiqueta(pantallaBrillo, &fuente_es_28, COLOR_TEXTO);
  lv_obj_align(etiquetaValorBrillo, LV_ALIGN_TOP_MID, 0, 92);

  botonMenos = crearBotonRedondo(pantallaBrillo, LV_SYMBOL_MINUS, -80);
  botonMas = crearBotonRedondo(pantallaBrillo, LV_SYMBOL_PLUS, 80);

  // Botón Auto (verde y con ✓ cuando está activado).
  botonAuto = lv_button_create(pantallaBrillo);
  lv_obj_set_size(botonAuto, 88, 30);
  lv_obj_set_style_radius(botonAuto, 15, 0);
  lv_obj_set_style_shadow_width(botonAuto, 0, 0);
  lv_obj_set_style_border_width(botonAuto, 2, 0);
  lv_obj_align(botonAuto, LV_ALIGN_TOP_MID, 0, 140);
  etiquetaBotonAuto = crearEtiqueta(botonAuto, &lv_font_montserrat_14, COLOR_TEXTO);
  lv_obj_center(etiquetaBotonAuto);
  lv_obj_add_event_cb(botonAuto, alPulsarAuto, LV_EVENT_CLICKED, NULL);

  // Casilla "Modo ahorro" (se puede tocar en toda la fila).
  casillaAhorro = lv_checkbox_create(pantallaBrillo);
  lv_checkbox_set_text(casillaAhorro, "Modo ahorro");
  lv_obj_set_style_text_font(casillaAhorro, &fuente_es_16, LV_PART_MAIN);
  lv_obj_set_style_text_font(casillaAhorro, &lv_font_montserrat_14, LV_PART_INDICATOR); // símbolo ✓
  lv_obj_set_style_border_color(casillaAhorro, lv_color_hex(COLOR_TEXTO_SECUNDARIO), LV_PART_INDICATOR);
  lv_obj_set_style_bg_color(casillaAhorro, lv_color_hex(COLOR_ACTIVADO), LV_PART_INDICATOR | LV_STATE_CHECKED);
  lv_obj_set_style_border_color(casillaAhorro, lv_color_hex(COLOR_ACTIVADO), LV_PART_INDICATOR | LV_STATE_CHECKED);
  lv_obj_align(casillaAhorro, LV_ALIGN_TOP_MID, 0, 182);
  lv_obj_add_event_cb(casillaAhorro, alCambiarAhorro, LV_EVENT_VALUE_CHANGED, NULL);

  puntoActivoBrillo = crearPuntosDePagina(pantallaBrillo, 1);
  lv_obj_add_event_cb(pantallaBrillo, alHacerGesto, LV_EVENT_GESTURE, NULL);
}

// Pone los controles de la pantalla de brillo de acuerdo con los ajustes y el brillo actual.
static void refrescarControlesBrillo()
{
  bool automatico = ajustes::brilloAutomatico();
  int brillo = brilloMostrado > 0 ? brilloMostrado : ajustes::brilloManual();

  lv_arc_set_value(ruedaBrillo, brillo);
  lv_obj_set_style_arc_color(ruedaBrillo, lv_color_hex(automatico ? COLOR_APAGADO : COLOR_SOL), LV_PART_INDICATOR);
  lv_label_set_text_fmt(etiquetaValorBrillo, "%d %%", brillo);

  // En automático, − / + se ocultan (se vuelve a manual con la rueda o el botón Auto).
  if (automatico)
  {
    lv_obj_add_flag(botonMenos, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(botonMas, LV_OBJ_FLAG_HIDDEN);
  }
  else
  {
    lv_obj_remove_flag(botonMenos, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(botonMas, LV_OBJ_FLAG_HIDDEN);
  }

  lv_label_set_text(etiquetaBotonAuto, automatico ? "Auto " LV_SYMBOL_OK : "Auto");
  lv_obj_set_style_bg_color(botonAuto, lv_color_hex(automatico ? COLOR_ACTIVADO : COLOR_FONDO), 0);
  lv_obj_set_style_border_color(botonAuto, lv_color_hex(automatico ? COLOR_ACTIVADO : COLOR_APAGADO), 0);

  if (ajustes::modoAhorro())
    lv_obj_add_state(casillaAhorro, LV_STATE_CHECKED);
  else
    lv_obj_remove_state(casillaAhorro, LV_STATE_CHECKED);

  if (modoNoche)
  {
    lv_obj_add_flag(iconoSol, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(iconoLuna, LV_OBJ_FLAG_HIDDEN);
  }
  else
  {
    lv_obj_remove_flag(iconoSol, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(iconoLuna, LV_OBJ_FLAG_HIDDEN);
  }
}

void ui_refrescar_brillo(int brilloActual, bool deNoche)
{
  if (brilloActual == brilloMostrado && deNoche == modoNoche)
    return;
  brilloMostrado = brilloActual;
  ui_establecer_modo_noche(deNoche);
  refrescarControlesBrillo();
}

// ------------------------------------------------------------------ pantalla de llegada

static void crearPantallaLlegada()
{
  pantallaLlegada = crearPantallaVacia();

  // Bandera: mástil y paño verde.
  lv_obj_t *mastil = lv_obj_create(pantallaLlegada);
  lv_obj_remove_style_all(mastil);
  lv_obj_set_size(mastil, 5, 64);
  lv_obj_set_style_bg_color(mastil, lv_color_hex(COLOR_FECHA), 0);
  lv_obj_set_style_bg_opa(mastil, LV_OPA_COVER, 0);
  lv_obj_align(mastil, LV_ALIGN_TOP_MID, -20, 44);

  lv_obj_t *pano = lv_obj_create(pantallaLlegada);
  lv_obj_remove_style_all(pano);
  lv_obj_set_size(pano, 44, 30);
  lv_obj_set_style_radius(pano, 3, 0);
  lv_obj_set_style_bg_color(pano, lv_color_hex(COLOR_ARCO_PROGRESO), 0);
  lv_obj_set_style_bg_opa(pano, LV_OPA_COVER, 0);
  lv_obj_align(pano, LV_ALIGN_TOP_MID, 7, 44);

  etiquetaHasLlegado = crearEtiqueta(pantallaLlegada, &fuente_es_28, COLOR_TEXTO);
  lv_label_set_text(etiquetaHasLlegado, "Has llegado");
  lv_obj_align(etiquetaHasLlegado, LV_ALIGN_TOP_MID, 0, 124);

  etiquetaHoraLlegada = crearEtiqueta(pantallaLlegada, &fuente_es_20, COLOR_TEXTO_SECUNDARIO);
  lv_obj_align(etiquetaHoraLlegada, LV_ALIGN_TOP_MID, 0, 162);
}

void ui_mostrar_llegada(int hora, int minuto)
{
  navegacionActiva = false;
  lv_label_set_text_fmt(etiquetaHoraLlegada, "%02d:%02d", hora, minuto);
  // Si se está en el brillo, no se interrumpe: al salir se irá al reloj.
  if (lv_screen_active() != pantallaBrillo)
    cargarPantalla(pantallaLlegada, LV_SCR_LOAD_ANIM_FADE_IN);
}

// ------------------------------------------------------------------ pantalla de actualización

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

// ------------------------------------------------------------------ inicio y tareas periódicas

void ui_init(void (*alCambiarBrillo)())
{
  avisarCambioDeBrillo = alCambiarBrillo;
  crearPantallaReloj();
  crearPantallaNavegacion();
  crearPantallaBrillo();
  crearPantallaLlegada();
  crearPantallaActualizacion();
  ui_actualizar_reloj(false, 0, 0, 0, 1, 1);
  lv_screen_load(pantallaReloj);
}

void ui_atender()
{
  // Vuelta automática desde el brillo si no se toca la pantalla.
  if (lv_screen_active() == pantallaBrillo &&
      lv_display_get_inactive_time(NULL) > TIEMPO_VUELTA_DESDE_BRILLO_MS)
    salirDeBrillo();
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
  movilConectado = conectado;
  lv_obj_set_style_text_color(etiquetaBluetooth,
                              lv_color_hex(conectado ? COLOR_BLUETOOTH_CONECTADO : COLOR_APAGADO), 0);
  if (!conectado)
  {
    lv_obj_add_flag(etiquetaIconoBateria, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(etiquetaPorcentajeBateria, LV_OBJ_FLAG_HIDDEN);
  }
}

void ui_mostrar_bateria_movil(int porcentaje, bool cargando)
{
  if (!movilConectado)
    return;
  const char *icono = cargando           ? LV_SYMBOL_CHARGE
                      : porcentaje >= 90 ? LV_SYMBOL_BATTERY_FULL
                      : porcentaje >= 65 ? LV_SYMBOL_BATTERY_3
                      : porcentaje >= 40 ? LV_SYMBOL_BATTERY_2
                      : porcentaje >= 15 ? LV_SYMBOL_BATTERY_1
                                         : LV_SYMBOL_BATTERY_EMPTY;
  // Batería baja (menos del 15 %) en naranja.
  uint32_t color = (!cargando && porcentaje < 15) ? COLOR_AVISO : colorSecundario();
  lv_label_set_text(etiquetaIconoBateria, icono);
  lv_label_set_text_fmt(etiquetaPorcentajeBateria, "%d %%", porcentaje);
  lv_obj_set_style_text_color(etiquetaIconoBateria, lv_color_hex(color), 0);
  lv_obj_set_style_text_color(etiquetaPorcentajeBateria, lv_color_hex(color), 0);
  lv_obj_remove_flag(etiquetaIconoBateria, LV_OBJ_FLAG_HIDDEN);
  lv_obj_remove_flag(etiquetaPorcentajeBateria, LV_OBJ_FLAG_HIDDEN);
}

void ui_volver_al_reloj()
{
  navegacionActiva = false;
  lv_obj_t *pantalla = lv_screen_active();
  // Desde el brillo no se sale: al salir de él ya se irá al reloj.
  if (pantalla == pantallaNavegacion || pantalla == pantallaLlegada || pantalla == pantallaActualizacion)
    cargarPantalla(pantallaReloj, LV_SCR_LOAD_ANIM_FADE_IN);
}

// ------------------------------------------------------------------ navegación

void ui_mostrar_navegacion(const String &distanciaManiobra, const String &indicacion,
                           const String &horaLlegada, const String &tiempoRestante,
                           const String &distanciaRestante)
{
  navegacionActiva = true;

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

  // Desde el reloj o la llegada se pasa a la navegación; el brillo no se interrumpe.
  lv_obj_t *pantalla = lv_screen_active();
  if (pantalla == pantallaReloj || pantalla == pantallaLlegada)
    cargarPantalla(pantallaNavegacion, LV_SCR_LOAD_ANIM_FADE_IN);
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
  lv_obj_set_style_text_color(etiquetaDistanciaManiobra, lv_color_hex(activar ? COLOR_AVISO : colorTexto()), 0);

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

// ------------------------------------------------------------------ modo noche

static void colorearTexto(lv_obj_t *etiqueta, uint32_t color)
{
  lv_obj_set_style_text_color(etiqueta, lv_color_hex(color), 0);
}

void ui_establecer_modo_noche(bool activado)
{
  if (activado == modoNoche)
    return;
  modoNoche = activado;

  // Textos principales y secundarios. El verde del día de la semana, el azul del
  // Bluetooth, el verde del arco y el naranja del aviso no cambian.
  colorearTexto(etiquetaHora, colorTexto());
  colorearTexto(etiquetaFecha, colorFecha());
  colorearTexto(etiquetaLlegada, colorSecundario());
  colorearTexto(etiquetaTiempoYDistancia, colorSecundario());
  colorearTexto(etiquetaIndicacion, colorTexto());
  if (!avisoActivo)
    colorearTexto(etiquetaDistanciaManiobra, colorTexto());
  colorearTexto(etiquetaTituloBrillo, colorSecundario());
  colorearTexto(etiquetaValorBrillo, colorTexto());
  lv_obj_set_style_text_color(casillaAhorro, lv_color_hex(colorTexto()), LV_PART_MAIN);
  colorearTexto(etiquetaHasLlegado, colorTexto());
  colorearTexto(etiquetaHoraLlegada, colorSecundario());
  for (lv_obj_t *punto : {puntoActivoReloj, puntoActivoNavegacion, puntoActivoBrillo})
    lv_obj_set_style_bg_color(punto, lv_color_hex(colorTexto()), 0);

  // La flecha usa el color del texto (índice 1 de su paleta).
  lv_color_t colorFlecha = lv_color_hex(colorTexto());
  lv_canvas_set_palette(lienzoFlecha, 1, lv_color32_make(colorFlecha.red, colorFlecha.green, colorFlecha.blue, 255));
  lv_obj_invalidate(lienzoFlecha);

  // La batería del móvil se recolorea en su próxima actualización; el icono se ajusta ya.
  if (!lv_obj_has_flag(etiquetaPorcentajeBateria, LV_OBJ_FLAG_HIDDEN))
  {
    colorearTexto(etiquetaIconoBateria, colorSecundario());
    colorearTexto(etiquetaPorcentajeBateria, colorSecundario());
  }
}
