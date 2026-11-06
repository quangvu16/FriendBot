//
// Created by Sparsh Jain on 25/08/22.
//

struct Strings {
    // Screens
    static var OpenBot: String { L10n.string("OpenBot") }
    static var freeRoam: String { L10n.string("freeRoam") }
    static var dataCollection: String { L10n.string("dataCollection") }
    static var controllerMapping: String { L10n.string("controllerMapping") }
    static var autoPilot: String { L10n.string("autoPilot") }
    static var objectTracking: String { L10n.string("objectTracking") }
    static var modelManagement: String { L10n.string("modelManagement") }
    static var robotInfo: String { L10n.string("robotInfo") }
    static var navigation: String { L10n.string("navigation") }
    static var gpsMission: String { L10n.string("gpsMission") }

    // Misc
    static var controller: String { L10n.string("controller") }
    static var speed: String { L10n.string("speed") }
    static var driveMode: String { L10n.string("driveMode") }
    static var gamepad: String { L10n.string("gamepad") }
    static var phone: String { L10n.string("phone") }
    static var web: String { L10n.string("web") }
    static var joystick: String { L10n.string("joystick") }
    static var game: String { L10n.string("game") }
    static var dual: String { L10n.string("dual") }
    static var tilt: String { L10n.string("drive_mode_tilt") }
    static var slow: String { L10n.string("slow") }
    static var medium: String { L10n.string("medium") }
    static var fast: String { L10n.string("fast") }
    static var logData: String { L10n.string("logData") }
    static var previewResolutionMedium: String { L10n.string("previewResolutionMedium") }
    static var previewResolutionLow: String { L10n.string("previewResolutionLow") }
    static var previewResolutionHigh: String { L10n.string("previewResolutionHigh") }
    static var low: String { L10n.string("low") }
    static var high: String { L10n.string("high") }
    static var modelResolution: String { L10n.string("modelResolution") }
    static var server: String { L10n.string("server") }
    static let expendSetting: String = "expendSetting"
    static var preview: String { L10n.string("preview") }
    static var training: String { L10n.string("training") }
    static var sensorData: String { L10n.string("sensorData") }
    static var vehicle: String { L10n.string("vehicle") }
    static var gps: String { L10n.string("gps") }
    static var accelerometer: String { L10n.string("accelerometer") }
    static var magnetic: String { L10n.string("magnetic") }
    static var gyroscope: String { L10n.string("gyroscope") }
    static var delay: String { L10n.string("delay") }
    static let images: String = "images"
    static let sensor: String = "sensor_data"
    static let timestamp: String = "timestamp[ns],frame\n"
    static let crop: String = "crop.jpeg"
    static let underscore: String = "_"
    static let comma: String = ","
    static let newLine: String = "\n"
    static let forwardSlash: String = "/"
    static var Autopilot: String { L10n.string("Autopilot") }
    static var ObjectTracking: String { L10n.string("ObjectTracking") }
    static var autoMode: String { L10n.string("autoMode") }
    static var model: String { L10n.string("model") }
    static var input: String { L10n.string("input") }
    static var device: String { L10n.string("device") }
    static var threads: String { L10n.string("threads") }
    static var object: String { L10n.string("object") }
    static var confidence: String { L10n.string("confidence") }
    static var dynamicSpeed: String { L10n.string("dynamicSpeed") }

    // Settings
    static var camera: String { L10n.string("camera") }
    static var microphone: String { L10n.string("microphone") }
    static var location: String { L10n.string("location") }
    static var permission: String { L10n.string("permission") }
    static let videoStreaming: String = "Video Streaming"
    static var bluetooth: String { L10n.string("bluetooth") }
    static var general: String { L10n.string("general") }
    static var language: String { L10n.string("language") }
    static var languageSubtitle: String { L10n.string("languageSubtitle") }
    static var systemDefault: String { L10n.string("systemDefault") }
    static var applyingLanguage: String { L10n.string("applyingLanguage") }
    static var webSignalingServer: String { L10n.string("webSignalingServer") }
    static var webSignalingServerPlaceholder: String { L10n.string("webSignalingServerPlaceholder") }

    // Notifications
    static let controllerConnected: String = "connectedWithControllerSuccessfully"
    static let clickSetting: String = "clickSetting"
    static let cancelButton: String = "cancelButton"
    static let switchCamera: String = "switchCamera"
    static let ble: String = "ble"
    static let logDataNotify: String = "log_data"
    static let updateSpeedMode: String = "update_speed"
    static let updateControlMode: String = "update_control"
    static let updateDriveMode: String = "update_drive_mode"
    static let updateResolution: String = "updateResolution"
    static let updatePreview: String = "updatePreview"
    static let updateTraining: String = "updateTraining"
    static let updateSensorsForLog: String = "updateSensorsForLog"

    // Screen Identifiers
    static let ScreenFreeRoam: String = "freeRoam"
    static let ScreenDataCollection: String = "dataCollection"
    static let ScreenControllerMapping: String = "controllerMapping"
    static let AutopilotFragment: String = "AutopilotFragment"
    static let ObjectTrackingFragment: String = "ObjectTrackingFragment"
    static let ScreenModelManagement: String = "ScreenModelManagement"
    static let ScreenBottomSheet: String = "ScreenBottomSheet"
    static let ScreenRobotInfo: String = "ScreenRobotInfo"
    static let ScreenNavigation: String = "ScreenNavigation"
    static let ScreenGpsMission: String = "ScreenGpsMission"
    /// Built in code (VoiceControlController), not in the storyboard.
    static let ScreenVoiceControl: String = "ScreenVoiceControl"
    /// Built in code (RemoteMicController), not in the storyboard.
    static let ScreenRemoteMic: String = "ScreenRemoteMic"

    // UIVIew Identifiers
    static let secondView: String = "secondView"
    static let bluetoothScreen: String = "bluetoothScreen"

    // Logging Headers
    static let accelerationHeader: String = "timestamp[ns] x[m/s^2], y[m/s^2], z[m/s^2]\n"
    static let locationCoordinatesHeader: String = "timestamp latitude, longitude\n"
    static let gyroscopeHeader: String = "timestamp[ns], x[rad/s], yx[rad/s], zx[rad/s]\n"
    static let magnetometerHeader: String = "timestamp[ns] x[uT], y[uT], z[uT]\n"
    static let gpsHeader: String = "timestamp latitude, longitude, altitude[m], speed[m/s]\n"
    static let bumperHeader: String = "timestamp[ns], bumper\n"
    static let ctrlHeader: String = "timestamp[ns], leftCtrl, rightCtrl\n"
    static let inferenceTimeHeader: String = "frame, inferenceTime [ns]\n"
    static let voltageHeader: String = "timestamp[ns], batteryVoltage\n"
    static let wheelsHeader: String = "timestamp[ns], leftWheel, rightWheel\n"
    static let motionHeader: String = "timestamp[ns]"

    // Bluetooth Status
    static var connect: String { L10n.string("connect") }
    static var disconnect: String { L10n.string("disconnect") }
    static var connecting: String { L10n.string("connecting") }
    static var disconnecting: String { L10n.string("disconnecting") }

    // Model management
    static var modelDetails: String { L10n.string("modelDetails") }
    static var name: String { L10n.string("name") }
    static var type: String { L10n.string("type") }
    static var `class`: String { L10n.string("class") }
    static var inputOfModel: String { L10n.string("inputOfModel") }
    /// Functional file-extension constant (file lookups, name matching) -- must stay ".tflite" in
    /// every language. See `tfliteLabel` for the translated on-screen copy.
    static let tflite: String = ".tflite"
    static var tfliteLabel: String { L10n.string("tfliteLabel") }
    static var cancel: String { L10n.string("cancel") }
    static var done: String { L10n.string("done") }
    static var file: String { L10n.string("file") }
    static var url: String { L10n.string("url") }
    static var addNewModel: String { L10n.string("addNewModel") }

    // Robot Info
    static var voltageDivider: String { L10n.string("voltageDivider") }
    static var bumperText: String { L10n.string("bumperText") }
    static var wheelOdometer: String { L10n.string("wheelOdometer") }
    static var status: String { L10n.string("status") }
    static var motors: String { L10n.string("motors") }
    static var forward: String { L10n.string("forward") }
    static var backward: String { L10n.string("backward") }
    static var stopLabel: String { L10n.string("stopLabel") }
    static var readings: String { L10n.string("readings") }
    static var battery: String { L10n.string("battery") }
    static var speedText: String { L10n.string("speedText") }
    static var sendCommand: String { L10n.string("sendCommand") }

    // Navigation
    static var setGoal: String { L10n.string("setGoal") }
    static var setGoalText: String { L10n.string("setGoalText") }
    static var left: String { L10n.string("left") }
    static var meter: String { L10n.string("meter") }
    static var startLabel: String { L10n.string("startLabel") }
    static var canceled: String { L10n.string("canceled") }
    static var info: String { L10n.string("info") }
    static var restart: String { L10n.string("restart") }

    // Settings > Permissions
    static var important: String { L10n.string("important") }
    static var cameraAccessRequiredMessage: String { L10n.string("cameraAccessRequiredMessage") }
    /// Format string with one %@ placeholder for the (already-localized) permission name, e.g. "Please allow %@ access for OpenBot".
    static var allowPermissionMessageFormat: String { L10n.string("allowPermissionMessageFormat") }
    /// Format string with one %@ placeholder for the (already-localized) permission name, e.g. "Allow %@".
    static var allowButtonFormat: String { L10n.string("allowButtonFormat") }

    // Logout / open-code UI
    static var logOut: String { L10n.string("logOut") }
    static var confirmLogoutTitle: String { L10n.string("confirmLogoutTitle") }
    static var confirmLogoutMessage: String { L10n.string("confirmLogoutMessage") }

    // Tab bar
    static var homeTab: String { L10n.string("homeTab") }
    static var profileTab: String { L10n.string("profileTab") }

    // Loading / generic alerts
    static var loadingTitle: String { L10n.string("loadingTitle") }
    static var loadingProfileMessage: String { L10n.string("loadingProfileMessage") }
    static var loadingDataMessage: String { L10n.string("loadingDataMessage") }
    static var pleaseWaitMessage: String { L10n.string("pleaseWaitMessage") }
    static var errorHeading: String { L10n.string("errorHeading") }
    static var genericErrorMessage: String { L10n.string("genericErrorMessage") }
    static var okAction: String { L10n.string("okAction") }

    // Profile screen
    static var myProfileHeading: String { L10n.string("myProfileHeading") }
    static var setupProfilePromptLine1: String { L10n.string("setupProfilePromptLine1") }
    static var setupProfilePromptLine2: String { L10n.string("setupProfilePromptLine2") }
    static var editProfile: String { L10n.string("editProfile") }
    static var logoutMenuItem: String { L10n.string("logoutMenuItem") }
    static var firstNameFieldLabel: String { L10n.string("firstNameFieldLabel") }
    static var lastNameFieldLabel: String { L10n.string("lastNameFieldLabel") }
    static var dateOfBirthFieldLabel: String { L10n.string("dateOfBirthFieldLabel") }
    static var emailFieldLabel: String { L10n.string("emailFieldLabel") }
    static var unknownFallback: String { L10n.string("unknownFallback") }
    static var saveChangesButton: String { L10n.string("saveChangesButton") }
    static var profileUpdatedSuccessToast: String { L10n.string("profileUpdatedSuccessToast") }
    static var profilePictureLoadErrorToast: String { L10n.string("profilePictureLoadErrorToast") }
    static var signInWithGoogle: String { L10n.string("signInWithGoogle") }

    // Robot Info headings
    static var robotTypeHeading: String { L10n.string("robotTypeHeading") }
    static var sensorsHeading: String { L10n.string("sensorsHeading") }
    /// Format string with one %@ placeholder for the battery reading value, e.g. "Battery %@ V".
    static var batteryReadingFormat: String { L10n.string("batteryReadingFormat") }

    // Point Goal Navigation
    static var goalReachedMessage: String { L10n.string("goalReachedMessage") }

    // Model Management filter dropdown
    static var modelFilterAll: String { L10n.string("modelFilterAll") }
    static var modelFilterAllCaps: String { L10n.string("modelFilterAllCaps") }
    static var modelFilterAutoPilot: String { L10n.string("modelFilterAutoPilot") }
    static var modelFilterDetector: String { L10n.string("modelFilterDetector") }
    static var modelFilterNavigation: String { L10n.string("modelFilterNavigation") }

    // Data Collection / Autopilot server dropdown default state
    static var noServer: String { L10n.string("noServer") }
}
