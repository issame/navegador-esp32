#pragma once
// Controlador de la pantalla redonda GC9A01 (240x240) y del táctil CST816S de la placa
// ESP32-C3 de 1,28". Configuración tomada del firmware esp32-c3-mini (entorno lolin_c3_mini).

#define LGFX_USE_V1
#include <Arduino.h>
#include <LovyanGFX.hpp>

#define SCREEN_WIDTH 240
#define SCREEN_HEIGHT 240

class LGFX : public lgfx::LGFX_Device
{
  lgfx::Panel_GC9A01 _panel;
  lgfx::Light_PWM _light;
  lgfx::Bus_SPI _bus;

public:
  LGFX()
  {
    {
      auto cfg = _bus.config();
      cfg.spi_host = SPI2_HOST;
      cfg.spi_mode = 0;
      cfg.freq_write = 80000000;
      cfg.freq_read = 20000000;
      cfg.use_lock = true;
      cfg.dma_channel = SPI_DMA_CH_AUTO;
      cfg.pin_sclk = 6;
      cfg.pin_mosi = 7;
      cfg.pin_miso = -1;
      cfg.pin_dc = 2;
      _bus.config(cfg);
      _panel.setBus(&_bus);
    }
    {
      auto cfg = _panel.config();
      cfg.pin_cs = 10;
      cfg.pin_rst = -1;
      cfg.pin_busy = -1;
      cfg.memory_width = SCREEN_WIDTH;
      cfg.memory_height = SCREEN_HEIGHT;
      cfg.panel_width = SCREEN_WIDTH;
      cfg.panel_height = SCREEN_HEIGHT;
      cfg.offset_x = 0;
      cfg.offset_y = 0;
      cfg.offset_rotation = 0;
      cfg.dummy_read_pixel = 8;
      cfg.dummy_read_bits = 1;
      cfg.readable = false;
      cfg.invert = true;
      cfg.rgb_order = false;
      cfg.dlen_16bit = false;
      cfg.bus_shared = false;
      _panel.config(cfg);
    }
    {
      auto cfg = _light.config();
      cfg.pin_bl = 3;
      cfg.invert = false;
      cfg.freq = 44100;
      cfg.pwm_channel = 1;
      _light.config(cfg);
      _panel.setLight(&_light);
    }
    // El táctil (CST816S, I2C 4/5) no se usa: la pantalla es solo de lectura.
    setPanel(&_panel);
  }
};
