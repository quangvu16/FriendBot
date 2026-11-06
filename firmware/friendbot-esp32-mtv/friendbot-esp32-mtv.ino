// =============================================================================
// FriendBot firmware (based on OpenBot, www.openbot.org) - ESP32 WROOM-32 + 3 ultrasonic sonars + 16x2 I2C LCD
// Robot type reported to the apps: "MTV"   (OPENBOT == MTV)
// =============================================================================
//
// Works with the FriendBot Android and iOS apps (standard OpenBot line protocol)
//   - USB serial  115200 8N1   (Android)
//   - Bluetooth LE            (Android + iOS), same UUIDs as stock OpenBot
//
// What this rewrite changes compared with the old MTV sketch
//   1. Real speed control: c<left>,<right> (-255..255) becomes PWM duty, both
//      directions. The old code only switched motors fully ON or OFF.
//   2. No delay() in loop(): the loop runs ~1000x/s, so the robot reacts to the
//      app immediately (old loop wasted 50-200 ms every pass).
//   3. Sonars are read with interrupts, one after another (no cross-talk,
//      no blocking), with a 3-sample median filter against false echoes.
//   4. Obstacle safety in the firmware: forward speed slows down when
//      something is near and stops at STOP distance. Turning on the spot and
//      reversing stay allowed, so you can always drive away.
//   5. Failsafes: stop when BLE disconnects, stop when the app heartbeat
//      (h<ms>) times out, optional stop when no c-command arrives.
//   6. BLE RX accepts "write" AND "write without response" (the iOS app sends
//      its STOP button with response - the old firmware could drop it).
//   7. Message parser is overflow-safe and has a separate buffer for USB and
//      BLE, so the two links cannot corrupt each other's messages.
//   8. LCD auto-detects address 0x27 / 0x3F, updates only changed characters
//      (no flicker), shows speed, link state and all 3 distances.
//   9. Builds on Arduino-ESP32 core 2.x AND 3.x.
//
// Libraries (Arduino IDE -> Tools -> Manage Libraries):
//   - "LiquidCrystal I2C" by Frank de Brabander   (only if HAS_LCD 1)
//   (NewPing is NOT needed any more.)
//
// Board: "ESP32 Dev Module"
// =============================================================================

#include <Arduino.h>
#if __has_include(<esp_arduino_version.h>)
#include <esp_arduino_version.h>
#endif
#if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
#define CORE_V3 1
#else
#define CORE_V3 0
#endif

//------------------------------------------------------------------------------
// SETTINGS - change these to fit your robot
//------------------------------------------------------------------------------
#define ROBOT_TYPE "MTV"   // name the apps show in "Robot Info"

#define HAS_BLUETOOTH 1    // BLE link for Android / iOS
#define HAS_LCD 1          // 16x2 I2C LCD
#define HAS_SONAR 1        // 3 HC-SR04 sonars
#define HAS_INDICATORS 1   // LEDs on GPIO 14 / 27
#define SONAR_SAFETY 1     // 1 = firmware slows/stops before obstacles
#define NO_PHONE_MODE 0    // 1 = drive alone and avoid obstacles (no app)
#define DEBUG 0            // 1 = print debug text on USB (do not use with app)

// ---- Motor driver --------------------------------------------------------
// DRIVER_DUAL_INPUT : L298N / MX1508 / L9110 / DRV8833 style. Each motor has
//                     two inputs (IN1 = forward, IN2 = backward). PWM goes on
//                     the active input. This matches your old wiring, where
//                     "PWM pin HIGH + DIR pin LOW" = forward.
// DRIVER_PWM_DIR    : Cytron MDD10A / similar. One PWM pin + one DIR pin.
#define DRIVER_DUAL_INPUT 1
#define DRIVER_PWM_DIR 2
#define MOTOR_DRIVER DRIVER_DUAL_INPUT

#define MOTOR_L_REVERSED 0     // 1 = left motor turns the wrong way
#define MOTOR_R_REVERSED 0     // 1 = right motor turns the wrong way
#define MOTOR_PWM_FREQ 5000    // Hz
#define MOTOR_PWM_RES 8        // bits -> duty 0..255
#define MOTOR_MIN_DUTY 60      // smallest duty that really turns the wheels
#define MOTOR_MAX_DUTY 255     // full app speed maps to this duty
#define MOTOR_BRAKE_ON_STOP 0  // 1 = brake (both inputs HIGH), 0 = coast
#define MOTOR_RAMP_STEP 20     // max duty increase per 10 ms (0 = no ramp)

// ---- Sonar safety -------------------------------------------------------
#define SONAR_MAX_CM 300        // readings above this = "free"
#define FRONT_STOP_CM 15        // middle sonar: stop forward at this distance
#define FRONT_SLOW_CM 50        // middle sonar: start slowing down here
#define SIDE_STOP_CM 10         // left/right sonar: stop forward
#define SIDE_SLOW_CM 25         // left/right sonar: start slowing down
#define SONAR_PERIOD_MS 40      // time per sensor (3 sensors -> 120 ms cycle)
#define SONAR_FAULT_MS 2000     // no echo edges this long = sensor fault

// ---- Escape reflex ------------------------------------------------------
// When any sonar sees less than ESCAPE_CM, the robot reverses away and turns
// toward the more open side - no matter what the app commands. Overrides the
// drive command until the front is clear again.
#define ESCAPE_CM 5          // trigger distance (cm)
#define ESCAPE_BACK_MS 400   // phase 1: back away from the obstacle
#define ESCAPE_TURN_MS 400   // phase 2: turn toward the open side
#define ESCAPE_BACK_DUTY 160 // reverse speed (of 255)
#define ESCAPE_TURN_DUTY 160 // turn speed (of 255)

// ---- Failsafe -----------------------------------------------------------
// The Android app sends h750 every 250 ms over USB -> heartbeat failsafe.
// Over BLE neither app sends a heartbeat, so BLE disconnect = stop.
// CONTROL_TIMEOUT_MS: stop when no c-command for this long (0 = off).
// Keep 0 unless your app re-sends drive commands continuously.
#define CONTROL_TIMEOUT_MS 0

//------------------------------------------------------------------------------
// PINS - same wiring as your old MTV sketch
//------------------------------------------------------------------------------
// Right motor: IN1(fwd)=19, IN2(back)=4, EN=18
// Left  motor: IN1(fwd)=15, IN2(back)=2, EN=5
const uint8_t PIN_R_IN1 = 19;
const uint8_t PIN_R_IN2 = 4;
const uint8_t PIN_R_EN = 18;  // driven HIGH (L298N enable). Unused on MX1508.
const uint8_t PIN_L_IN1 = 15;
const uint8_t PIN_L_IN2 = 2;
const uint8_t PIN_L_EN = 5;

// Sonars: trigger, echo
// NOTE: your old sketch had two different left/right labels for 26/25 and
// 32/33. Here 26/25 = LEFT and 32/33 = RIGHT. If the LCD shows them swapped,
// swap the two lines below.
const uint8_t PIN_SONAR_MID_TRIG = 13, PIN_SONAR_MID_ECHO = 12;
const uint8_t PIN_SONAR_LEFT_TRIG = 26, PIN_SONAR_LEFT_ECHO = 25;
const uint8_t PIN_SONAR_RIGHT_TRIG = 32, PIN_SONAR_RIGHT_ECHO = 33;

const uint8_t PIN_LED_LEFT = 14;
const uint8_t PIN_LED_RIGHT = 27;

const uint8_t PIN_I2C_SDA = 21;  // ESP32 default I2C pins
const uint8_t PIN_I2C_SCL = 22;

//------------------------------------------------------------------------------
// INCLUDES that depend on settings
//------------------------------------------------------------------------------
#if HAS_LCD
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#endif

#if HAS_BLUETOOTH
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#endif

// The Arduino preprocessor inserts function prototypes above the first
// function definition, i.e. before the struct definitions below. Forward
// declare them so those prototypes (Motor &, Sonar &, LineParser &) compile.
struct Motor;
struct Sonar;
struct LineParser;

//------------------------------------------------------------------------------
// GLOBAL STATE
//------------------------------------------------------------------------------
// Command from the app (or NO_PHONE_MODE), -255..255
int cmd_left = 0;
int cmd_right = 0;
// After obstacle safety
int safe_left = 0;
int safe_right = 0;
// What the motors are really doing (after ramp), -255..255
int out_left = 0;
int out_right = 0;

bool obstacle_block = false;   // forward motion blocked by an obstacle
float obstacle_factor = 1.0f;  // 1 = full speed, 0 = stopped

// Heartbeat (stock OpenBot behaviour: off until the first h<ms> message)
unsigned long heartbeat_interval = 0;
unsigned long heartbeat_time = 0;
bool heartbeat_expired = false;
unsigned long last_ctrl_ms = 0;

// Sensor report to the app (s<cm>); the app can change it with s<ms>
unsigned long sonar_report_interval = 1000;
unsigned long sonar_report_time = 0;

#if HAS_INDICATORS
// LED modes set by the app/voice with i<left>,<right>:
//   0=off, 1=on, 2=blink, 3=auto (show driving state)
enum LedMode : uint8_t { LED_OFF = 0, LED_ON, LED_BLINK, LED_AUTO };
uint8_t led_mode_left = LED_AUTO;
uint8_t led_mode_right = LED_AUTO;
bool blink_state = false;
unsigned long blink_time = 0;
#endif

enum LinkSource : uint8_t { SRC_USB, SRC_BLE };
LinkSource last_ctrl_source = SRC_USB;
unsigned long last_usb_rx_ms = 0;

//------------------------------------------------------------------------------
// PWM helpers (Arduino-ESP32 core 2.x and 3.x have different LEDC APIs)
//------------------------------------------------------------------------------
static void pwm_attach(uint8_t pin, uint8_t channel) {
#if CORE_V3
  (void)channel;
  ledcAttach(pin, MOTOR_PWM_FREQ, MOTOR_PWM_RES);
#else
  ledcSetup(channel, MOTOR_PWM_FREQ, MOTOR_PWM_RES);
  ledcAttachPin(pin, channel);
#endif
}

static void pwm_write(uint8_t pin, uint8_t channel, uint32_t duty) {
#if CORE_V3
  (void)channel;
  ledcWrite(pin, duty);
#else
  (void)pin;
  ledcWrite(channel, duty);
#endif
}

//------------------------------------------------------------------------------
// MOTORS
//------------------------------------------------------------------------------
struct Motor {
  uint8_t in1, in2, en;
  uint8_t ch1, ch2;  // LEDC channels (core 2.x only)
  bool reversed;
};

Motor motor_left = { PIN_L_IN1, PIN_L_IN2, PIN_L_EN, 0, 1, MOTOR_L_REVERSED };
Motor motor_right = { PIN_R_IN1, PIN_R_IN2, PIN_R_EN, 2, 3, MOTOR_R_REVERSED };

static const uint32_t DUTY_MAX = (1UL << MOTOR_PWM_RES) - 1;

static uint32_t cmd_to_duty(int cmd) {
  int a = abs(cmd);
  if (a == 0) return 0;
  if (a > 255) a = 255;
  // 1..255 -> MIN..MAX duty, so small app commands still move the robot
  return (uint32_t)map(a, 1, 255, MOTOR_MIN_DUTY, MOTOR_MAX_DUTY);
}

static void motor_begin(Motor &m) {
  pinMode(m.in1, OUTPUT);
  pinMode(m.in2, OUTPUT);
  digitalWrite(m.in1, LOW);
  digitalWrite(m.in2, LOW);
  pinMode(m.en, OUTPUT);
  digitalWrite(m.en, HIGH);  // L298N: enable. MX1508: pin not connected.
  pwm_attach(m.in1, m.ch1);
#if (MOTOR_DRIVER == DRIVER_DUAL_INPUT)
  pwm_attach(m.in2, m.ch2);
#endif
  pwm_write(m.in1, m.ch1, 0);
#if (MOTOR_DRIVER == DRIVER_DUAL_INPUT)
  pwm_write(m.in2, m.ch2, 0);
#endif
}

static void motor_set(Motor &m, int cmd) {
  if (m.reversed) cmd = -cmd;
  uint32_t duty = cmd_to_duty(cmd);
#if (MOTOR_DRIVER == DRIVER_DUAL_INPUT)
  if (cmd > 0) {
    pwm_write(m.in2, m.ch2, 0);
    pwm_write(m.in1, m.ch1, duty);
  } else if (cmd < 0) {
    pwm_write(m.in1, m.ch1, 0);
    pwm_write(m.in2, m.ch2, duty);
  } else {
    uint32_t stop = MOTOR_BRAKE_ON_STOP ? DUTY_MAX : 0;
    pwm_write(m.in1, m.ch1, stop);
    pwm_write(m.in2, m.ch2, stop);
  }
#else  // DRIVER_PWM_DIR: in1 = PWM, in2 = DIR
  digitalWrite(m.in2, cmd < 0 ? HIGH : LOW);
  pwm_write(m.in1, m.ch1, duty);
#endif
}

// Ramp: speeding up is limited (protects gears, less wheel spin),
// slowing down / stopping is always immediate (safety).
static int next_output(int current, int target, bool tick) {
#if (MOTOR_RAMP_STEP > 0)
  bool same_dir = (current >= 0 && target >= 0) || (current <= 0 && target <= 0);
  if (!same_dir) return 0;                        // reversing: stop first
  if (abs(target) <= abs(current)) return target; // slowing down: at once
  if (!tick) return current;                      // speeding up: in steps
  int step = min(abs(target) - abs(current), MOTOR_RAMP_STEP);
  return current + (target > 0 ? step : -step);
#else
  (void)current;
  (void)tick;
  return target;
#endif
}

unsigned long motor_update_time = 0;

static void update_motors() {
  unsigned long now = millis();
  bool tick = (now - motor_update_time) >= 10;  // ramp step every 10 ms
  if (tick) motor_update_time = now;

  int nl = next_output(out_left, safe_left, tick);
  int nr = next_output(out_right, safe_right, tick);

  if (nl != out_left) {
    out_left = nl;
    motor_set(motor_left, out_left);
  }
  if (nr != out_right) {
    out_right = nr;
    motor_set(motor_right, out_right);
  }
}

static void stop_now() {
  cmd_left = cmd_right = 0;
  safe_left = safe_right = 0;
  out_left = out_right = 0;
  motor_set(motor_left, 0);
  motor_set(motor_right, 0);
}

//------------------------------------------------------------------------------
// SONARS (interrupt based, one sensor at a time)
//------------------------------------------------------------------------------
#if HAS_SONAR
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

enum { SONAR_MID = 0, SONAR_LEFT = 1, SONAR_RIGHT = 2, SONAR_NUM = 3 };

Sonar sonars[SONAR_NUM] = {
  { PIN_SONAR_MID_TRIG, PIN_SONAR_MID_ECHO, 0, 0, false, 0, { SONAR_MAX_CM, SONAR_MAX_CM, SONAR_MAX_CM }, 0, SONAR_MAX_CM, false },
  { PIN_SONAR_LEFT_TRIG, PIN_SONAR_LEFT_ECHO, 0, 0, false, 0, { SONAR_MAX_CM, SONAR_MAX_CM, SONAR_MAX_CM }, 0, SONAR_MAX_CM, false },
  { PIN_SONAR_RIGHT_TRIG, PIN_SONAR_RIGHT_ECHO, 0, 0, false, 0, { SONAR_MAX_CM, SONAR_MAX_CM, SONAR_MAX_CM }, 0, SONAR_MAX_CM, false },
};

uint8_t sonar_active = 0;
unsigned long sonar_time = 0;

void IRAM_ATTR sonar_echo_isr(void *arg) {
  Sonar *s = (Sonar *)arg;
  uint32_t now = micros();
  if (digitalRead(s->echo)) {
    s->rise_us = now;
  } else if (s->rise_us != 0) {
    s->width_us = now - s->rise_us;
    s->rise_us = 0;
    s->done = true;
  }
  s->last_edge_ms = millis();
}

static uint16_t median3(uint16_t a, uint16_t b, uint16_t c) {
  if (a > b) { uint16_t t = a; a = b; b = t; }
  if (b > c) { uint16_t t = b; b = c; c = t; }
  if (a > b) { uint16_t t = a; a = b; b = t; }
  return b;
}

static void sonar_trigger(Sonar &s) {
  s.done = false;
  s.rise_us = 0;
  digitalWrite(s.trig, LOW);
  delayMicroseconds(2);
  digitalWrite(s.trig, HIGH);
  delayMicroseconds(10);
  digitalWrite(s.trig, LOW);
}

static void sonar_finish(Sonar &s) {
  uint16_t cm = SONAR_MAX_CM;  // no echo = nothing in range
  if (s.done) {
    uint32_t d = s.width_us / 58;  // us -> cm (speed of sound, there and back)
    if (d > 1 && d < SONAR_MAX_CM) cm = (uint16_t)d;
  }
  s.hist[s.hist_idx] = cm;
  s.hist_idx = (s.hist_idx + 1) % 3;
  s.cm = median3(s.hist[0], s.hist[1], s.hist[2]);
  s.fault = (millis() - s.last_edge_ms) > SONAR_FAULT_MS;
}

static void sonar_begin() {
  for (uint8_t i = 0; i < SONAR_NUM; i++) {
    pinMode(sonars[i].trig, OUTPUT);
    digitalWrite(sonars[i].trig, LOW);
    pinMode(sonars[i].echo, INPUT);
    sonars[i].last_edge_ms = millis();
    attachInterruptArg(digitalPinToInterrupt(sonars[i].echo), sonar_echo_isr, &sonars[i], CHANGE);
  }
  sonar_time = millis();
  sonar_trigger(sonars[sonar_active]);
}

static void sonar_update() {
  if ((millis() - sonar_time) < SONAR_PERIOD_MS) return;
  sonar_time = millis();
  sonar_finish(sonars[sonar_active]);
  sonar_active = (sonar_active + 1) % SONAR_NUM;
  sonar_trigger(sonars[sonar_active]);
}

static uint16_t front_clearance_cm() {
  uint16_t d = sonars[SONAR_MID].cm;
  d = min(d, sonars[SONAR_LEFT].cm);
  d = min(d, sonars[SONAR_RIGHT].cm);
  return d;
}
#endif

//------------------------------------------------------------------------------
// OBSTACLE SAFETY
//------------------------------------------------------------------------------
static float zone_factor(uint16_t d, uint16_t stop_cm, uint16_t slow_cm) {
  if (d <= stop_cm) return 0.0f;
  if (d >= slow_cm) return 1.0f;
  return (float)(d - stop_cm) / (float)(slow_cm - stop_cm);
}

// Split the command into forward speed (v) and turn (w). Only the forward
// part is reduced, so turning on the spot and reversing always work.
static void apply_safety() {
  int l = cmd_left, r = cmd_right;
  obstacle_factor = 1.0f;
  obstacle_block = false;
#if (HAS_SONAR && SONAR_SAFETY)
  float v = (l + r) / 2.0f;
  float w = (l - r) / 2.0f;
  if (v > 0) {
    float f_mid = sonars[SONAR_MID].fault ? 1.0f : zone_factor(sonars[SONAR_MID].cm, FRONT_STOP_CM, FRONT_SLOW_CM);
    float f_l = sonars[SONAR_LEFT].fault ? 1.0f : zone_factor(sonars[SONAR_LEFT].cm, SIDE_STOP_CM, SIDE_SLOW_CM);
    float f_r = sonars[SONAR_RIGHT].fault ? 1.0f : zone_factor(sonars[SONAR_RIGHT].cm, SIDE_STOP_CM, SIDE_SLOW_CM);
    obstacle_factor = min(f_mid, min(f_l, f_r));
    obstacle_block = obstacle_factor <= 0.0f;
    v *= obstacle_factor;
    l = (int)lroundf(v + w);
    r = (int)lroundf(v - w);
  }
#endif
  safe_left = constrain(l, -255, 255);
  safe_right = constrain(r, -255, 255);
}

#if HAS_SONAR
// ESCAPE REFLEX - any sonar closer than ESCAPE_CM: back away, then turn
// toward the more open side. Overrides the app drive command until clear.
enum EscapePhase : uint8_t { ESC_NONE, ESC_BACK, ESC_TURN };
EscapePhase esc_phase = ESC_NONE;
unsigned long esc_time = 0;

static bool escape_triggered() {
  return sonars[SONAR_MID].cm < ESCAPE_CM || sonars[SONAR_LEFT].cm < ESCAPE_CM ||
         sonars[SONAR_RIGHT].cm < ESCAPE_CM;
}

static void update_escape() {
  unsigned long now = millis();
  switch (esc_phase) {
    case ESC_NONE:
      if (escape_triggered()) {
        esc_phase = ESC_BACK;
        esc_time = now;
      }
      break;
    case ESC_BACK:
      if (now - esc_time >= ESCAPE_BACK_MS) {
        esc_phase = ESC_TURN;
        esc_time = now;
      }
      break;
    case ESC_TURN:
      if (now - esc_time >= ESCAPE_TURN_MS) {
        esc_phase = escape_triggered() ? ESC_BACK : ESC_NONE;  // still blocked: try again
      }
      break;
  }
}

// Writes the escape maneuver over safe_left / safe_right. Returns true while
// the reflex owns the motors (app commands resume when it is done).
static bool apply_escape() {
  if (esc_phase == ESC_NONE) return false;
  if (esc_phase == ESC_BACK) {
    safe_left = -ESCAPE_BACK_DUTY;
    safe_right = -ESCAPE_BACK_DUTY;
  } else {
    // turn toward the side with more room (left open -> turn left)
    bool left_open = sonars[SONAR_LEFT].cm >= sonars[SONAR_RIGHT].cm;
    safe_left = left_open ? -ESCAPE_TURN_DUTY : ESCAPE_TURN_DUTY;
    safe_right = left_open ? ESCAPE_TURN_DUTY : -ESCAPE_TURN_DUTY;
  }
  return true;
}
#endif

//------------------------------------------------------------------------------
// NO PHONE MODE - simple 3-sonar wander
//------------------------------------------------------------------------------
#if (NO_PHONE_MODE && HAS_SONAR)
static void no_phone_drive() {
  const int CRUISE = 170, SLOW = 110, SPIN = 150;
  uint16_t m = sonars[SONAR_MID].cm;
  uint16_t dl = sonars[SONAR_LEFT].cm;
  uint16_t dr = sonars[SONAR_RIGHT].cm;
  bool left_open = dl >= dr;  // turn toward the side with more room
  if (m < 12) {               // very close: back up while turning
    cmd_left = left_open ? -SLOW : -SLOW / 2;
    cmd_right = left_open ? -SLOW / 2 : -SLOW;
  } else if (m < 30 || dl < 15 || dr < 15) {  // blocked: spin on the spot
    cmd_left = left_open ? -SPIN : SPIN;
    cmd_right = left_open ? SPIN : -SPIN;
  } else if (m < 70) {  // getting close: slow and steer away
    cmd_left = left_open ? SLOW / 2 : SLOW;
    cmd_right = left_open ? SLOW : SLOW / 2;
  } else {  // free: cruise, steer gently away from the closer wall
    int steer = constrain(((int)dr - (int)dl) / 4, -40, 40);
    cmd_left = CRUISE - steer;
    cmd_right = CRUISE + steer;
  }
}
#endif

//------------------------------------------------------------------------------
// COMMUNICATION (USB serial + BLE)
//------------------------------------------------------------------------------
const uint8_t MAX_MSG_SZ = 64;

struct LineParser {
  char buf[MAX_MSG_SZ];
  uint8_t len;
  bool overflow;
};
LineParser usb_parser = { "", 0, false };

#if HAS_BLUETOOTH
LineParser ble_parser = { "", 0, false };
BLEServer *ble_server = nullptr;
BLECharacteristic *tx_char = nullptr;
BLECharacteristic *rx_char = nullptr;
volatile bool ble_connected = false;
bool ble_was_connected = false;
unsigned long ble_change_ms = 0;
bool ble_ready_sent = true;
QueueHandle_t ble_rx_queue = nullptr;

const char *SERVICE_UUID = "61653dc3-4021-4d1e-ba83-8b4eec61d613";
const char *CHARACTERISTIC_UUID_RX = "06386c14-86ea-4d71-811c-48f97c58f8c9";
const char *CHARACTERISTIC_UUID_TX = "9bf1103b-834c-47cf-b149-c9e4bcf778a7";
#endif

void send_data(const String &data) {
  Serial.println(data);
#if HAS_BLUETOOTH
  if (ble_connected && tx_char != nullptr) {
    size_t n = data.length();
    if (n > 180) n = 180;
    tx_char->setValue((uint8_t *)data.c_str(), n);
    tx_char->notify();
  }
#endif
}

static void send_features() {
  String msg = String("f") + ROBOT_TYPE + ":";
#if HAS_INDICATORS
  msg += "i:";
#endif
#if HAS_SONAR
  msg += "s:";
#endif
  send_data(msg);
}

// Parse "a,b" into two ints. Returns false if the message is broken.
static bool parse_two_ints(const char *body, long &a, long &b) {
  char *end;
  a = strtol(body, &end, 10);
  if (end == body || (*end != ',' && *end != ':')) return false;
  const char *p = end + 1;
  b = strtol(p, &end, 10);
  if (end == p) return false;
  return true;
}

static void handle_message(char *msg, LinkSource src) {
  char header = msg[0];
  const char *body = msg + 1;
  switch (header) {
    case 'c': {  // drive: c<left>,<right>
      long l, r;
      if (!parse_two_ints(body, l, r)) return;  // ignore broken commands
#if !NO_PHONE_MODE
      cmd_left = constrain((int)l, -255, 255);
      cmd_right = constrain((int)r, -255, 255);
#endif
      last_ctrl_ms = millis();
      last_ctrl_source = src;
      break;
    }
    case 'h':  // heartbeat: h<timeout ms>
      heartbeat_interval = strtoul(body, nullptr, 10);
      heartbeat_time = millis();
      break;
    case 'f':  // feature request
      send_features();
      break;
#if HAS_SONAR
    case 's': {  // sonar report interval: s<ms>
      unsigned long v = strtoul(body, nullptr, 10);
      sonar_report_interval = v < 50 ? 50 : v;
      break;
    }
#endif
#if HAS_INDICATORS
    case 'i': {  // LEDs: i<left>,<right>  (0=off, 1=on, 2=blink, 3=auto)
      long l, r;
      if (parse_two_ints(body, l, r)) {
        led_mode_left = (l >= LED_OFF && l <= LED_BLINK) ? (uint8_t)l : LED_AUTO;
        led_mode_right = (r >= LED_OFF && r <= LED_BLINK) ? (uint8_t)r : LED_AUTO;
      }
      break;
    }
#endif
    default:  // v, w, l, n, b ... not fitted on this robot -> ignore
      break;
  }
}

static void parser_feed(LineParser &p, char c, LinkSource src) {
  if (c == '\r') return;
  if (c == '\n') {
    if (!p.overflow && p.len > 0) {
      p.buf[p.len] = '\0';
      handle_message(p.buf, src);
    }
    p.len = 0;
    p.overflow = false;
    return;
  }
  if (p.len < MAX_MSG_SZ - 1) {
    p.buf[p.len++] = c;
  } else {
    p.overflow = true;  // too long: drop the whole line
  }
}

#if HAS_BLUETOOTH
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    ble_connected = true;
  }
  void onDisconnect(BLEServer *) override {
    ble_connected = false;
  }
};

// Runs in the BLE task: only copy the bytes, they are parsed in loop()
class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    auto value = c->getValue();  // std::string (core 2.x) or String (core 3.x)
    for (size_t i = 0; i < value.length(); i++) {
      char ch = value[i];
      xQueueSend(ble_rx_queue, &ch, 0);
    }
  }
};

static void ble_begin() {
  ble_rx_queue = xQueueCreate(256, sizeof(char));
  String name = String("FriendBot: ") + ROBOT_TYPE;
  BLEDevice::init(name.c_str());
  ble_server = BLEDevice::createServer();
  ble_server->setCallbacks(new ServerCallbacks());
  BLEService *service = ble_server->createService(BLEUUID(SERVICE_UUID));

  tx_char = service->createCharacteristic(BLEUUID(CHARACTERISTIC_UUID_TX), BLECharacteristic::PROPERTY_NOTIFY);
  tx_char->addDescriptor(new BLE2902());

  // WRITE + WRITE_NR: iOS sends STOP "with response", drive "without"
  rx_char = service->createCharacteristic(
    BLEUUID(CHARACTERISTIC_UUID_RX),
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx_char->setCallbacks(new RxCallbacks());

  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(BLEUUID(SERVICE_UUID));
  adv->setScanResponse(true);  // full name in scan response (fits iOS)
  adv->setMinPreferred(0x06);  // helps iPhone connection
  adv->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
}

static void ble_update() {
  char ch;
  while (xQueueReceive(ble_rx_queue, &ch, 0) == pdTRUE) {
    parser_feed(ble_parser, ch, SRC_BLE);
  }
  bool now_connected = ble_connected;
  if (now_connected != ble_was_connected) {
    ble_was_connected = now_connected;
    ble_change_ms = millis();
    ble_parser.len = 0;
    ble_parser.overflow = false;
    if (now_connected) {
      ble_ready_sent = false;  // send 'r' once the app has subscribed
    } else if (last_ctrl_source == SRC_BLE) {
      stop_now();  // phone gone -> stop
    }
  }
  if (now_connected && !ble_ready_sent && millis() - ble_change_ms > 800) {
    ble_ready_sent = true;
    send_data("r");
  }
  // restart advertising a little after a disconnect
  static bool adv_pending = false;
  if (!now_connected && ble_change_ms != 0 && !adv_pending && millis() - ble_change_ms > 300) {
    adv_pending = true;
    BLEDevice::startAdvertising();
  }
  if (now_connected) adv_pending = false;
}
#endif

static void usb_update() {
  int n = Serial.available();
  while (n-- > 0) {
    char c = (char)Serial.read();
    parser_feed(usb_parser, c, SRC_USB);
    last_usb_rx_ms = millis();
  }
}

//------------------------------------------------------------------------------
// FAILSAFES
//------------------------------------------------------------------------------
static void update_failsafe() {
  unsigned long now = millis();
  heartbeat_expired = false;
  if (heartbeat_interval > 0 && (now - heartbeat_time) >= heartbeat_interval) {
    heartbeat_expired = true;  // app stopped talking
  }
#if (CONTROL_TIMEOUT_MS > 0)
  if ((now - last_ctrl_ms) >= CONTROL_TIMEOUT_MS) heartbeat_expired = true;
#endif
  if (heartbeat_expired) {
    cmd_left = 0;
    cmd_right = 0;
  }
}

//------------------------------------------------------------------------------
// INDICATOR LEDS
//------------------------------------------------------------------------------
#if HAS_INDICATORS
// LED_AUTO shows the driving state:
//   turn left  -> left LED blinks
//   turn right -> right LED blinks
//   stopped    -> both LEDs blink
//   straight   -> both off
// Other modes are forced by the voice/app with the i-command.

static void update_indicators() {
  unsigned long now = millis();
  if (now - blink_time >= 500) {
    blink_time = now;
    blink_state = !blink_state;
  }
  // auto: driving state
  bool led_l, led_r;
  if (out_left == 0 && out_right == 0) {  // stopped: all blink
    led_l = led_r = blink_state;
  } else if (out_right > out_left) {  // turning left: left blinks
    led_l = blink_state;
    led_r = false;
  } else if (out_left > out_right) {  // turning right: right blinks
    led_l = false;
    led_r = blink_state;
  } else {  // straight
    led_l = led_r = false;
  }
  // forced modes (voice/app) override per side
  if (led_mode_left != LED_AUTO) led_l = (led_mode_left == LED_ON) || (led_mode_left == LED_BLINK && blink_state);
  if (led_mode_right != LED_AUTO) led_r = (led_mode_right == LED_ON) || (led_mode_right == LED_BLINK && blink_state);
  digitalWrite(PIN_LED_LEFT, led_l ? HIGH : LOW);
  digitalWrite(PIN_LED_RIGHT, led_r ? HIGH : LOW);
}
#endif

//------------------------------------------------------------------------------
// LCD 16x2
//------------------------------------------------------------------------------
#if HAS_LCD
LiquidCrystal_I2C *lcd = nullptr;
char lcd_shown[2][17];
uint8_t lcd_row = 0;
unsigned long lcd_time = 0;

static bool i2c_has(uint8_t addr) {
  Wire.beginTransmission(addr);
  return Wire.endTransmission() == 0;
}

static void lcd_begin() {
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  uint8_t addr = 0;
  if (i2c_has(0x27)) addr = 0x27;
  else if (i2c_has(0x3F)) addr = 0x3F;
  if (addr == 0) return;  // no LCD found: run without it
  lcd = new LiquidCrystal_I2C(addr, 16, 2);
  lcd->init();
  lcd->backlight();
  lcd->setCursor(0, 0);
  lcd->print("FriendBot " ROBOT_TYPE);
  lcd->setCursor(0, 1);
  lcd->print("Ready  BLE+USB");
  snprintf(lcd_shown[0], 17, "%-16s", "FriendBot " ROBOT_TYPE);
  snprintf(lcd_shown[1], 17, "%-16s", "Ready  BLE+USB");
}

static void fmt_dist(char *out, uint8_t idx) {  // out: 4 chars + '\0' 
#if HAS_SONAR
  if (sonars[idx].fault) {
    strcpy(out, "---");
  } else if (sonars[idx].cm >= SONAR_MAX_CM) {
    strcpy(out, "max");
  } else {
    snprintf(out, 6, "%3u", (unsigned)(sonars[idx].cm % 1000));
  }
#else
  (void)idx;
  strcpy(out, "n/a");
#endif
}

static const char *link_text() {
  if (obstacle_block) return "STOP";
  if (heartbeat_expired) return "LOST";
#if HAS_BLUETOOTH
  if (ble_connected) return " BLE";
#endif
  if (last_usb_rx_ms != 0 && millis() - last_usb_rx_ms < 2000) return " USB";
#if NO_PHONE_MODE
  return "AUTO";
#else
  return "WAIT";
#endif
}

// Write only the characters that changed -> fast, no flicker
static void lcd_write_row(uint8_t row, const char *text) {
  char line[17];
  snprintf(line, sizeof(line), "%-16s", text);
  for (uint8_t col = 0; col < 16; col++) {
    if (line[col] != lcd_shown[row][col]) {
      lcd->setCursor(col, row);
      uint8_t end = col;
      while (end < 16 && line[end] != lcd_shown[row][end]) end++;
      for (uint8_t k = col; k < end; k++) {
        lcd->write(line[k]);
        lcd_shown[row][k] = line[k];
      }
      col = end;
    }
  }
}

static void lcd_update() {
  if (lcd == nullptr) return;
  if (millis() - lcd_time < 125) return;  // one row every 125 ms
  lcd_time = millis();
  char text[24];
  if (lcd_row == 0) {
    // "L-255 R 255 BLE "  -> motor outputs and link state
    snprintf(text, sizeof(text), "L%4d R%4d %s", out_left, out_right, link_text());
  } else {
    // "L 25 M120 R max"   -> left / middle / right distance in cm
    char dl[6], dm[6], dr[6];
    fmt_dist(dl, 1);
    fmt_dist(dm, 0);
    fmt_dist(dr, 2);
    snprintf(text, sizeof(text), "L%s M%s R%s", dl, dm, dr);
  }
  lcd_write_row(lcd_row, text);
  lcd_row ^= 1;
}
#endif

//------------------------------------------------------------------------------
// SENSOR REPORTS TO THE APP
//------------------------------------------------------------------------------
static void report_sensors() {
#if HAS_SONAR
  // Stock OpenBot apps read "s<cm>" as the front distance. Send the closest
  // of the three front sensors (your current apps simply ignore it).
  if (millis() - sonar_report_time >= sonar_report_interval) {
    sonar_report_time = millis();
    send_data(String("s") + String(front_clearance_cm()));
  }
#endif
}

#if DEBUG
static void debug_print() {
  static unsigned long t = 0;
  if (millis() - t < 500) return;
  t = millis();
  Serial.printf("# cmd %d,%d safe %d,%d out %d,%d", cmd_left, cmd_right, safe_left, safe_right, out_left, out_right);
#if HAS_SONAR
  Serial.printf(" | L%u M%u R%u f%.2f", sonars[1].cm, sonars[0].cm, sonars[2].cm, obstacle_factor);
#endif
  Serial.println();
}
#endif

//------------------------------------------------------------------------------
// SETUP
//------------------------------------------------------------------------------
void setup() {
  motor_begin(motor_left);   // motors first, so they never twitch at boot
  motor_begin(motor_right);

#if HAS_INDICATORS
  pinMode(PIN_LED_LEFT, OUTPUT);
  pinMode(PIN_LED_RIGHT, OUTPUT);
  digitalWrite(PIN_LED_LEFT, HIGH);  // quick lamp test
  digitalWrite(PIN_LED_RIGHT, HIGH);
#endif

  Serial.begin(115200, SERIAL_8N1);

#if HAS_LCD
  lcd_begin();
#endif
#if HAS_SONAR
  sonar_begin();
#endif
#if HAS_BLUETOOTH
  ble_begin();
#endif

  Serial.println("r");  // tells the app the robot is ready
}

//------------------------------------------------------------------------------
// LOOP - no delay(), every task checks its own timer
//------------------------------------------------------------------------------
void loop() {
  usb_update();
#if HAS_BLUETOOTH
  ble_update();
#endif
#if HAS_SONAR
  sonar_update();
#endif

#if NO_PHONE_MODE
#if HAS_SONAR
  no_phone_drive();
#endif
#else
  update_failsafe();
#endif

  apply_safety();
#if HAS_SONAR
  update_escape();
  apply_escape();
#endif
  update_motors();

#if HAS_INDICATORS
  update_indicators();
#endif
#if HAS_LCD
  lcd_update();
#endif
  report_sensors();
#if DEBUG
  debug_print();
#endif
}
