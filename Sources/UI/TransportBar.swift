import SwiftUI

struct TransportBar: View {
    var showsShuffle = true
    @AppStorage("Fader.appearance.useSystemAccent", store: FaderStorage.defaults) private var useSystemAccent = true
    @AppStorage("Fader.appearance.accentHex", store: FaderStorage.defaults) private var accentHex = "#4B8DF8"
    private var faderAccent: Color {
        useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex)
    }
    @EnvironmentObject var player: Player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isDragging = false
    @State private var temporaryPosition: Double = 0
    @State private var seekVisualHoldUntil: TimeInterval = 0
    @State private var seekVisualTrackPath: String?

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
                if showsShuffle {
                Image(systemName: "shuffle")
                    .foregroundStyle(player.shuffle ? faderAccent : Color.secondary)
                    .help("Shuffle \(player.shuffle ? "on" : "off")")
                    .onTapGesture { player.toggleShuffle() }
                }

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
                            .frame(width: max(0, geo.size.width * (isDragging ? temporaryPosition : displayedProgress)))
                            .frame(height: 6)

                        // Position marker uses the existing 10 Hz progress updates.
                        // Presentation only: no extra timer or audio-engine work.
                        let markerProgress = isDragging ? temporaryPosition : displayedProgress
                        let markerX = max(0, min(max(0, geo.size.width - 18), geo.size.width * markerProgress - 9))
                        Image(systemName: "play.fill")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(faderAccent)
                            .padding(.horizontal, 1)
                            .background(Color(nsColor: .windowBackgroundColor), in: RoundedRectangle(cornerRadius: 3))
                            .frame(width: 18, height: 20)
                            .offset(x: markerX)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)

                        // Interactive area - tap to seek and drag for scrubbing
                        Rectangle()
                            .fill(.clear)
                            .contentShape(Rectangle())
                            .gesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { value in
                                        guard player.currentTrack != nil, geo.size.width > 0 else { return }
                                        // A click must not first teleport the marker as a zero-distance drag.
                                        if isDragging || abs(value.translation.width) > 3 || abs(value.translation.height) > 3 {
                                            temporaryPosition = max(0, min(1, value.location.x / geo.size.width))
                                            isDragging = true
                                        }
                                    }
                                    .onEnded { value in
                                        guard let track = player.currentTrack, geo.size.width > 0 else {
                                            isDragging = false
                                            return
                                        }
                                        let target = max(0, min(1, value.location.x / geo.size.width))
                                        let wasDragging = isDragging
                                        seekVisualTrackPath = track.normalizedPath
                                        seekVisualHoldUntil = ProcessInfo.processInfo.systemUptime + 0.25
                                        // Publish the destination BEFORE ending the drag. Never reveal the
                                        // stale progress snapshot while the player's UI values catch up.
                                        withAnimation(reduceMotion || wasDragging ? nil : .easeOut(duration: 0.22)) {
                                            displayedProgress = target
                                            displayedElapsed = target * track.duration
                                            temporaryPosition = target
                                            isDragging = false
                                        }
                                        player.seek(to: target * track.duration)
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
        guard !isDragging else { return }
        if seekVisualTrackPath == player.currentTrack?.normalizedPath,
           ProcessInfo.processInfo.systemUptime < seekVisualHoldUntil { return }
        let newElapsed = player.elapsed
        let newProgress = player.progress
        if displayedElapsed != newElapsed {
            displayedElapsed = newElapsed
        }

        if displayedProgress != newProgress {
            withAnimation(reduceMotion ? nil : .linear(duration: 0.10)) {
                displayedProgress = newProgress
            }
        }
    }
}