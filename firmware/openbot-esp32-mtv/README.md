# OpenBot firmware: ESP32 WROOM-32 + front sonar + claw + LCD (`OPENBOT == MTV`)

This replaces `firmware/openbot-esp32s3/openbot-esp32s3.ino` (the old MTV sketch).
The old sketch is still there, unchanged. The motor, LED and LCD wiring is the same — the two
side sonars are gone and their pins now drive the claw driver, so re-flash after rewiring.

## Flash it
1. Arduino IDE → Board: **ESP32 Dev Module**.
2. Library Manager → install **LiquidCrystal I2C** (Frank de Brabander). NewPing is no longer needed.
3. Open `openbot-esp32-mtv.ino` → Upload.
4. In the app, connect by **USB** (Android) or **Bluetooth "OpenBot: MTV"** (Android / iOS).

## Wiring
| Part | GPIO |
|---|---|
| Right motor IN1 (forward) / IN2 (back) / EN | 19 / 4 / 18 |
| Left motor IN1 (forward) / IN2 (back) / EN | 15 / 2 / 5 |
| Sonar front (middle) trig / echo | 13 / 12 |
| LEDs left / right | 14 / 27 |
| LCD SDA / SCL (address 0x27 or 0x3F, auto-detected) | 21 / 22 |
| Claw driver: signal (S+) / +5 V / GND | **32** / 5 V / GND |

The old side-sonar pins **26 / 25 / 33** are free. Driver board power comes from the 5 V rail
(the L298N's +5 V output or a buck converter), not the ESP32's 5 V pin.

## First test (wheels off the ground)
| You see | Fix |
|---|---|
| One wheel turns backward on "forward" | set `MOTOR_L_REVERSED` or `MOTOR_R_REVERSED` to 1 |
| LCD shows `Dist:---` | sensor fault → check the ECHO/TRIG wires (GPIO 12 / 13) and the divider |
| Wheels hum but don't turn at low speed | raise `MOTOR_MIN_DUTY` (60 → 80) |
| Reverse doesn't work and your driver has PWM + DIR pins | set `MOTOR_DRIVER DRIVER_PWM_DIR` |
| LCD stays blank | turn the blue contrast screw on the back of the LCD |
| Claw never moves | check the driver's 5 V, GND and signal wire (GPIO 32); raise `GRIPPER_MOVE_MS` |

## LCD
```
Dist:120 cm BLE      <- front distance + link: BLE / USB / WAIT / LOST / STOP
Claw: OPEN           <- claw: OPEN / CLOSED / MOVING (row blank without a gripper)
```

## Messages the app sends
`c<left>,<right>` drive · `h<ms>` heartbeat · `f` features · `s<ms>` sonar reports ·
`i<left>,<right>` LEDs · `g1` / `g0` / `g2` claw close / open / report · `a<ms>` approach mode.

## Safety
- Forward speed slows down from 50 cm and stops at 15 cm (front sonar). Turning on the spot and
  reversing still work, so you can drive away.
- Escape reflex: while driving and closer than 5 cm, back up and turn left (one sonar, so no
  side to prefer), at most 2 tries.
- While the claw is closed, readings closer than 13 cm (`GRIPPER_HELD_CM`) are treated as the
  held object, not an obstacle.
- Bluetooth disconnects → robot stops.
- Android USB heartbeat stops arriving → robot stops after 750 ms.

## Hardware warnings
- **HC-SR04 echo is 5 V; the ESP32 is 3.3 V.** Put a divider on the echo wire (1 kΩ + 2 kΩ).
- GPIO 2, 5, 12 and 15 are boot "strapping" pins. If the board won't boot or flash, unplug
  the sonar on GPIO 12 and the motor driver while flashing. GPIO 32 is not a strapping pin.
