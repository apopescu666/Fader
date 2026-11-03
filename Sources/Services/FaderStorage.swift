import Foundation

/// Production storage retains Fader 1.0 data. Isolated profiles are available
/// only to explicitly compiled test executables, never to the release app.
enum FaderStorage {
#if FADER_TESTING
    static let token: String = {
        if let raw = ProcessInfo.processInfo.environment["FADER_TEST_PROFILE"], let uuid = UUID(uuidString: raw) {
            return uuid.uuidString
        }
        return UUID().uuidString
    }()
    static let defaults = UserDefaults(suiteName: "com.fader.tests." + token)!
    static let supportComponent = "FaderTestProfiles/" + token
    static let serialImport = CommandLine.arguments.contains("--serial-import")
#elseif FADER_LITE_PREVIEW
    static let token = "persistent"
    static let defaults = UserDefaults(suiteName: "com.fader.litetest65.profile")!
    static let supportComponent = "FaderLiteTest65/persistent"
    static let serialImport = false
#else
    static let defaults = UserDefaults.standard
    static let supportComponent = "FaderPlayer"
    static let serialImport = false
#endif
    static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent(supportComponent, isDirectory: true)

    static func log(_ message: String) {
        print("FADER IMPORT: " + message)
    }
}
