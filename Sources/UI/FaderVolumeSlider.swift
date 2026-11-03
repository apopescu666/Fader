import SwiftUI
import AppKit

/// A native continuous slider with the existing volume binding and a narrow,
/// perpendicular thumb. AppKit retains dragging, keyboard and accessibility behavior.
struct FaderVolumeSlider: NSViewRepresentable {
    @Binding var value: Double
    var accent: NSColor
    var onAdjustment: (Bool) -> Void = { _ in }
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeCoordinator() -> Coordinator { Coordinator(value: $value) }

    func makeNSView(context: Context) -> NSSlider {
        let slider = FaderAnimatedVolumeSlider(frame: NSRect(x: 0, y: 0, width: 140, height: 24))
        slider.cell = FaderVolumeSliderCell()
        slider.sliderType = .linear
        slider.isVertical = false
        slider.minValue = 0
        slider.maxValue = 1
        slider.isContinuous = true
        slider.target = context.coordinator
        slider.action = #selector(Coordinator.changed(_:))
        slider.setAccessibilityLabel("Volume")
        slider.toolTip = "Volume"
        updateNSView(slider, context: context)
        return slider
    }

    func updateNSView(_ slider: NSSlider, context: Context) {
        context.coordinator.value = $value
        slider.isEnabled = isEnabled
        slider.trackFillColor = accent
        (slider as? FaderAnimatedVolumeSlider)?.onAdjustment = onAdjustment
        let next = min(1, max(0, value))
        if abs(slider.doubleValue - next) > 0.000001 { slider.doubleValue = next }
        if let animated = slider as? FaderAnimatedVolumeSlider {
            animated.present(next, animated: !reduceMotion)
        }
        slider.needsDisplay = true
    }

    final class Coordinator: NSObject {
        var value: Binding<Double>
        init(value: Binding<Double>) { self.value = value }
        @objc func changed(_ slider: NSSlider) {
            let next = min(1, max(0, slider.doubleValue))
            if let animated = slider as? FaderAnimatedVolumeSlider {
                let dragging = NSApp.currentEvent?.type == .leftMouseDragged
                animated.present(next, animated: !dragging && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion)
            }
            value.wrappedValue = next
        }
    }
}

/// Animates presentation only. doubleValue and the audio binding update immediately.
final class FaderAnimatedVolumeSlider: NSSlider {
    private var visualTarget: Double?
    @objc dynamic var displayedFraction: Double = 0 {
        didSet {
            (cell as? FaderVolumeSliderCell)?.displayedFraction = displayedFraction
            needsDisplay = true
        }
    }
    private var animationGeneration = 0
    private var haloGeneration = 0
    private var trackingVolume = false
    var onAdjustment: (Bool) -> Void = { _ in }

    func beginAdjustmentFeedback() {
        haloGeneration += 1
        (cell as? FaderVolumeSliderCell)?.haloOpacity = 1
        needsDisplay = true
        let generation = haloGeneration
        DispatchQueue.main.async { [weak self] in
            guard let self, self.haloGeneration >= generation else { return }
            self.onAdjustment(true)
        }
    }

    func endAdjustmentFeedback() {
        haloGeneration += 1
        let generation = haloGeneration
        let reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        // A bounded fade after interaction; nothing runs while the control is idle.
        for frame in 1...8 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12 + 0.03 * Double(frame)) { [weak self] in
                guard let self, self.haloGeneration == generation else { return }
                (self.cell as? FaderVolumeSliderCell)?.haloOpacity = reduceMotion ? 0 : 1 - Double(frame) / 8
                self.needsDisplay = true
                if frame == 8 { self.onAdjustment(false) }
            }
        }
    }

    override func mouseDown(with event: NSEvent) {
        trackingVolume = true
        beginAdjustmentFeedback()
        defer {
            trackingVolume = false
            endAdjustmentFeedback()
        }
        super.mouseDown(with: event)
    }

    func present(_ value: Double, animated: Bool) {
        let fraction = max(0, min(1, value))
        guard visualTarget != fraction else { return }
        let first = visualTarget == nil
        visualTarget = fraction
        if first { displayedFraction = fraction; return }
        if !trackingVolume {
            beginAdjustmentFeedback()
            endAdjustmentFeedback()
        }
        animationGeneration += 1
        let generation = animationGeneration
        guard animated else { displayedFraction = fraction; return }
        let start = displayedFraction
        // Five short, cancellable presentation updates; no repeating/idle timer.
        for frame in 1...5 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.14 * Double(frame) / 5) { [weak self] in
                guard let self, self.animationGeneration == generation else { return }
                let progress = Double(frame) / 5
                let eased = 1 - pow(1 - progress, 3)
                self.displayedFraction = frame == 5 ? fraction : start + (fraction - start) * eased
            }
        }
    }
}

final class FaderVolumeSliderCell: NSSliderCell {
    var displayedFraction: Double?
    var haloOpacity: Double = 0
    private var fraction: Double {
        displayedFraction ?? max(0, min(1, (doubleValue - minValue) / max(0.000001, maxValue - minValue)))
    }
    override func barRect(flipped: Bool) -> NSRect {
        guard let bounds = controlView?.bounds else { return super.barRect(flipped: flipped) }
        return NSRect(x: bounds.minX + 4, y: bounds.midY - 2, width: max(0, bounds.width - 8), height: 4)
    }
    override func knobRect(flipped: Bool) -> NSRect {
        let bar = barRect(flipped: flipped)
        return NSRect(x: bar.minX + bar.width * fraction - 4, y: bar.midY - 10, width: 8, height: 20)
    }
    override func drawBar(inside rect: NSRect, flipped: Bool) {
        let bar = barRect(flipped: flipped)
        NSColor.separatorColor.withAlphaComponent(0.35).setFill()
        NSBezierPath(roundedRect: bar, xRadius: 2, yRadius: 2).fill()
        if fraction > 0 {
            let filled = NSRect(x: bar.minX, y: bar.minY, width: bar.width * fraction, height: bar.height)
            let color = (controlView as? NSSlider)?.trackFillColor ?? .controlAccentColor
            color.withAlphaComponent(isEnabled ? 1 : 0.35).setFill()
            NSBezierPath(roundedRect: filled, xRadius: 2, yRadius: 2).fill()
        }
        for tick in 0...20 {
            let position = Double(tick) / 20
            let height: CGFloat = tick % 5 == 0 ? 12 : 8
            let mark = NSRect(x: bar.minX + bar.width * position - 0.7,
                              y: bar.midY - height / 2, width: 1.4, height: height)
            let color = position <= fraction
                ? ((controlView as? NSSlider)?.trackFillColor ?? .controlAccentColor)
                : NSColor.separatorColor.withAlphaComponent(0.45)
            color.withAlphaComponent(isEnabled ? color.alphaComponent : 0.25).setFill()
            NSBezierPath(roundedRect: mark, xRadius: 0.7, yRadius: 0.7).fill()
        }
    }

    override func drawKnob(_ knobRect: NSRect) {
        NSGraphicsContext.saveGraphicsState()
        defer { NSGraphicsContext.restoreGraphicsState() }
        let thumb = NSBezierPath(roundedRect: knobRect.insetBy(dx: 0.5, dy: 0.5), xRadius: 4, yRadius: 4)
        let shadow = NSShadow()
        shadow.shadowColor = NSColor.black.withAlphaComponent(0.15)
        shadow.shadowBlurRadius = 2
        shadow.shadowOffset = NSSize(width: 0, height: -1)
        shadow.set()
        NSColor.controlBackgroundColor.setFill()
        thumb.fill()
        shadow.shadowColor = nil
        shadow.set()
        NSColor.separatorColor.setStroke()
        thumb.lineWidth = 0.7
        thumb.stroke()
    }
}
