import SwiftUI
import AppKit

struct SplitPositionMemory: NSViewRepresentable {
    let name: String
    let isVertical: Bool
    var dividerIndex: Int = 0

    func makeNSView(context: Context) -> TrackingView {
        let view = TrackingView()
        view.name = name
        view.expectedVertical = isVertical
        view.dividerIndex = dividerIndex
        return view
    }

    func updateNSView(_ view: TrackingView, context: Context) {
        view.name = name
        view.expectedVertical = isVertical
        view.dividerIndex = dividerIndex
        view.scheduleConnection()
    }

    final class TrackingView: NSView {
        var name = ""
        var expectedVertical = true
        var dividerIndex = 0

        private weak var split: NSSplitView?
        private var resizeObserver: NSObjectProtocol?
        private var scheduled = false
        private var initialized = false

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            scheduleConnection()
        }

        override func layout() {
            super.layout()

            // Also check after SwiftUI rearranges the layout.
            scheduleConnection()
        }

        func scheduleConnection() {
            guard !scheduled else { return }
            scheduled = true

            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.scheduled = false
                self.connect()
            }
        }

        private func connect() {
            guard window != nil else { return }

            var ancestor = superview
            var found: NSSplitView?

            while let view = ancestor {
                if let candidate = view as? NSSplitView,
                   candidate.isVertical == expectedVertical {
                    found = candidate
                    break
                }
                ancestor = view.superview
            }

            guard let target = found else { return }

            if split !== target {
                if let resizeObserver {
                    NotificationCenter.default.removeObserver(resizeObserver)
                }

                split = target
                initialized = false

                resizeObserver = NotificationCenter.default.addObserver(
                    forName: NSSplitView.didResizeSubviewsNotification,
                    object: target,
                    queue: .main
                ) { [weak self] notification in
                    self?.handleResize(notification)
                }
            }

            guard target.subviews.count >= 2,
                  target.bounds.width > 0,
                  target.bounds.height > 0 else {
                return
            }

            guard target.subviews.count > dividerIndex else { return }
            let minimum = target.minPossiblePositionOfDivider(at: dividerIndex)
            let maximum = target.maxPossiblePositionOfDivider(at: dividerIndex)

            guard maximum > minimum else { return }

            initialized = true

            // Do not interfere while the user is dragging.
            if NSApp.currentEvent?.type == .leftMouseDragged {
                return
            }

            guard FaderStorage.defaults.object(forKey: name) != nil
            else { return }

            let saved = CGFloat(
                FaderStorage.defaults.double(forKey: name)
            )

            let desired = min(max(saved, minimum), maximum)

            let pane = target.subviews[dividerIndex]
            let current = target.isVertical
                ? pane.frame.maxX
                : pane.frame.maxY

            // Avoid unnecessary layout cycles.
            guard abs(current - desired) > 1.5 else {
                return
            }

            target.setPosition(desired, ofDividerAt: dividerIndex)
        }

        private func handleResize(_ notification: Notification) {
            guard initialized else { return }

            // Only save genuine user divider movements.
            let userResize = (
                notification.userInfo?["NSSplitViewUserResizeKey"]
                as? NSNumber
            )?.boolValue == true

            let mouseDrag =
                NSApp.currentEvent?.type == .leftMouseDragged

            guard userResize || mouseDrag else {
                // A SwiftUI layout change is not a user resize.
                // Never restore synchronously inside NSSplitView.didResizeSubviewsNotification:
                // setPosition() emits that same notification and causes infinite recursion.
                scheduleConnection()
                return
            }

            savePosition()
        }

        private func savePosition() {
            guard let split,
                  split.subviews.count >= 2 else {
                return
            }

            guard split.subviews.count > dividerIndex else { return }
            let pane = split.subviews[dividerIndex]
            let position = split.isVertical
                ? pane.frame.maxX
                : pane.frame.maxY

            guard position.isFinite, position > 0 else {
                return
            }

            FaderStorage.defaults.set(
                Double(position),
                forKey: name
            )
        }

        deinit {
            if let resizeObserver {
                NotificationCenter.default.removeObserver(resizeObserver)
            }
        }
    }
}
