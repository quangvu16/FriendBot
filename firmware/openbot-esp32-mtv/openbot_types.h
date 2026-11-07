// openbot_types.h - data types for openbot-esp32-mtv.ino
//
// Why a separate file? The Arduino IDE writes hidden declarations of every
// function at the top of the .ino, before any struct in the .ino is defined.
// Functions that take a Motor&, Sonar& or LineParser& then fail to compile
// ("'Motor' was not declared in this scope"). Types in a header that is
// included first are known before those hidden declarations.
#pragma once
#include <Arduino.h>

enum LinkSource : uint8_t { SRC_USB, SRC_BLE };

struct Motor {
  uint8_t in1, in2, en;
  uint8_t ch1, ch2;  // LEDC channels (core 2.x only)
  bool reversed;
};

struct Sonar {
  uint8_t trig, echo;
  volatile uint32_t rise_us;
  volatile uint32_t width_us;
  volatile bool done;
  volatile uint32_t last_edge_ms;
  uint16_t hist[3];
  uint8_t hist_idx;
  uint16_t cm;  // filtered distance
  bool fault;
};

const uint8_t MAX_MSG_SZ = 64;

struct LineParser {
  char buf[MAX_MSG_SZ];
  uint8_t len;
  bool overflow;
};
