import SwiftUI

struct CrossfadeView: View {
    @EnvironmentObject var player: Player

    var body: some View {
        // FADER_UI_V1_STAGE5C: compact control, same binding and range as before.
        // STAGE5F: shared 84 / 140 / 42 layout with Volume row.
        HStack(spacing: 8) {
            Text("Crossfade")
                .font(.caption)
                .fontWeight(.medium)
                .foregroundStyle(.secondary)
                .frame(width: 84, alignment: .leading)
            Slider(value: $player.crossfade, in: 0...10, step: 0.5)
                .frame(width: 140)
                .help("Fade length for automatic mixes between songs. 0 = instant switch.")
            Text(String(format: "%.1f s", player.crossfade))
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(width: 42, alignment: .leading)
            if player.isCrossfading {
                Text("mixing…")
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
            }
        }
        .frame(width: 282, alignment: .center)
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.bottom, 2)
    }
}
