# OpenBot firmware: ESP32 WROOM-32 + 3 sonars + LCD (`OPENBOT == MTV`)

This replaces `firmware/openbot-esp32s3/openbot-esp32s3.ino` (the old MTV sketch).
The old sketch is still there, unchanged. The wiring is the same, so you only need to re-flash.

## Flash it
1. Arduino IDE → Board: **ESP32 Dev Module**.
2. Library Manager → install **LiquidCrystal I2C** (Frank de Brabander). NewPing is no longer needed.
3. Open `openbot-esp32-mtv.ino` → Upload.
4. In the app, connect by **USB** (Android) or **Bluetooth "OpenBot: MTV"** (Android / iOS).

## Wiring (unchanged)
| Part | GPIO |
|---|---|
| Right motor IN1 (forward) / IN2 (back) / EN | 19 / 4 / 18 |
| Left motor IN1 (forward) / IN2 (back) / EN | 15 / 2 / 5 |
| Sonar middle trig / echo | 13 / 12 |
| Sonar left trig / echo | 26 / 25 |
| Sonar right trig / echo | 32 / 33 |
| LEDs left / right | 14 / 27 |
| LCD SDA / SCL (address 0x27 or 0x3F, auto-detected) | 21 / 22 |

## First test (wheels off the ground)
| You see | Fix |
|---|---|
| One wheel turns backward on "forward" | set `MOTOR_L_REVERSED` or `MOTOR_R_REVERSED` to 1 |
| Left and right distance swapped on the LCD | swap the two sonar pin lines |
| Wheels hum but don't turn at low speed | raise `MOTOR_MIN_DUTY` (60 → 80) |
| Reverse doesn't work and your driver has PWM + DIR pins | set `MOTOR_DRIVER DRIVER_PWM_DIR` |
| LCD stays blank | turn the blue contrast screw on the back of the LCD |

## LCD
```
L 200 R 200  BLE     <- motor outputs, link: BLE / USB / WAIT / LOST / STOP
L 40 M120 Rmax       <- left / middle / right distance in cm (--- = sensor fault)
```

## Safety
- Forward speed slows down from 50 cm and stops at 15 cm (middle sonar). For the side sonars it's 25 cm / 10 cm.
  Turning on the spot and reversing still work, so you can drive away.
- Bluetooth disconnects → robot stops.
- Android USB heartbeat stops arriving → robot stops after 750 ms.

## Hardware warnings
- **HC-SR04 echo is 5 V; the ESP32 is 3.3 V.** Put a divider on each echo wire (1 kΩ + 2 kΩ).
- GPIO 2, 5, 12 and 15 are boot "strapping" pins. If the board won't boot or flash, unplug
  the sonar on GPIO 12 and the motor driver while flashing.
