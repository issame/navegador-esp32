#pragma once
// Controlador de la pantalla redonda GC9A01 (240x240) de la placa ESP32-C3 de 1,28".
// Configuración tomada del firmware esp32-c3-mini de Felix Biego (entorno lolin_c3_mini).
// El táctil (CST816S, I2C en los pines 4 y 5) no se usa: esta pantalla solo muestra datos.

#define LGFX_USE_V1
#include <Arduino.h>
#include <LovyanGFX.hpp>

#define ANCHO_PANTALLA 240
#define ALTO_PANTALLA 240

// Pines de la placa
#define PIN_SPI_RELOJ 6     // SCLK
#define PIN_SPI_DATOS 7     // MOSI
#define PIN_DATO_COMANDO 2  // DC
#define PIN_SELECCION 10    // CS
#define PIN_RETROILUMINACION 3

class PantallaRedonda : public lgfx::LGFX_Device
{
  lgfx::Panel_GC9A01 panel;
  lgfx::Light_PWM retroiluminacion;
  lgfx::Bus_SPI busSpi;

public:
  PantallaRedonda()
  {
    // Bus SPI a 80 MHz con DMA
    {
      auto cfg = busSpi.config();
      cfg.spi_host = SPI2_HOST;
      cfg.spi_mode = 0;
      cfg.freq_write = 80000000;
      cfg.freq_read = 20000000;
      cfg.use_lock = true;
      cfg.dma_channel = SPI_DMA_CH_AUTO;
      cfg.pin_sclk = PIN_SPI_RELOJ;
      cfg.pin_mosi = PIN_SPI_DATOS;
      cfg.pin_miso = -1; // la pantalla no se lee
      cfg.pin_dc = PIN_DATO_COMANDO;
      busSpi.config(cfg);
      panel.setBus(&busSpi);
    }
    // Panel GC9A01
    {
      auto cfg = panel.config();
      cfg.pin_cs = PIN_SELECCION;
      cfg.pin_rst = -1;
      cfg.pin_busy = -1;
      cfg.memory_width = ANCHO_PANTALLA;
      cfg.memory_height = ALTO_PANTALLA;
      cfg.panel_width = ANCHO_PANTALLA;
      cfg.panel_height = ALTO_PANTALLA;
      cfg.offset_x = 0;
      cfg.offset_y = 0;
      cfg.offset_rotation = 0;
      cfg.dummy_read_pixel = 8;
      cfg.dummy_read_bits = 1;
      cfg.readable = false;
      cfg.invert = true;     // este panel tiene los colores invertidos de fábrica
      cfg.rgb_order = false;
      cfg.dlen_16bit = false;
      cfg.bus_shared = false;
      panel.config(cfg);
    }
    // Retroiluminación por PWM (permite ajustar el brillo)
    {
      auto cfg = retroiluminacion.config();
      cfg.pin_bl = PIN_RETROILUMINACION;
      cfg.invert = false;
      cfg.freq = 44100;
      cfg.pwm_channel = 1;
      retroiluminacion.config(cfg);
      panel.setLight(&retroiluminacion);
    }
    setPanel(&panel);
  }
};
