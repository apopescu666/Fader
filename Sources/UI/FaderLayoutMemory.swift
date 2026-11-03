import Foundation

/// Stable geometry keys independent of SwiftUI-generated window class names.
/// No library, playback or search settings are read or written here.
final class FaderLayoutMemory {
    private let defaults: UserDefaults
    private let windowKey = "Fader.layout.mainWindow.frame.v1"
    init(defaults: UserDefaults = FaderStorage.defaults) { self.defaults = defaults }

    func windowFrame() -> CGRect? {
        if let values = defaults.dictionary(forKey: windowKey),
           let x = values["x"] as? Double, let y = values["y"] as? Double,
           let width = values["width"] as? Double, let height = values["height"] as? Double {
            let frame = CGRect(x: x, y: y, width: width, height: height)
            if Self.valid(frame) { return frame }
        }
        if let legacy = defaults.string(forKey: "NSWindow Frame Fader.MainWindow") {
            let numbers = legacy.split(whereSeparator: \.isWhitespace).compactMap { Double($0) }
            if numbers.count >= 4 {
                let frame = CGRect(x: numbers[0], y: numbers[1], width: numbers[2], height: numbers[3])
                if Self.valid(frame) { return frame }
            }
        }
        return nil
    }
    func save(window frame: CGRect) {
        guard Self.valid(frame) else { return }
        defaults.set(["x": Double(frame.minX), "y": Double(frame.minY),
                      "width": Double(frame.width), "height": Double(frame.height)], forKey: windowKey)
    }
    func fraction(for key: String, available: CGFloat) -> CGFloat? {
        if let stored = defaults.object(forKey: key + ".fraction") as? NSNumber {
            let value = stored.doubleValue
            if value.isFinite && value > 0 && value < 1 { return CGFloat(value) }
        }
        if let stored = defaults.object(forKey: key) as? NSNumber,
           stored.doubleValue.isFinite, stored.doubleValue > 0, available > 0 {
            return min(max(CGFloat(stored.doubleValue) / available, 0.001), 0.999)
        }
        return nil
    }
    func save(split key: String, position: CGFloat, fraction: CGFloat) {
        guard position.isFinite, position > 0, fraction.isFinite, fraction > 0, fraction < 1 else { return }
        // Preserve the old absolute-position key for older builds, plus the ratio
        // used to restore proportions when the main window changes size.
        defaults.set(Double(position), forKey: key)
        defaults.set(Double(fraction), forKey: key + ".fraction")
    }
    static func valid(_ frame: CGRect) -> Bool {
        [frame.origin.x, frame.origin.y, frame.width, frame.height].allSatisfy(\.isFinite)
            && frame.width >= 100 && frame.height >= 100
    }
    static func fit(_ frame: CGRect, inside screen: CGRect) -> CGRect {
        guard valid(frame), screen.width > 0, screen.height > 0 else { return frame }
        let width = min(frame.width, screen.width)
        let height = min(frame.height, screen.height)
        return CGRect(x: min(max(frame.minX, screen.minX), screen.maxX - width),
                      y: min(max(frame.minY, screen.minY), screen.maxY - height),
                      width: width, height: height)
    }
}
