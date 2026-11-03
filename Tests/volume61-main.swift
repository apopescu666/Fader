import SwiftUI
import AppKit
import QuartzCore

@main struct VolumeTests {
    @MainActor static func main() throws {
        NSApplication.shared.setActivationPolicy(.prohibited)
        var received = -1.0
        let coordinator = FaderVolumeSlider.Coordinator(value: Binding(get: { received }, set: { received = $0 }))
        let slider = FaderAnimatedVolumeSlider(frame: NSRect(x: 0, y: 0, width: 240, height: 24))
        let cell = FaderVolumeSliderCell()
        slider.cell = cell; slider.minValue = 0; slider.maxValue = 1
        slider.isContinuous = true; slider.trackFillColor = .systemBlue
        slider.target = coordinator; slider.action = #selector(FaderVolumeSlider.Coordinator.changed(_:))
        let image = NSImage(size: NSSize(width: 360, height: 140))
        image.lockFocus()
        NSColor.windowBackgroundColor.setFill(); NSRect(x: 0, y: 0, width: 360, height: 140).fill()
        for (index, value) in [0.0, 0.35, 1.0].enumerated() {
            slider.doubleValue = value
            precondition(slider.sendAction(slider.action, to: slider.target))
            precondition(abs(received - value) < 0.000001)
            // Let the native animation complete without opening a window.
            let deadline = Date().addingTimeInterval(0.22)
            while Date() < deadline { _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.01)) }
            precondition(abs(slider.displayedFraction - value) < 0.000001)
            let bar = cell.barRect(flipped: false)
            let knob = cell.knobRect(flipped: false)
            precondition(abs(knob.midY - bar.midY) < 0.000001)
            precondition(abs(knob.midX - (bar.minX + bar.width * value)) < 0.000001)
            precondition(slider.bounds.contains(knob))
            NSGraphicsContext.saveGraphicsState()
            let transform = NSAffineTransform(); transform.translateX(by: 90, yBy: CGFloat(18 + index * 38)); transform.concat()
            cell.draw(withFrame: slider.bounds, in: slider)
            NSGraphicsContext.restoreGraphicsState()
            ("\(Int(value*100))%" as NSString).draw(at: NSPoint(x: 20, y: 23 + index * 38), withAttributes: [.foregroundColor: NSColor.labelColor])
        }
        image.unlockFocus()
        slider.present(0.5, animated: true)
        let midpoint = Date().addingTimeInterval(0.07)
        while Date() < midpoint { _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.005)) }
        precondition(slider.displayedFraction < 1 && slider.displayedFraction > 0.5)
        // A drag supersedes a pending transition immediately and stays there.
        slider.present(0.8, animated: false)
        precondition(abs(slider.displayedFraction - 0.8) < 0.000001)
        let settled = Date().addingTimeInterval(0.2)
        while Date() < settled { _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.01)) }
        precondition(abs(slider.displayedFraction - 0.8) < 0.000001)
        slider.present(0.5, animated: false)
        precondition(abs(slider.displayedFraction - 0.5) < 0.000001)
        if let data = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: data), let png = bitmap.representation(using: .png, properties: [:]) {
            try png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
        }
        print("PASS: immediate audio binding at 0/35/100%; native animation reaches exact target; thumb centered vertically and on blue endpoint at each value; no-animation mode immediate")
    }
}
