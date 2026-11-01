import SwiftUI
import AppKit

private final class FaderAccentDividerNativeView: NSSplitView {
    var layoutAction: ((NSSplitView) -> Void)?
    var dividerDragInProgress = false
    var finishedDividerDrag: ((NSSplitView) -> Void)?
    override func mouseDown(with event: NSEvent) {
        let before = subviews.first.map { isVertical ? $0.frame.maxX : $0.frame.maxY }
        dividerDragInProgress = true
        defer { dividerDragInProgress = false }
        super.mouseDown(with: event)
        if let before, let pane = subviews.first {
            let after = isVertical ? pane.frame.maxX : pane.frame.maxY
            if abs(after - before) > 0.5 { finishedDividerDrag?(self) }
        }
    }
    override func drawDivider(in rect: NSRect) { }
    override func resizeSubviews(withOldSize oldSize: NSSize) {
        super.resizeSubviews(withOldSize: oldSize)
        layoutAction?(self)
    }
    override func layout() {
        super.layout()
        layoutAction?(self)
    }
}

struct FaderAccentNativeSplit<First: View, Second: View>: NSViewRepresentable {
    let isVertical: Bool
    let storageKey: String
    let minFirst: CGFloat
    let minSecond: CGFloat
    let first: First
    let second: Second
    var initialFraction: CGFloat = 0.23
    var initialFirstMaximum: CGFloat? = 330

    final class Coordinator: NSObject, NSSplitViewDelegate {
        let key: String
        var minFirst: CGFloat
        var minSecond: CGFloat
        var initialFraction: CGFloat
        var initialFirstMaximum: CGFloat?
        var preferredFraction: CGFloat?
        var lastLength: CGFloat = -1
        var applying = false
        var colorObserver: NSObjectProtocol?
        private let memory: FaderLayoutMemory
        private var terminationObserver: NSObjectProtocol?
        private var pendingSave: DispatchWorkItem?
        private var pendingPosition: (position: CGFloat, fraction: CGFloat)?

        init(key: String, minFirst: CGFloat, minSecond: CGFloat,
             initialFraction: CGFloat, initialFirstMaximum: CGFloat?, memory: FaderLayoutMemory = FaderLayoutMemory()) {
            self.key = key
            self.memory = memory
            self.minFirst = minFirst
            self.minSecond = minSecond
            self.initialFraction = initialFraction
            self.initialFirstMaximum = initialFirstMaximum
            super.init()
            terminationObserver = NotificationCenter.default.addObserver(
                forName: NSApplication.willTerminateNotification, object: nil, queue: .main
            ) { [weak self] _ in self?.flush() }
        }

        func layout(_ split: NSSplitView) {
            let length = split.isVertical ? split.bounds.width : split.bounds.height
            guard !applying, split.subviews.count == 2, length > 1,
                  abs(length - lastLength) > 0.5 else { return }
            let available = max(1, length - split.dividerThickness)
            if preferredFraction == nil {
                preferredFraction = memory.fraction(for: key, available: available)
                    ?? (min(available * initialFraction, initialFirstMaximum ?? available) / available)
            }
            let position = FaderLayoutPolicy.position(length: length, divider: split.dividerThickness,
                minimumFirst: minFirst, minimumSecond: minSecond, fraction: preferredFraction ?? initialFraction)
            applying = true
            lastLength = length
            // Explicit frames prevent an unsized hosting view from taking half the
            // initial window or retaining a width greater than its new container.
            if split.isVertical {
                split.subviews[0].frame = NSRect(x: 0, y: 0, width: position, height: split.bounds.height)
                split.subviews[1].frame = NSRect(x: position + split.dividerThickness, y: 0,
                    width: max(0, available - position), height: split.bounds.height)
            } else {
                split.subviews[0].frame = NSRect(x: 0, y: 0, width: split.bounds.width, height: position)
                split.subviews[1].frame = NSRect(x: 0, y: position + split.dividerThickness,
                    width: split.bounds.width, height: max(0, available - position))
            }
            applying = false
        }

        func splitView(_ split: NSSplitView, constrainMinCoordinate proposed: CGFloat,
                       ofSubviewAt dividerIndex: Int) -> CGFloat { max(proposed, minFirst) }
        func splitView(_ split: NSSplitView, constrainMaxCoordinate proposed: CGFloat,
                       ofSubviewAt dividerIndex: Int) -> CGFloat {
            let length = split.isVertical ? split.bounds.width : split.bounds.height
            return min(proposed, max(minFirst, length - split.dividerThickness - minSecond))
        }
        func splitView(_ split: NSSplitView, canCollapseSubview subview: NSView) -> Bool { false }
        func splitViewDidResizeSubviews(_ notification: Notification) {
            guard !applying, let split = notification.object as? NSSplitView,
                  split.subviews.count == 2, split.window?.inLiveResize != true else { return }
            let length = split.isVertical ? split.bounds.width : split.bounds.height
            // Parent/window resizing is automatic layout, not a new divider preference.
            guard lastLength > 0, abs(length - lastLength) <= 0.5 else { return }
            let explicitUserResize = (notification.userInfo?["NSSplitViewUserResizeKey"] as? NSNumber)?.boolValue == true
            let event = NSApp?.currentEvent
            var mouseDrag = false
            if event?.type == .leftMouseDragged, let event, let pane = split.subviews.first {
                let point = split.convert(event.locationInWindow, from: nil)
                let coordinate = split.isVertical ? point.x : point.y
                let dividerPosition = split.isVertical ? pane.frame.maxX : pane.frame.maxY
                mouseDrag = split.bounds.contains(point) && abs(coordinate - dividerPosition) <= max(8, split.dividerThickness + 2)
            }
            guard explicitUserResize || mouseDrag || (split as? FaderAccentDividerNativeView)?.dividerDragInProgress == true else { return }
            recordUserPosition(split)
        }
        func recordUserPosition(_ split: NSSplitView) {
            guard !applying, split.subviews.count == 2, split.window?.inLiveResize != true else { return }
            let length = split.isVertical ? split.bounds.width : split.bounds.height
            let pane = split.subviews[0].frame
            let position = split.isVertical ? pane.maxX : pane.maxY
            guard length > 1, position.isFinite, position > 0 else { return }
            let fraction = position / max(1, length - split.dividerThickness)
            guard fraction > 0, fraction < 1 else { return }
            preferredFraction = fraction
            pendingPosition = (position, fraction)
            pendingSave?.cancel()
            let work = DispatchWorkItem { [weak self] in self?.flush() }
            pendingSave = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
        }
        func flush() {
            pendingSave?.cancel(); pendingSave = nil
            if let pendingPosition { memory.save(split: key, position: pendingPosition.position, fraction: pendingPosition.fraction) }
            pendingPosition = nil
        }
        deinit {
            pendingSave?.cancel()
            if let pendingPosition { memory.save(split: key, position: pendingPosition.position, fraction: pendingPosition.fraction) }
            if let colorObserver { NotificationCenter.default.removeObserver(colorObserver) }
            if let terminationObserver { NotificationCenter.default.removeObserver(terminationObserver) }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(key: storageKey, minFirst: minFirst, minSecond: minSecond,
                    initialFraction: initialFraction, initialFirstMaximum: initialFirstMaximum)
    }
    func makeNSView(context: Context) -> NSSplitView {
        let split = FaderAccentDividerNativeView()
        split.isVertical = isVertical
        split.dividerStyle = .thin
        split.delegate = context.coordinator
        let a = NSHostingView(rootView: first)
        let b = NSHostingView(rootView: second)
        a.sizingOptions = []; b.sizingOptions = []
        split.addSubview(a); split.addSubview(b)
        split.layoutAction = { [weak coordinator = context.coordinator] view in coordinator?.layout(view) }
        split.finishedDividerDrag = { [weak coordinator = context.coordinator] view in
            coordinator?.recordUserPosition(view)
            coordinator?.flush()
        }
        context.coordinator.colorObserver = NotificationCenter.default.addObserver(
            forName: NSColor.systemColorsDidChangeNotification, object: nil, queue: .main
        ) { [weak split] _ in split?.needsDisplay = true }
        return split
    }
    func updateNSView(_ split: NSSplitView, context: Context) {
        context.coordinator.minFirst = minFirst
        context.coordinator.minSecond = minSecond
        guard split.subviews.count == 2,
              let a = split.subviews[0] as? NSHostingView<First>,
              let b = split.subviews[1] as? NSHostingView<Second> else { return }
        a.rootView = first; b.rootView = second
        split.needsLayout = true
        split.needsDisplay = true
    }
}
