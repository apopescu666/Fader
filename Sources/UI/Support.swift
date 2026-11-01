import SwiftUI

/// Format seconds as m:ss.
func clock(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "0:00" }
    let s = Int(seconds.rounded(.down))
    return String(format: "%d:%02d", s / 60, s % 60)
}

/// Read-only determinate progress bar.
struct ProgressTrack: View {
    let value: Double            // 0…1
    var tint: Color = .accentColor

    /// For interactive seeking
    @State private var isDragging = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.secondary.opacity(0.2))
                Capsule()
                    .fill(tint)
                    .frame(width: max(0, geo.size.width * value))
            }
        }
        .frame(height: 6)
        .contentShape(Rectangle())
    }
}
