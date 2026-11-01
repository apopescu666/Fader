import Foundation
import SwiftUI
import AppKit

@main
struct LayoutMemoryTests {
    static func check(_ condition: @autoclosure () -> Bool, _ message: String, line: Int = #line) {
        if !condition() {
            FileHandle.standardError.write(Data("FAIL line \(line): \(message)\n".utf8))
            exit(1)
        }
    }
    static func pump() {
        var done = false
        DispatchQueue.main.async { done = true }
        let deadline = Date().addingTimeInterval(2)
        while !done && Date() < deadline { _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.005)) }
        check(done, "Main queue ready")
    }
    static func split(width: CGFloat) -> NSSplitView {
        let view = NSSplitView(frame: NSRect(x: 0, y: 0, width: width, height: 700))
        view.isVertical = true
        view.addSubview(NSView()); view.addSubview(NSView())
        return view
    }
    static func main() {
        let domain = CommandLine.arguments[1]
        let mode = CommandLine.arguments[2]
        let defaults = UserDefaults(suiteName: domain)!
        let memory = FaderLayoutMemory(defaults: defaults)
        if mode == "read" {
            check(memory.windowFrame() == CGRect(x: 123, y: 234, width: 1450, height: 903), "Window survives a new process")
            check(abs((memory.fraction(for: "sidebar", available: 2000) ?? 0) - 0.34) < 0.0001, "Sidebar survives a new process")
            check(abs((memory.fraction(for: "queue", available: 2000) ?? 0) - 0.61) < 0.0001, "Queue survives a new process")
            defaults.removePersistentDomain(forName: domain)
            print("PASS new-process restore: exact window frame and pane ratios")
            return
        }
        defaults.removePersistentDomain(forName: domain)
        check(memory.windowFrame() == nil, "New profile has no fake frame")
        let frame = CGRect(x: 123, y: 234, width: 1450, height: 903)
        memory.save(window: frame)
        check(FaderLayoutMemory(defaults: defaults).windowFrame() == frame, "Stored window geometry")
        memory.save(window: CGRect(x: CGFloat.nan, y: 0, width: 1200, height: 760))
        memory.save(window: CGRect(x: 0, y: 0, width: 0, height: 0))
        check(memory.windowFrame() == frame, "Invalid startup frames cannot replace valid geometry")
        let screen = CGRect(x: 0, y: 0, width: 1000, height: 800)
        let fitted = FaderLayoutMemory.fit(CGRect(x: 5000, y: 5000, width: 1500, height: 1000), inside: screen)
        check(screen.contains(fitted) && fitted.size == screen.size, "Disconnected monitor recovery")
        defaults.set(350.0, forKey: "legacy")
        check(memory.fraction(for: "legacy", available: 1000) == 0.35, "Old absolute split positions still load")
        memory.save(split: "sidebar", position: 350, fraction: 0.34)
        memory.save(split: "queue", position: 700, fraction: 0.61)
        memory.save(split: "sidebar", position: .nan, fraction: 0.9)
        check(memory.fraction(for: "sidebar", available: 3000) == 0.34, "Invalid split changes ignored")

        // Invisible native windows/views only; no global mouse events or user app control.
        _ = NSApplication.shared
        NSApp.setActivationPolicy(.prohibited)
        let visible = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1600, height: 1000)
        let testFrame = NSRect(x: visible.minX + 60, y: visible.minY + 60,
                              width: min(1000, visible.width - 120), height: min(700, visible.height - 120))
        memory.save(window: testFrame)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 600, height: 400),
                              styleMask: [.titled, .resizable, .closable], backing: .buffered, defer: true)
        window.isReleasedWhenClosed = false
        let probe = FaderWindowSizing.Probe(memory: memory)
        window.contentView!.addSubview(probe)
        pump()
        check(window.frame == testFrame, "Own frame restores independently of SwiftUI autosave names")
        let changed = NSRect(x: testFrame.minX + 10, y: testFrame.minY + 10, width: testFrame.width - 30, height: testFrame.height - 30)
        window.setFrame(changed, display: false)
        NotificationCenter.default.post(name: NSWindow.didResizeNotification, object: window)
        probe.flush()
        check(memory.windowFrame() == window.frame, "Native resize notification saved")
        window.setFrame(NSRect(x: changed.minX + 10, y: changed.minY + 10, width: changed.width, height: changed.height), display: false)
        NotificationCenter.default.post(name: NSWindow.willCloseNotification, object: window)
        check(memory.windowFrame() == window.frame, "Close flushes final move without a delay")
        probe.removeFromSuperview()
        window.close()

        typealias C = FaderAccentNativeSplit<Text, Text>.Coordinator
        let first = split(width: 1500)
        let c = C(key: "native", minFirst: 220, minSecond: 633,
                  initialFraction: 0.23, initialFirstMaximum: 330, memory: memory)
        first.delegate = c; c.layout(first)
        first.subviews[0].frame.size.width = 450
        first.subviews[1].frame.origin.x = 450 + first.dividerThickness
        first.subviews[1].frame.size.width = first.bounds.width - 450 - first.dividerThickness
        // Modern AppKit's user-resize notification must work without mouseDown interception.
        c.splitViewDidResizeSubviews(Notification(name: NSSplitView.didResizeSubviewsNotification,
            object: first, userInfo: ["NSSplitViewUserResizeKey": true]))
        c.flush()
        let expected = 450 / (first.bounds.width - first.dividerThickness)
        check(abs((memory.fraction(for: "native", available: 9999) ?? 0) - expected) < 0.0001, "Native divider user event persists without tracking flag")
        let second = split(width: 1800)
        let c2 = C(key: "native", minFirst: 220, minSecond: 633,
                   initialFraction: 0.23, initialFirstMaximum: 330, memory: memory)
        second.delegate = c2; c2.layout(second)
        check(abs(second.subviews[0].frame.width - (second.bounds.width - second.dividerThickness) * expected) < 0.1,
              "Recreated split restores saved proportion")
        second.setFrameSize(NSSize(width: 900, height: 700)); c2.layout(second)
        c2.flush()
        check(abs((memory.fraction(for: "native", available: 900) ?? 0) - expected) < 0.0001,
              "Automatic/minimum-constrained layout must not overwrite user preference")
        second.setFrameSize(NSSize(width: 1800, height: 700)); c2.layout(second)
        check(abs(second.subviews[0].frame.width - (second.bounds.width - second.dividerThickness) * expected) < 0.1,
              "Preference survives temporary narrow window")
        memory.save(window: frame)
        defaults.synchronize() // Test-only cross-process persistence barrier.
        print("PASS geometry: native invisible resize/move/close, user divider notifications, screen fit, legacy keys, invalid input and automatic-resize guards")
    }
}
