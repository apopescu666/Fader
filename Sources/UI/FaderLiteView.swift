import SwiftUI
import AppKit

/// One transport band. Uses the same Player, artwork cache, progress control,
/// volume slider and appearance keys as the full application.
struct FaderLiteView: View {
    @EnvironmentObject var player: Player
    @ObservedObject var presentation: FaderPresentationController
    @AppStorage("Fader.appearance.mode", store: FaderStorage.defaults) private var mode = "system"
    @AppStorage("Fader.appearance.useSystemAccent", store: FaderStorage.defaults) private var useSystemAccent = true
    @AppStorage("Fader.appearance.accentHex", store: FaderStorage.defaults) private var accentHex = "#4B8DF8"
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var targeted = false
    @State private var adjustingVolume = false
    private var accent: Color { useSystemAccent ? .accentColor : FaderAccentColor.fromHex(accentHex) }
    private var scheme: ColorScheme? { mode == "dark" ? .dark : (mode == "light" ? .light : nil) }

    var body: some View {
        HStack(spacing: 16) {
            nowPlaying.frame(width: 265, alignment: .leading)
            Divider().frame(height: 105)
            TransportBar(showsShuffle: false).frame(minWidth: 235, maxWidth: .infinity)
            Divider().frame(height: 105)
            VStack(spacing: 22) {
                HStack {
                    Button(action: presentation.chooseTracks) { Label("Add tracks", systemImage: "plus") }
                        .help("Add files to Current Queue in Fader")
                    Spacer(minLength: 8)
                    Button(action: { presentation.showFull() }) { Label("Open Fader", systemImage: "arrow.up.right.square") }
                }
                .buttonStyle(.plain)
                .font(.callout)
                .foregroundStyle(accent)
                HStack(spacing: 10) {
                    Button { player.toggleMute() } label: {
                        Image(systemName: player.isMuted ? "speaker.slash.fill" : "speaker.wave.3.fill",
                              variableValue: min(1, max(0, Double(player.volume))))
                            .symbolRenderingMode(.hierarchical)
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(player.isMuted ? Color.secondary : accent)
                            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: player.volume)
                            .background {
                                Circle().fill(accent.opacity(adjustingVolume ? 0.14 : 0))
                                    .frame(width: 36, height: 36)
                                    .animation(reduceMotion ? nil : .easeOut(duration: 0.20), value: adjustingVolume)
                                    .allowsHitTesting(false)
                            }
                            .frame(width: 28, height: 30)
                    }
                    .buttonStyle(.plain).help(player.isMuted ? "Unmute" : "Mute")
                    FaderVolumeSlider(value: .init(get: { Double(player.volume) }, set: { player.setVolume(Float($0)) }),
                                      accent: NSColor(accent), onAdjustment: { adjustingVolume = $0 })
                        .frame(minWidth: 100, maxWidth: .infinity).frame(height: 24)
                    Text("\(Int((min(1, max(0, Double(player.volume))) * 100).rounded()))%")
                        .font(.caption).monospacedDigit().foregroundStyle(.secondary)
                        .frame(width: 35, alignment: .trailing)
                }
            }
            .frame(width: 260)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor), in: RoundedRectangle(cornerRadius: 11))
        .padding(7)
        .background(Color(nsColor: .controlBackgroundColor))
        .contentShape(Rectangle())
        .onDrop(of: ["public.file-url"], isTargeted: $targeted) { providers in
            FaderLiteDropReader.read(providers) { presentation.addFromLite($0) }
            return !providers.isEmpty
        }
        .overlay {
            if targeted {
                RoundedRectangle(cornerRadius: 11).strokeBorder(accent, lineWidth: 2)
                    .padding(7).allowsHitTesting(false)
            }
        }
        .preferredColorScheme(scheme)
        .tint(useSystemAccent ? nil : accent)
    }

    private var nowPlaying: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 11).fill(accent.opacity(0.12))
                if let track = player.currentTrack, let artwork = FaderArtworkImageCache.image(for: track) {
                    Image(nsImage: artwork).resizable().aspectRatio(contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 11))
                } else {
                    Image(systemName: "music.note").font(.system(size: 32)).foregroundStyle(accent)
                }
            }
            .frame(width: 80, height: 80)
            VStack(alignment: .leading, spacing: 5) {
                Text(player.currentTrack?.title ?? "Nothing playing")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold)).lineLimit(2)
                Text(player.currentTrack?.artist ?? "Open a song from Finder")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Text("Opened from Finder").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if player.status.lowercased().contains("couldn't") || player.status.lowercased().contains("failed") {
                    Text(player.status).font(.caption2).foregroundStyle(.red).lineLimit(2)
                }
            }
            .help(player.currentTrack.map { "\($0.title) — \($0.artist)" } ?? player.status)
        }
    }
}

/// Finder file URLs only. Preserve Finder's provider order despite asynchronous
/// loading, and deliver all accepted URLs together for one queue confirmation.
enum FaderLiteDropReader {
    static func read(_ providers: [NSItemProvider], completion: @escaping ([URL]) -> Void) {
        let group = DispatchGroup()
        let lock = NSLock()
        var received: [Int: URL] = [:]
        for (index, provider) in providers.enumerated() where provider.hasItemConformingToTypeIdentifier("public.file-url") {
            group.enter()
            provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, _ in
                let url: URL?
                if let value = item as? URL { url = value }
                else if let data = item as? Data { url = URL(dataRepresentation: data, relativeTo: nil) }
                else if let text = item as? String { url = URL(string: text) }
                else { url = nil }
                if let url, url.isFileURL {
                    lock.lock(); received[index] = url; lock.unlock()
                }
                group.leave()
            }
        }
        group.notify(queue: .main) { completion(received.keys.sorted().compactMap { received[$0] }) }
    }
}
