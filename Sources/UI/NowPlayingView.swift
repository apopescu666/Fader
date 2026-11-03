import SwiftUI

struct NowPlayingView: View {
    @EnvironmentObject var player: Player

    // FADER_PLAYING_SOURCE_P07_V1
    @AppStorage("Fader.playingSourceName", store: FaderStorage.defaults)
    private var playingSourceName = ""


    var body: some View {
        // FADER_UI_V1_STAGE5_PLAYER — presentation only
        VStack(spacing: 4) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Color.accentColor.opacity(0.12))
                        .frame(width: 100, height: 100)

                    if let track = player.currentTrack,
                       let nsImage = FaderArtworkImageCache.image(for: track) {
                        Image(nsImage: nsImage)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 94, height: 94)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    } else {
                        Image(systemName: player.isPlaying ? "music.note.list" : "music.note")
                            .font(.system(size: 38, weight: .regular))
                            .foregroundStyle(Color.accentColor)
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(displayTitle)
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .lineLimit(1)
                    Text(displayArtist)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .help(player.status)
                    // Stage 7A: avoid showing routine, often-truncated status text.
                    // Keep critical playback errors and active crossfade feedback visible.
                    if isPlaybackError || player.isCrossfading {
                        statusLine
                    }
                    // FADER_GO_TO_PLAYING_P07_V1
                    if player.currentTrack != nil && !playingSourceName.isEmpty && !player.isFinderPlayback {
                        Button {
                            NotificationCenter.default.post(
                                name: Notification.Name("Fader.goToPlaying"),
                                object: nil
                            )
                        } label: {
                            Label("Go to Playing", systemImage: "scope")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.accentColor)
                                .padding(.horizontal, 9)
                                .padding(.vertical, 5)
                                .background(Color.accentColor.opacity(0.14), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .help("Show the playlist or folder containing the playing track")
                    }
                    if player.currentTrack != nil && (player.isFinderPlayback || !playingSourceName.isEmpty) {
                        Text(player.isFinderPlayback ? "Opened from Finder" : "Playing from: \(playingSourceName)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, 2)
            .padding(.vertical, 3)
        }
        .background(Color.clear)
        // FADER_P08_EXPLICIT_DROP_V1: Finder -> immediate playback
        .contentShape(Rectangle())
        .dropDestination(for: URL.self) { urls, _ in
            importAndPlayDroppedURLs(urls)
            return !urls.isEmpty
        }
    }

    // Keep existing queue content: append new tracks, start at first new item.
    // Audio-file and NAS-folder scanning happens off the UI thread.
    private func importAndPlayDroppedURLs(_ urls: [URL]) {
        let droppedURLs = urls
        DispatchQueue.global(qos: .userInitiated).async {
            var tracks: [Track] = []
            for url in droppedURLs where url.isFileURL {
                var isDirectory: ObjCBool = false
                guard FileManager.default.fileExists(
                    atPath: url.path, isDirectory: &isDirectory
                ) else { continue }
                if isDirectory.boolValue {
                    tracks.append(contentsOf: Library.scan(folder: url))
                } else if Library.isPlaylist(url: url) {
                    tracks.append(contentsOf: Library.loadPlaylist(url: url))
                } else if Library.audioExtensions.contains(url.pathExtension.lowercased()) {
                    tracks.append(Library.makeTrack(url: url))
                }
            }
            DispatchQueue.main.async {
                guard !tracks.isEmpty else {
                    player.status = "No supported audio files in Now Playing drop."
                    return
                }
                // FADER_NOW_PLAYING_DEDUP_P08_V1
                // Keep existing queue entries, append only unseen file paths.
                // Still play the first dropped song, even if it is already queued.
                let firstPath = tracks[0].normalizedPath
                var knownPaths = Set(player.queue.map {
                    $0.normalizedPath
                })
                let uniqueTracks = tracks.filter { track in
                    knownPaths.insert(track.normalizedPath).inserted
                }
                if !uniqueTracks.isEmpty {
                    player.append(uniqueTracks)
                }
                guard let playingIndex = player.queue.firstIndex(where: {
                    $0.normalizedPath == firstPath
                }) else {
                    player.status = "Dropped song could not be added to Current Queue."
                    return
                }
                player.playQueueIndex(playingIndex)
            }
        }
    }

    private var displayTitle: String {
        if let inc = player.incoming { return "↔ \(inc.title)" }
        return player.currentTrack?.title ?? "Nothing playing"
    }

    private var displayArtist: String {
        if let inc = player.incoming {
            return "crossfading · \(inc.artist)"
        }
        return player.currentTrack?.artist ?? "Add a folder or playlist to begin"
    }

    // FADER_VISIBLE_ERRORS_V1
    private var isPlaybackError: Bool {
        let message = player.status.lowercased()
        return message.contains("couldn't load")
            || message.contains("unavailable")
            || message.contains("couldn't")
            || message.contains("failed")
    }

    @ViewBuilder private var statusLine: some View {
        HStack(spacing: 6) {
            if isPlaybackError {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
            } else if player.isCrossfading {
                Image(systemName: "waveform")
                    .foregroundStyle(Color.accentColor)
            }

            Text(player.status)
                .font(.caption)
                .fontWeight(isPlaybackError ? .semibold : .regular)
                .foregroundStyle(
                    isPlaybackError ? Color.red : Color.secondary
                )
                .lineLimit(1)
        }
    }
}
