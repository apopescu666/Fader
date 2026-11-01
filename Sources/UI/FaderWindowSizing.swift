import SwiftUI
import AppKit

/// Own geometry persistence avoids autosave names changed internally by SwiftUI.
struct FaderWindowSizing: NSViewRepresentable {
    final class Probe: NSView {
        private let memory: FaderLayoutMemory
        private weak var trackedWindow: NSWindow?
        private var observers: [NSObjectProtocol] = []
        private var pending: DispatchWorkItem?
        private var pendingFrame: NSRect?
        private var restoring = false
        private var fullScreenTransition = false

        init(memory: FaderLayoutMemory = FaderLayoutMemory()) {
            self.memory = memory
            super.init(frame: .zero)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else {
                flush()
                observers.forEach { NotificationCenter.default.removeObserver($0) }
                observers.removeAll()
                trackedWindow = nil
                return
            }
            track(window)
        }
        func track(_ window: NSWindow) {
            guard trackedWindow !== window else { return }
            flush()
            observers.forEach { NotificationCenter.default.removeObserver($0) }
            observers.removeAll()
            trackedWindow = window
            restoring = true
            observe(NSWindow.didResizeNotification, object: window) { [weak self] in self?.changed() }
            observe(NSWindow.didMoveNotification, object: window) { [weak self] in self?.changed() }
            observe(NSWindow.didEndLiveResizeNotification, object: window) { [weak self] in self?.changed(); self?.flush() }
            observe(NSWindow.willCloseNotification, object: window) { [weak self] in self?.changed(); self?.flush() }
            observe(NSApplication.willTerminateNotification, object: nil) { [weak self] in self?.changed(); self?.flush() }
            observe(NSWindow.willEnterFullScreenNotification, object: window) { [weak self] in
                self?.changed(); self?.flush(); self?.fullScreenTransition = true
            }
            observe(NSWindow.didEnterFullScreenNotification, object: window) { [weak self] in self?.fullScreenTransition = true }
            observe(NSWindow.willExitFullScreenNotification, object: window) { [weak self] in self?.fullScreenTransition = true }
            observe(NSWindow.didExitFullScreenNotification, object: window) { [weak self] in
                self?.fullScreenTransition = false; self?.changed()
            }
            DispatchQueue.main.async { [weak self, weak window] in
                guard let self, let window, self.trackedWindow === window else { return }
                let saved = self.memory.windowFrame()
                let proposed = saved ?? window.frame
                // Select the screen containing the saved frame; recover safely if
                // that monitor was disconnected. No default-size reset on launch.
                let screens = NSScreen.screens
                let target = screens.max { a, b in
                    let aa = a.visibleFrame.intersection(proposed)
                    let bb = b.visibleFrame.intersection(proposed)
                    return max(0, aa.width) * max(0, aa.height) < max(0, bb.width) * max(0, bb.height)
                }
                let screen = (target?.visibleFrame.intersects(proposed) == true ? target : nil) ?? window.screen ?? NSScreen.main
                if let screen {
                    window.setFrame(FaderLayoutMemory.fit(proposed, inside: screen.visibleFrame.insetBy(dx: 16, dy: 16)), display: false)
                } else if let saved { window.setFrame(saved, display: false) }
                self.restoring = false
            }
        }
        private func observe(_ name: Notification.Name, object: AnyObject?, action: @escaping () -> Void) {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: object, queue: .main) { _ in action() })
        }
        private func changed() {
            guard !restoring, !fullScreenTransition, let window = trackedWindow,
                  !window.styleMask.contains(.fullScreen), !window.isMiniaturized,
                  FaderLayoutMemory.valid(window.frame) else { return }
            pendingFrame = window.frame
            pending?.cancel()
            // Resize/move notifications only update in-memory geometry during a
            // drag. One coalesced preferences write follows; close/quit flush it.
            let work = DispatchWorkItem { [weak self] in self?.flush() }
            pending = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
        }
        func flush() {
            pending?.cancel(); pending = nil
            if let pendingFrame { memory.save(window: pendingFrame) }
            pendingFrame = nil
        }
        deinit {
            pending?.cancel()
            if let pendingFrame { memory.save(window: pendingFrame) }
            observers.forEach { NotificationCenter.default.removeObserver($0) }
        }
    }
    func makeNSView(context: Context) -> NSView { Probe() }
    func updateNSView(_ view: NSView, context: Context) { }
}
