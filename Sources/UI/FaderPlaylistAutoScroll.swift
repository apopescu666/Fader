import SwiftUI
import AppKit

// FADER_P299C3_AUTOSCROLL
// Controls scrolling only; never modifies playlist or audio data.
final class FaderPlaylistAutoScrollController {

    weak var scrollView: NSScrollView?

    private var timer: Timer?
    private var lastDragUpdate = Date.distantPast

    private let edgeZone: CGFloat = 70
    private let minimumSpeed: CGFloat = 2
    private let maximumSpeed: CGFloat = 13

    func dragUpdated() {
        lastDragUpdate = Date()

        guard timer == nil else { return }

        let t = Timer(timeInterval: 1.0 / 60.0, repeats: true) {
            [weak self] _ in
            self?.tick()
        }

        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func tick() {
        guard Date().timeIntervalSince(lastDragUpdate) < 0.8,
              let scroll = scrollView,
              let window = scroll.window,
              let document = scroll.documentView else {
            stop()
            return
        }

        let clip = scroll.contentView
        let pointInWindow = window.convertPoint(
            fromScreen: NSEvent.mouseLocation
        )
        let point = clip.convert(pointInWindow, from: nil)

        let visible = clip.bounds
        let y = point.y - visible.minY
        let x = point.x - visible.minX

        guard x >= 0, x <= visible.width,
              y >= 0, y <= visible.height else {
            return
        }

        let zone = min(edgeZone, visible.height / 3)
        guard zone > 0 else { return }

        var delta: CGFloat = 0

        if y < zone {
            let intensity = (zone - y) / zone
            delta = -speed(intensity)
        } else if y > visible.height - zone {
            let intensity = (y - (visible.height - zone)) / zone
            delta = speed(intensity)
        }

        guard delta != 0 else { return }

        if !document.isFlipped {
            delta = -delta
        }

        let maximumY = max(
            0, document.frame.height - visible.height
        )

        let nextY = min(
            max(visible.minY + delta, 0),
            maximumY
        )

        guard abs(nextY - visible.minY) > 0.01 else {
            return
        }

        clip.scroll(
            to: NSPoint(x: visible.minX, y: nextY)
        )
        scroll.reflectScrolledClipView(clip)
    }

    private func speed(_ intensity: CGFloat) -> CGFloat {
        let t = min(max(intensity, 0), 1)
        return minimumSpeed
            + (maximumSpeed - minimumSpeed) * t * t
    }
}

// Invisible bridge to the existing SwiftUI ScrollView.
struct FaderPlaylistScrollBridge: NSViewRepresentable {

    let controller: FaderPlaylistAutoScrollController

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {
        DispatchQueue.main.async { [weak view] in
            guard let view else { return }
            controller.scrollView = view.enclosingScrollView
        }
    }
}
