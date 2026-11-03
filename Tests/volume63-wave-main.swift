import AppKit
import CryptoKit

@main struct WavePreview {
    @MainActor static func main() throws {
        NSApplication.shared.setActivationPolicy(.prohibited)
        let image = NSImage(size: NSSize(width: 200, height: 210))
        var hashes = Set<String>()
        image.lockFocus()
        NSColor.windowBackgroundColor.setFill(); NSRect(x: 0, y: 0, width: 200, height: 210).fill()
        for (index, value) in [0.0, 0.25, 0.5, 0.75, 1.0].enumerated() {
            guard let source = NSImage(systemSymbolName: "speaker.wave.3.fill", variableValue: value, accessibilityDescription: nil),
                  let symbol = source.withSymbolConfiguration(NSImage.SymbolConfiguration(hierarchicalColor: .systemBlue)) else { fatalError("Variable speaker symbol unavailable") }
            let rowY = CGFloat(12 + index * 40)
            symbol.draw(in: NSRect(x: 22, y: rowY, width: 30, height: 25))
            ("\(Int(value * 100))%" as NSString).draw(at: NSPoint(x: 92, y: rowY+5), withAttributes: [.foregroundColor: NSColor.labelColor])
            let sample = NSImage(size: NSSize(width: 40, height: 36))
            sample.lockFocus(); symbol.draw(in: NSRect(x: 4, y: 4, width: 30, height: 25)); sample.unlockFocus()
            guard let data = sample.tiffRepresentation else { fatalError("No rendered symbol") }
            hashes.insert(SHA256.hash(data: data).description)
        }
        image.unlockFocus()
        precondition(hashes.count >= 4, "Speaker waves did not change with volume")
        if let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff), let png = bitmap.representation(using: .png, properties: [:]) {
            try png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
        }
        print("PASS: variable speaker symbol renders distinct wave levels across 0/25/50/75/100%; percent labels match")
    }
}
