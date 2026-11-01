import SwiftUI

struct TransportBar: View {
    @AppStorage("Fader.appearance.useSystemAccent") private var useSystemAccent = true
    @AppStorage("Fader.appearance.accentHex") private var accentHex = "#4B8DF8"
    private var faderAccent: Color {
        useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex)
    }
    @EnvironmentObject var player: Player
    @State private var isDragging = false
    @State private var temporaryPosition: Double = 0

    // FADER_TRANSPORT_REFRESH_V5
    @State private var displayedElapsed: Double = 0
    @State private var displayedProgress: Double = 0

    var body: some View {
        // FADER_UI_V1_STAGE5_PLAYER — presentation only
        // FADER_DECOUPLED_PROGRESS_V4
        VStack(spacing: 8) {
            // FADER_UI_V1_STAGE5B_LAYOUT: center transport controls; keep callbacks.
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                HStack(spacing: 20) {
                Image(systemName: "shuffle")
                    .foregroundStyle(player.shuffle ? faderAccent : Color.secondary)
                    .help("Shuffle \(player.shuffle ? "on" : "off")")
                    .onTapGesture { player.toggleShuffle() }

                Button { player.previous() } label: {
                    Image(systemName: "backward.end.fill")
                }
                .buttonStyle(.plain).help("Previous")

                Button { player.togglePlay() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(faderAccent)
                        .frame(minWidth: 38, minHeight: 30)
                }
                .buttonStyle(.plain).help("Play / Pause  (⌘K)")

                Button { player.next(manual: true) } label: {
                    Image(systemName: "forward.end.fill")
                }
                .buttonStyle(.plain).help("Next")

                Image(systemName: player.repeatMode.symbol)
                    .foregroundStyle(player.repeatMode == .off ? Color.secondary : faderAccent)
                    .help(player.repeatMode.label)
                    .onTapGesture { player.cycleRepeat() }

                }
                Spacer(minLength: 0)

            }

            HStack(spacing: 8) {
                Text(clock(displayedElapsed))
                    .font(.caption).monospacedDigit()
                    .foregroundStyle(.secondary)
                    .frame(width: 46, alignment: .leading)

                // Simple progress bar - tap to seek functionality
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.secondary.opacity(0.16))
                            .frame(height: 6)

                        Capsule()
                            .fill(player.isCrossfading ? Color.green : faderAccent)
                            .frame(width: max(0, geo.size.width * displayedProgress))
                            .frame(height: 6)

                        // Visual indicator for drag position
                        if isDragging {
                            Capsule()
                                .fill(Color.white.opacity(0.7))
                                .frame(width: 4, height: 12)
                                .offset(x: max(0, min(geo.size.width, geo.size.width * temporaryPosition)))
                        }

                        // Interactive area - tap to seek and drag for scrubbing
                        Rectangle()
                            .fill(.clear)
                            .contentShape(Rectangle())
                            .onTapGesture { location in
                                if let track = player.currentTrack {
                                    let progress = max(0, min(1, (location.x / geo.size.width)))
                                    let targetTime = progress * track.duration
                                    player.seek(to: targetTime)
                                }
                            }
                            .simultaneousGesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { value in
                                        if player.currentTrack != nil {
                                            let progress = max(0, min(1, (value.location.x / geo.size.width)))
                                            temporaryPosition = progress
                                            isDragging = true
                                        }
                                    }
                                    .onEnded { value in
                                        if let track = player.currentTrack {
                                            let progress = max(0, min(1, (value.location.x / geo.size.width)))
                                            let targetTime = progress * track.duration
                                            player.seek(to: targetTime)
                                            isDragging = false
                                        }
                                    }
                            )
                    }
                }
                .frame(height: 20)

                Text(clock(player.currentTrack?.duration ?? 0))
                    .font(.caption).monospacedDigit()
                    .foregroundStyle(.secondary)
                    .frame(width: 46, alignment: .trailing)
            }

        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .background(Color.clear)
        .onAppear {
            refreshDisplayedProgress()
        }
        .onReceive(
            Timer.publish(
                every: 0.10,
                on: .main,
                in: .common
            ).autoconnect()
        ) { _ in
            refreshDisplayedProgress()
        }
    }

    private func refreshDisplayedProgress() {
        let newElapsed = player.elapsed
        let newProgress = player.progress

        if displayedElapsed != newElapsed {
            displayedElapsed = newElapsed
        }

        if displayedProgress != newProgress {
            displayedProgress = newProgress
        }
    }
}