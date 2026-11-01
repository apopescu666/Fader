import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var player: Player
    // FADER_UI_V1_APPEARANCE_STAGE1 — presentation preferences only.
    @AppStorage("Fader.appearance.mode") private var appearanceMode = "system"
    @AppStorage("Fader.appearance.useSystemAccent") private var useSystemAccent = true
    @AppStorage("Fader.appearance.accentHex") private var accentHex = "#4B8DF8"
    private var faderAccent: Color {
        useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex)
    }

    private var selectedScheme: ColorScheme? {
        switch appearanceMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil // Follow macOS, including its automatic switching.
        }
    }

    @AppStorage("Fader.premium.showQueuePanel") private var showQueuePanel = true
    @State private var isDropTargeted = false
    @AppStorage("Fader.showSidebar")
    private var showSidebar = true

    var body: some View {
        VStack(spacing: 0) {
            // STAGE10D30: no decorative horizontal separators between rounded sections.
            MusicFolderLibraryView(
                showSidebar: $showSidebar
            )
            .disabled(player.isRestoringSession)

            bottomPlayerBar
                .disabled(player.isRestoringSession && !player.isFinderPlayback)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            Color(nsColor: .controlBackgroundColor)
                .contentShape(Rectangle())
        )
        .overlay {
            if player.isRestoringSession && !player.isFinderPlayback {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("Restoring previous session…").font(.headline)
                    Text("Loading your saved songs and playback position")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(24)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .navigationTitle("Fader")
        .preferredColorScheme(selectedScheme)
        .tint(useSystemAccent ? nil : FaderAccentColor.fromHex(accentHex))
        .dropDestination(for: URL.self) { urls, _ in
            print("FADER DROP: received \(urls.count) URLs")
            importDroppedItems(urls)
            return true
        } isTargeted: { targeted in
            isDropTargeted = targeted
        }
        .overlay {
            if isDropTargeted {
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(
                        Color.accentColor,
                        lineWidth: 3
                    )
                    .allowsHitTesting(false)
            }
        }
    }


    // STAGE7B_FIX: the transport UI is identical; extract it to simplify type-checking.
    private var bottomPlayerBar: some View {
        // FADER_UI_V1_STAGE5D: album and Go to Playing left;
        // transport and seek center; equal-width audio controls right.
        ViewThatFits(in: .horizontal) {
            widePlayerBar
                .frame(minWidth: 1040)
            VStack(spacing: 10) {
                HStack(spacing: 14) {
                    NowPlayingView().frame(maxWidth: .infinity, alignment: .leading)
                    Divider().frame(height: 100)
                    audioControlsGrid
                }
                TransportBar()
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color(nsColor: .windowBackgroundColor),
                    in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
    }

    private var widePlayerBar: some View {
        HStack(alignment: .center, spacing: 14) {
            NowPlayingView()
                .frame(width: 360, alignment: .leading)
            Divider()
                .frame(height: 110)
            TransportBar()
                .frame(maxWidth: .infinity)
            Divider()
                .frame(height: 110)
            audioControlsGrid
        }
    }

    private var audioControlsGrid: some View {
            // STAGE5G: one shared grid fixes both columns at the same x position.
            Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 12) {
                GridRow {
                    Text("Crossfade")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundStyle(.secondary)
                        .frame(width: 72, alignment: .center)
                    Slider(value: $player.crossfade, in: 0...10, step: 0.5)
                        .tint(useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex))
                        .frame(width: 140)
                        .help("Fade length for automatic mixes between songs. 0 = instant switch.")
                    Text(String(format: "%.1f s", player.crossfade))
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                        .frame(width: 42, alignment: .leading)
                }
                GridRow {
                    Button {
                        player.toggleMute()
                    } label: {
                        Image(systemName: player.isMuted ? "speaker.slash.fill" : "speaker.fill")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(player.isMuted ? Color.secondary : faderAccent)
                            .frame(width: 72, height: 30, alignment: .center)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .help(player.isMuted ? "Unmute" : "Mute")
                    Slider(value: .init(
                        get: { Double(player.volume) },
                        set: { player.setVolume(Float($0)) }
                    ), in: 0...1)
                    .tint(useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex))
                    .frame(width: 140)
                    Color.clear.frame(width: 42, height: 1)
                }
            }
            .frame(width: 290, alignment: .center)
    }

    private var librarySidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("LIBRARY")
                .font(.caption2)
                .fontWeight(.semibold)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 14)
                .padding(.top, 17)
                .padding(.bottom, 10)

            Label("Current Queue", systemImage: "music.note.list")
                .font(.callout)
                .fontWeight(.medium)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.clear)

            Label("Music Folders", systemImage: "folder")
                .font(.callout)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 14)
                .padding(.top, 20)

            Text("No folders saved yet")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .padding(.leading, 37)
                .padding(.top, 9)

            Divider()
                .padding(.vertical, 18)
                .padding(.horizontal, 12)

            Label("Playlists", systemImage: "list.bullet")
                .font(.callout)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 14)

            Text("No playlists created yet")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .padding(.leading, 37)
                .padding(.top, 9)

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    // MARK: - Finder Drag & Drop

    // FADER_FINDER_BACKGROUND_IMPORT_V1
    private func importDroppedItems(_ urls: [URL]) {
        guard !player.isRestoringSession else { return }
        let droppedURLs = urls

        // Scan audio and network folders away from the UI thread.
        DispatchQueue.global(qos: .userInitiated).async {
            var tracks: [Track] = []

            for url in droppedURLs {
                guard url.isFileURL else { continue }

                var isDirectory: ObjCBool = false
                guard FileManager.default.fileExists(
                    atPath: url.path,
                    isDirectory: &isDirectory
                ) else {
                    print("FADER DROP: file not accessible: \(url.path)")
                    continue
                }

                if isDirectory.boolValue {
                    tracks.append(contentsOf: Library.scan(folder: url))
                } else if Library.isPlaylist(url: url) {
                    tracks.append(contentsOf: Library.loadPlaylist(url: url))
                } else if Library.audioExtensions.contains(
                    url.pathExtension.lowercased()
                ) {
                    tracks.append(Library.makeTrack(url: url))
                }
            }

            // Player and SwiftUI state are changed on the main thread.
            DispatchQueue.main.async {
                print("FADER DROP: imported \(tracks.count) tracks")

                if tracks.isEmpty {
                    player.status = "No supported audio files found in drop."
                } else {
                    // FADER_FINDER_QUEUE_DEDUP_P275_V1
                    // Finder -> Music Folder area -> Current Queue: retain existing
                    // queue entries, and append only tracks whose file path is new.
                    // Also avoid duplicates within one multi-file Finder drop.
                    var existingPaths = Set(player.queue.map {
                        $0.normalizedPath
                    })
                    let newTracks = tracks.filter { track in
                        existingPaths.insert(track.normalizedPath).inserted
                    }
                    if newTracks.isEmpty {
                        player.status = "Song already in Current Queue."
                        print("FADER DROP DEDUP P275: skipped \(tracks.count) duplicate(s)")
                    } else {
                        player.append(newTracks)
                        if newTracks.count < tracks.count {
                            print("FADER DROP DEDUP P275: added \(newTracks.count), skipped \(tracks.count - newTracks.count) duplicate(s)")
                        }
                    }
                }
            }
        }
    }
}
