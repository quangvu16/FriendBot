//
//  Created by Sparsh Jain on 20/08/22.
//

import Foundation
import GameController

public var connectedController: GCController?

class GameController: GCPhysicalInputProfile {
    static let shared: GameController = GameController()
    private let maximumControllerCount: Int = 1
    private(set) var controllers = Set<GCController>()
    var audioPlayer = AudioPlayer.shared
    private var panRecognizer: UIPanGestureRecognizer!
    var selectedSpeedMode: SpeedMode = SpeedMode.SLOW
    var selectedControlMode: ControlMode = ControlMode.GAMEPAD
    /// The active drive mode. Tilt mode is driven by the phone's sensors, so
    /// selecting it starts the tilt engine and leaving it stops the robot.
    var selectedDriveMode: DriveMode = DriveMode.JOYSTICK {
        didSet {
            if selectedDriveMode == .TILT {
                TiltDrive.shared.start()
            } else if oldValue == .TILT {
                TiltDrive.shared.stop()
            }
        }
    }
    var vehicleControl = Control()
    let bluetooth = bluetoothDataController.shared
    let dataLogger = DataLogger.shared
    var controlData: String = ""
    var resetControl: Bool = true
    
    /// Initialization routine
    override init() {
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(didConnectController), name: NSNotification.Name.GCControllerDidConnect, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(didDisconnectController), name: NSNotification.Name.GCControllerDidDisconnect, object: nil)
        GCController.startWirelessControllerDiscovery {
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self, name: NSNotification.Name.GCControllerDidConnect, object: nil)
        NotificationCenter.default.removeObserver(self, name: NSNotification.Name.GCControllerDidDisconnect, object: nil)
        GCController.stopWirelessControllerDiscovery()
    }

    /// Callback function for the GCControllerDidConnect event
    @objc func didConnectController(_ notification: Notification) {
        guard controllers.count < maximumControllerCount else {
            return
        }
        let controller = notification.object as! GCController
        controllers.insert(controller)
        connectedController = controller
        let nc = NotificationCenter.default
        nc.post(name: Notification.Name(rawValue: Strings.controllerConnected), object: self)
    }

    /// Callback function for the GCControllerDidDisconnect event
    @objc func didDisconnectController(_ notification: Notification) {
        let controller = notification.object as! GCController
        controllers.remove(controller)
    }

    /// Gamepad control processing routine
    ///
    /// - Parameters:
    ///    - mode: drive mode (DUAL, GAME, JOYSTICK or TILT)
    ///    - gamepad: extended Gamepad profile
    /// - Returns: robot left and right controls
    public func processJoystickInput(mode: DriveMode, gamepad: GCExtendedGamepad) -> Control {
        switch (mode) {
        case .TILT:
            // Tilt is driven by the phone's sensors; the gamepad sticks send
            // nothing so they cannot fight the tilt engine.
            return Control()
        case .DUAL:
            return convertDualToControl(leftStick: gamepad.leftThumbstick.yAxis.value, rightStick: gamepad.rightThumbstick.yAxis.value)

        case .GAME:
            let rightTrigger = gamepad.rightTrigger.value
            let leftTrigger = gamepad.leftTrigger.value
            var steeringOffset: Float = gamepad.leftThumbstick.xAxis.value
            if (steeringOffset == 0) {
                steeringOffset = gamepad.dpad.yAxis.value
            }
            if (steeringOffset == 0) {
                steeringOffset = gamepad.rightThumbstick.xAxis.value
            }
            return convertGameToControl(leftTrigger: leftTrigger, rightTrigger: rightTrigger, steeringOffset: steeringOffset)
        case .JOYSTICK:

            var yAxis: Float = gamepad.leftThumbstick.yAxis.value
            if (yAxis == 0) {
                yAxis = gamepad.dpad.yAxis.value
            }
            if (yAxis == 0) {
                yAxis = gamepad.rightThumbstick.yAxis.value
            }
            var xAxis: Float = gamepad.leftThumbstick.xAxis.value
            if (xAxis == 0) {
                xAxis = gamepad.dpad.xAxis.value
            }
            if (xAxis == 0) {
                xAxis = gamepad.rightThumbstick.xAxis.value
            }
            return convertJoystickToControl(xAxis: xAxis, yAxis: yAxis)
        }
    }

    /// Function to return dual mode control values to openbot device movement values.
    ///
    /// - Parameters:
    ///     - leftStick: left joystick yAxis values.
    ///     - rightStick: right joystick yAxis values.
    /// - Returns: return control object with left and right values.
    public func convertDualToControl(leftStick: Float, rightStick: Float) -> Control {
        return Control(left: leftStick, right: rightStick)
    }

    /// Function to convert controller input for joystick mode to values to return to openbot device to control the navigation.
    ///
    /// - Parameters:
    ///     - xAxis: xAxis position of the joystick
    ///     - yAxis: yAxis position of the joystick
    /// - Returns: returns control object with left and right values.
    public func convertJoystickToControl(xAxis: Float, yAxis: Float) -> Control {
        var left = yAxis
        var right = yAxis

        if (left >= 0) {
            left += xAxis
        } else {
            left -= xAxis
        }
        if (right >= 0) {
            right -= xAxis
        } else {
            right += xAxis
        }
        return Control(left: left, right: right)
    }

    /// Function to convert controller input for game mode to the values to return to openbot device to control the device movement.
    ///
    /// - Parameters:
    ///     - leftTrigger: left control of device.
    ///     - rightTrigger: right control of device.
    ///     - steeringOffset: offset in left and right directions.
    /// - Returns: Returns Control object with left and right values.
    public func convertGameToControl(leftTrigger: Float, rightTrigger: Float, steeringOffset: Float) -> Control {
        var left = rightTrigger - leftTrigger
        var right = rightTrigger - leftTrigger

        if (left >= 0) {
            left += steeringOffset
        } else {
            left -= steeringOffset
        }
        if (right >= 0) {
            right -= steeringOffset
        } else {
            right += steeringOffset
        }
        return Control(left: left, right: right)
    }

    /// Function to process the controller keys and create events out of them.
    ///
    /// - Parameters:
    ///     - element: takes controllerElement as input and using localized_names of controller and map it to Keymap[enum].
    /// - Returns: Events
    public func processControllerKeyData(element: GCControllerElement) -> Any {

        switch (element) {
        case connectedController?.extendedGamepad?.buttonA:
            if (connectedController?.extendedGamepad?.buttonA.isPressed == false) {
                return CMD_Events.TOGGLE_LOGS
            }
        case connectedController?.extendedGamepad?.buttonOptions:
            if (connectedController?.extendedGamepad?.buttonOptions?.isPressed == false) {
                return CMD_Events.TOGGLE_NOISE
            }
        case connectedController?.extendedGamepad?.leftShoulder:
            if (connectedController?.extendedGamepad?.leftShoulder.isPressed == false) {
                return CMD_Events.CMD_DRIVE_MODE
            }
        case connectedController?.extendedGamepad?.rightShoulder:
            if (connectedController?.extendedGamepad?.rightShoulder.isPressed == false) {
                return CMD_Events.TOGGLE_NETWORK
            }
        case connectedController?.extendedGamepad?.leftThumbstickButton:
            if (connectedController?.extendedGamepad?.leftThumbstickButton?.isPressed == false) {
                return CMD_Events.CMD_SPEED_DOWN
            }
        case connectedController?.extendedGamepad?.rightThumbstickButton:
            if (connectedController?.extendedGamepad?.rightThumbstickButton?.isPressed == false) {
                return CMD_Events.CMD_SPEED_UP
            }
        default:
            return ""
        }
        return ""
    }

    /// Main control update function
    func updateControllerValues() {
        if (connectedController == nil) {
            return
        }
        let controller = connectedController
        controller?.extendedGamepad?.valueChangedHandler = { [self] gamepad, element in
            if selectedDriveMode != .TILT {
                if resetControl == true {
                    sendControl(control: Control())
                } else {
                    let control = processJoystickInput(mode: selectedDriveMode, gamepad: gamepad)
                    sendControl(control: control)
                }
            }

            let keyCommand = processControllerKeyData(element: element)
            sendKeyUpdates(keyCommand: keyCommand)
        }
    }

    /// Send control input to the vehicle through BLE
    ///
    /// - Parameters:
    ///     - control: the control input to be sent to the robot
    func sendControl(control: Control) {
        if selectedControlMode == ControlMode.PHONE {
            return
        }
        if (control.getRight() != vehicleControl.getRight() || control.getLeft() != vehicleControl.getLeft()) {
            let left = Int((control.getLeft() * selectedSpeedMode.rawValue).rounded())
            let right = Int((control.getRight() * selectedSpeedMode.rawValue).rounded())
            vehicleControl = control
            dataLogger.setControlLogs(left: (String(left)), right: String(right))
            controlData = String(left) + " " + String(right)
            bluetooth.sendData(payload: "c" + String(left) + "," + String(right) + "\n")
            NotificationCenter.default.post(name: .updateSpeedLabel, object: String(Int(left)) + "," + String(Int(right)))
        }
    }

    /// Send control input to the vehicle through BLE
    ///
    /// - Parameters:
    ///     - control: the control input to be sent to the robot
    func sendControlFromPhoneController(control: Control) {
        if (control.getRight() != vehicleControl.getRight() || control.getLeft() != vehicleControl.getLeft()) {
            let left = Int((control.getLeft() * selectedSpeedMode.rawValue).rounded())
            let right = Int((control.getRight() * selectedSpeedMode.rawValue).rounded())
            vehicleControl = control
            dataLogger.setControlLogs(left: (String(left)), right: String(right))
            controlData = String(left) + " " + String(right)
            bluetooth.sendData(payload: "c" + String(left) + "," + String(right) + "\n")
            NotificationCenter.default.post(name: .updateSpeedLabel, object: String(Int(left)) + "," + String(Int(right)))
        }
    }

    /// Only send commands that don't make the robot move (mode/speed toggles etc.)
    /// Useful for the controller mapping fragment or the main screen.
    ///
    /// - Parameters:
    ///     - keyCommand: the control input to be sent to the robot
    @objc func sendKeyUpdates(keyCommand: Any) {
        switch (keyCommand) {
        case CMD_Events.TOGGLE_LOGS:
            NotificationCenter.default.post(name: .logData, object: nil)
            break
        case CMD_Events.TOGGLE_NETWORK:
            NotificationCenter.default.post(name: .toggleNetworks, object: nil)
            break
        case CMD_Events.CMD_SPEED_UP:
            increaseSpeedMode()
            break
        case CMD_Events.CMD_SPEED_DOWN:
            decreaseSpeedMode()
            break
        case CMD_Events.CMD_DRIVE_MODE:
            NotificationCenter.default.post(name: .updateDriveMode, object: nil)
        default:
            break
        }
    }

    /// function to increase speed mode and notify UI on main thread
    func increaseSpeedMode(){
        switch selectedSpeedMode {
        case .SLOW:
            selectedSpeedMode = .NORMAL;
            break;
        case .NORMAL:
            selectedSpeedMode = .FAST;
            break;
        case .FAST:
            return
        }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .increaseSpeedMode, object: nil)
        }
    }

    /// function to decrease speed mode and notify UI on main thread
    func decreaseSpeedMode(){
        switch selectedSpeedMode {
        case .SLOW:
            return;
        case .NORMAL:
            selectedSpeedMode = .SLOW;
            break;
        case .FAST:
            selectedSpeedMode = .NORMAL;
            break;
        }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .decreaseSpeedMode, object: nil)
        }
    }
}

