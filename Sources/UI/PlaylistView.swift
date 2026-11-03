import SwiftUI
import AppKit

// STAGE10D40E: the user-approved broom silhouette, rasterized into a small template icon.
// No external resource required; macOS secondary foreground is applied dynamically.
private struct FaderBroomIcon: View {
    private static let icon: NSImage = {
        let encoded =
            "iVBORw0KGgoAAAANSUhEUgAAAEgAAABICAYAAABV7bNHAAAG4ElEQVR42u2caagVZRjHf2fxqrlFV80Wy1LLFiktl9KiIFqpVEzI" +
            "FgstshVNghYi0wqsaFFsMVqIVKSFvhiimEUUaX2wUiuVSpIkK7eS21lm+jDPy3nuw5zl3s6dc7kzL7zMuXNmDu/7n/+z/J/3nQtJ" +
            "S1rSklaXlpIOsAD4A9gMjE+gCVpGjpMAH/DkuDWBJmBOWkD6XMApAEX5nLBHjhcLIEUByAdWJfAE7AF4T0DJKxMbn7AnMLGRQIsA" +
            "kxdwNijwYm9eLyn2FOXztYlpBewZDBxQ7PGAr4AmFfor2mZXjl4+MBPoK8A40JYAuTibmEsMjwR2GfbsAHqZ5DG2vucOYVFBhfa5" +
            "5prYsqc78K1hzx5gQMKeoE0WxujE8Km4s0dHr/UmMdwPnKikR6yz5glKa7nEcFlMondN5rXCsKdFsumEPcCpwGHDnlUJe0rsed7I" +
            "igJwfhLagzaIoFroSabsA2vjblqaGQ+qxNCJ0qsS9gS9N7DTsGeTKnnEPjGcEcKemxP2BD0rbNHs+SERpSVmXEZptcLJivsSWVGK" +
            "TKsFlJyAtBs4Ku7saRJ2jBbW6MRwQcKeUnvdyIoDwAlxzn2cyUyRrPlvBZAPvBpn9rhJzxMwfOWcPeAgMCJu7MkA3aSngGaCymAB" +
            "+FeB44vMGGyYFgtz0m22Yk4hxEHPl+uy9aJrZw7hPnAlQfG9H8EKxRZxxMMklLsKYl5A+Rj4hNKyT5f2NZOMr/kReERUe1q+X6++" +
            "z4kPoiv7IJfcNQlbfPE1RQPESkqbD0YCbwEvdxb/k+7AQTj23K3AcH5Gr1C4/iVwk+guOgN7UlWcaL3kwyXA1wYMvfGpoKKXL/Li" +
            "OWB4ZzCDoWICHQFSSlg0QP6eCLwrZhYWvSyr9gFjG8Ekt41tEfAP8BHQp86CUFcGDxEU2yfKucHA48IU36h3lwu1yPklUUdqB0Bf" +
            "eUJugGPrOBD3G+eoibq+GbhdHkgGmA58EWJ+LnF8qBGpjHPMD8tTXA4cYRjkWJZpI6ucNOgFfKO0VcFEr72iv06R+0YBb4jE0BFu" +
            "ZKOd9dE1Zr21Npf1vqbYoNkRFr3WAlfLff2BOcB2YE2jQn1K6aJy4JwLvEOwSaBfjQN1ZnCXUeVh3QsBagfwgIDUBAxsBEDpChNz" +
            "pjdExKIb+PIaVLVjzqUmhPtVukfrPT++OPUpjfA9KUXjqeIghxlWubzFlwiXA35SAKQqAHyWArZYAzh+iPk5p/6YGVOHA+NynQtk" +
            "wm5QB4A75bvuwpIBwDZ1zaIKT9OdG0KwltVecHxTTbw/KgbpJ94D+EVpopwa2Chz/bHAvcA0yi/YZRU4W8s45fYApFcyslEwqKeY" +
            "1NshT9g9sfUCRu82RqsRwPd1AkcDNCsqgAYBnxmnGOYo3eeNEvrTEuUyIZotq8x1dx3B0QBNjwqglUZJ1+Igy/kc/fct4sTrCY7+" +
            "rWuiAuhQG0JuXvpqkxLoaNITeNEAWy9wdE36ono66Uoo52v8DV8ASUupUwNTlD4GWAyMo/R+VroDgkpeJAdRlFoXmxTfq/IE3xch" +
            "m1WT7wMsVFEvr0zBM+WL/8sgV+Y4KSoN1lcy4XJO2ZnIQkn0bCifqkSn8xHOke4BPhBf5NUBIDeWX8WUI5UZ4whWFPYqp52TQeUJ" +
            "lnh1uwJYV6aw5QOfAjeKqPRrYGYt3xVVSSRSeaFpemvIIOcrfzOC4AU1q751pHoSOJng9YBqjtprRwT7sFEqvklprcXAUuA6Ey3G" +
            "qAm3GGA2AudJnrSthhDvwNmlmOuZ437j23zg2aiFakZlyKkKCt8dRyvp4IsZzVaZ888GHK9KbvUCpY0JGqCcmLEFaGaUMqNJas67" +
            "KC3EdVPVwiaT5ziQBhKsTc2VaiOS3R404BSrONxlwNMh93jia14xkqcIXBglg3ooc5hYY+i03/cxkbBoJvynCfUOgN+As5VA9sx9" +
            "T9D6DWZXim2O2gcdL74lDITpIlQfpfUiotNcWeAY47h1HrROasm65uwmOxm4vow5HiTYNb/H3LOhUQ46DJxmqQe5iYdRWw90hqr5" +
            "+CI5JgiDnExwE10h99gyiDsuJfgHAdof6R0dka9ipM2kU5JEfqfKnGeWMTFdC2qWScwSx78lRAzvlFWNaSHscbJluKQLDhwH8IWN" +
            "AKhSIW0ocI+E72rUtoO+XNijnfNhSmtsW01xvmDYtZ3WL6tsU6lIp2ypGq/JKLD6A7dJZv07cIOcn6MqlwXVW6QCOU6B48os8zoL" +
            "e6z5ZdspCi2gx8mxt6oy2v6MXPOmOb+J+i9/t+nJd7SUKRp/NQQ4DTgDOF0+/0WwnNNdzC8lcmUNwRbgfXTx3WRJS1oXbf8B9f2Z" +
            "V1AyxzcAAAAASUVORK5CYII="
        guard let data = Data(base64Encoded: encoded),
              let image = NSImage(data: data) else {
            return NSImage(size: NSSize(width: 24, height: 24))
        }
        image.isTemplate = true
        return image
    }()

    var body: some View {
        Image(nsImage: Self.icon)
            .renderingMode(.template)
            .resizable()
            .interpolation(.high)
            .scaledToFit()
            .foregroundStyle(.secondary)
            .frame(width: 23, height: 23)
            .accessibilityHidden(true)
    }
}

// FADER_QUEUE_MANAGEMENT_V1
// Queue UI only. Folders, playlists, and audio engine remain untouched.
// FADER_QUEUE_TO_PLAYLIST_V2
struct QueuePlaylistDestination: Identifiable {
    let id: UUID
    let name: String
}

struct PlaylistView: View {
    var onAddFolder: (() -> Void)? = nil

    @EnvironmentObject var player: Player
    @AppStorage("Fader.appearance.useSystemAccent", store: FaderStorage.defaults) private var selectionUseSystemAccent = true
    @AppStorage("Fader.appearance.accentHex", store: FaderStorage.defaults) private var selectionAccentHex = "#4B8DF8"
    private var selectionAccent: Color {
        selectionUseSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(selectionAccentHex)
    }
    let playlistDestinations: [QueuePlaylistDestination]
    let addToPlaylist: (UUID, [Track]) -> Void
    @State private var filter = ""
    @State private var goToPlayingRequest = 0
    @State private var selectedIDs: Set<UUID> = []
    @State private var selectionAnchor: UUID? = nil
    @State private var confirmClear = false
    @State private var confirmRemove = false

    // FADER_P08_EXPLICIT_DROP_V1
    // FADER_P29111_QUEUE_SELECTION_INTERNAL_DROP
    // Resolve internal drags using the EXISTING asynchronous Finder import path.
    // This keeps path-based deduplication and avoids reading NAS files on the UI thread.
    private func acceptInternalFolderTracks(_ providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first(where: {
            $0.canLoadObject(ofClass: NSString.self)
        }) else { return false }
        _ = provider.loadObject(ofClass: NSString.self) { object, _ in
            guard let payload = object as? String else { return }
            let paths: [String]
            if payload.hasPrefix("fader-tracks:") {
                let encoded = String(payload.dropFirst("fader-tracks:".count))
                guard let data = Data(base64Encoded: encoded),
                      let decoded = try? JSONDecoder().decode([String].self, from: data)
                else { return }
                paths = decoded
            } else if payload.hasPrefix("fader-track:") {
                paths = [String(payload.dropFirst("fader-track:".count))]
            } else {
                return
            }
            let urls = paths.filter { $0.hasPrefix("/") }.map { URL(fileURLWithPath: $0) }
            guard !urls.isEmpty else { return }
            DispatchQueue.main.async {
                self.appendDroppedURLsToQueue(urls)
            }
        }
        return true
    }

    private func appendDroppedURLsToQueue(_ urls: [URL], insertionSlot: Int? = nil) {
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
                // FADER_QUEUE_FINDER_DEDUP_P08_V1
                // Keep existing queue entries unchanged; add only new paths.
                // Deduplicate both against the queue and inside this drop.
                var knownPaths = Set(player.queue.map {
                    $0.normalizedPath
                })
                let uniqueTracks = tracks.filter { track in
                    knownPaths.insert(track.normalizedPath).inserted
                }
                if tracks.isEmpty {
                    player.status = "No supported audio files in Current Queue drop."
                } else if uniqueTracks.isEmpty {
                    player.status = "All dropped songs are already in Current Queue."
                } else {
                    if let insertionSlot {
                        player.insertExternalQueueTracks(uniqueTracks, at: insertionSlot)
                    } else {
                        player.append(uniqueTracks)
                    }
                    let skipped = tracks.count - uniqueTracks.count
                    if skipped > 0 {
                        player.status = "Added \(uniqueTracks.count) song(s); skipped \(skipped) duplicate(s)."
                    }
                }
            }
        }
    }

    private var filtered: [Track] {
        let q = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return player.queue }
        return player.queue.filter {
            $0.title.localizedStandardContains(q) ||
            $0.artist.localizedStandardContains(q) ||
            $0.album.localizedStandardContains(q)
        }
    }

    private func toggleQueuePlayPause(_ row: Int) {
        let tracks = filtered
        guard tracks.indices.contains(row) else { return }
        let track = tracks[row]
        if !player.isFolderPlayback && !player.isFinderPlayback && player.currentTrack?.id == track.id {
            player.togglePlay()
        } else {
            play(track)
        }
    }

    private var queueSelection: Binding<Set<Int>> {
        Binding(
                        get: {
                            let tracks = filtered
                            return Set(tracks.indices.filter {
                                selectedIDs.contains(tracks[$0].id)
                            })
                        },
                        set: { indices in
                            let tracks = filtered
                            selectedIDs = Set(indices.compactMap { index in
                                tracks.indices.contains(index)
                                    ? tracks[index].id : nil
                            })
                        }
                    )
    }

    private var queueTable: some View {
        let tracks = filtered
        return PlaylistNativeTable(
                    playlistID: UUID(uuidString: "A92A1111-1111-4111-8111-111111111111")!,
                    items: tracks.map { track in
                        PlaylistNativeTable.Item(
                            path: track.normalizedPath,
                            track: track,
                            identity: track.id.uuidString
                        )
                    },
                    playingPath: player.isFolderPlayback || player.isFinderPlayback
                        ? nil
                        : player.currentTrack?.id.uuidString,
                    playbackRunning: player.isPlaying,
                    scrollRequest: goToPlayingRequest,
                    selectedIndices: queueSelection,
                    play: { row in
                        guard tracks.indices.contains(row) else { return }
                        play(tracks[row])
                    },
                    playPause: { row in toggleQueuePlayPause(row) },
                    remove: { row in
                        guard tracks.indices.contains(row) else { return }
                        remove(tracks[row])
                    },
                    move: { rows, slot in
                        guard filter.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                              rows.count == 1,
                              let source = rows.first,
                              player.queue.indices.contains(source) else { return }
                        _ = player.reorderQueue(
                            sourceID: player.queue[source].id,
                            toInsertionSlot: slot
                        )
                    },
                    addToQueue: { _ in },
                    copyToPlaylist: { row, destination in
                        guard tracks.indices.contains(row) else { return }
                        addToPlaylist(destination, [tracks[row]])
                    },
                    copyDestinations: playlistDestinations.map {
                        PlaylistNativeTable.CopyDestination(
                            id: $0.id,
                            name: $0.name
                        )
                    },
                    isQueueMode: true,
                    allowsReordering: filter.trimmingCharacters(
                        in: .whitespacesAndNewlines
                    ).isEmpty,
                    receiveExternalPaths: { paths, dropRow in
                        // Map the visible table slot to the underlying queue slot.
                        let slot: Int
                        if dropRow < tracks.count {
                            let targetID = tracks[dropRow].id
                            slot = player.queue.firstIndex(where: { $0.id == targetID })
                                ?? player.queue.count
                        } else {
                            slot = player.queue.count
                        }
                        let urls = paths.map { URL(fileURLWithPath: $0).standardizedFileURL }
                        appendDroppedURLsToQueue(urls, insertionSlot: slot)
                    }
                )
    }

    var body: some View {
        VStack(spacing: 0) {
            // STAGE10D40 — Current Queue toolbar only. Existing actions are unchanged.
            HStack(spacing: 9) {
                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    TextField("Search", text: $filter)
                        .textFieldStyle(.plain)
                    if !filter.isEmpty {
                        Button { filter = "" } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help("Clear search")
                    }
                }
                .padding(.horizontal, 9)
                .frame(height: 30)
                .background(Color(nsColor: .textBackgroundColor),
                            in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(Color(nsColor: .separatorColor).opacity(0.45), lineWidth: 1)
                        .allowsHitTesting(false)
                }

                Text(filter.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                     ? "\(player.queue.count) tracks"
                     : "\(filtered.count) / \(player.queue.count)")
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .fixedSize()

                // STAGE10D40B: icon-only actions, exactly as in the track rows.
                // Actions, eligibility and confirmation flows are unchanged.
                Menu {
                    ForEach(playlistDestinations) { target in
                        Button(target.name) {
                            addSelected(to: target.id)
                        }
                    }
                } label: {
                    Image(systemName: "text.badge.plus")
                        .font(.system(size: 19))
                        .frame(width: 28, height: 30)
                        .contentShape(Rectangle())
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .frame(width: 30, height: 30)
                .disabled(selectedIDs.isEmpty || playlistDestinations.isEmpty)
                .help("Add to Playlist")

                Button { confirmRemove = true } label: {
                    FaderBroomIcon()
                        .frame(width: 28, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .frame(width: 30, height: 30)
                .disabled(selectedIDs.isEmpty)
                .help("Remove Selected")

                Button { confirmClear = true } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 17))
                        .foregroundStyle(Color.red)
                        .frame(width: 28, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .frame(width: 30, height: 30)
                .disabled(player.queue.isEmpty)
                .help("Clear Queue")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)

            if player.queue.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "music.note.list")
                        .font(.system(size: 44))
                        .foregroundStyle(.secondary)
                    Text("Your queue is empty").font(.headline)
                    Text("Add a music folder or drag songs here to begin.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 20)
                    if let onAddFolder {
                        Button(action: onAddFolder) {
                            Label("Add Music Folder", systemImage: "folder.badge.plus")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // STAGE10D38: the empty queue has no native table drop target.
                // Accept ONLY Fader's existing internal track payload here.
                // The existing outer Finder drop target and populated native table
                // remain untouched; no new transfer or deduplication logic.
                .contentShape(Rectangle())
                .onDrop(of: ["public.utf8-plain-text", "public.plain-text"],
                        isTargeted: nil) { providers in
                    acceptInternalFolderTracks(providers)
                }
            } else {
                // FADER_P2911_NATIVE_QUEUE_UI
                queueTable
            }
        }
        // FADER_P08_EXPLICIT_DROP_V1: Finder -> Current Queue only
        .contentShape(Rectangle())
        .dropDestination(for: URL.self) { urls, _ in
            appendDroppedURLsToQueue(urls)
            return !urls.isEmpty
        }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("Fader.goToPlaying"))) { _ in
            if !player.isFolderPlayback {
                // Ensure the playing row is visible even with an active queue filter.
                filter = ""
                goToPlayingRequest += 1
            }
        }
        .onChange(of: player.queue.count) { _, _ in
            let available = Set(player.queue.map(\.id))
            selectedIDs.formIntersection(available)
            if let selectionAnchor, !available.contains(selectionAnchor) {
                self.selectionAnchor = nil
            }
        }
        .confirmationDialog(
            "Remove selected songs from Current Queue?",
            isPresented: $confirmRemove,
            titleVisibility: .visible
        ) {
            Button("Remove Selected", role: .destructive) { removeSelected() }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Original audio files will not be deleted.")
        }
        .confirmationDialog(
            "Clear all songs from Current Queue?",
            isPresented: $confirmClear,
            titleVisibility: .visible
        ) {
            Button("Clear Queue", role: .destructive) { clearQueue() }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This only removes queue entries; original files and playlists are kept. Queue playback will stop.")
        }
    }

    private func row(for track: Track) -> some View {
        let active = !player.isFolderPlayback && player.currentTrack?.id == track.id
        let selected = selectedIDs.contains(track.id)
        return HStack(spacing: 10) {
            Image(systemName: active ? "speaker.wave.2.fill" : "music.note")
                .foregroundStyle(active ? selectionAccent : Color.secondary)
                .frame(width: 18)

            if let image = FaderArtworkImageCache.image(for: track) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 40, height: 40)
            } else {
                Image(systemName: "music.note")
                    .foregroundStyle(.secondary)
                    .frame(width: 40, height: 40)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(track.title).lineLimit(1)
                Text(track.artist)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(clock(track.duration))
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(.secondary)
            Button { play(track) } label: {
                Image(systemName: "play.circle")
            }
            .buttonStyle(.borderless)
            .help("Play from this song")
            Menu {
                ForEach(playlistDestinations) { target in
                    Button(target.name) {
                        addToPlaylist(target.id, [track])
                    }
                }
            } label: {
                Image(systemName: "text.badge.plus")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .disabled(playlistDestinations.isEmpty)
            .help("Add this song to a saved playlist")

            Button { remove(track) } label: {
                Image(systemName: "minus.circle")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .help("Remove from Current Queue (keep audio file)")
        }
        .padding(.vertical, 3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            selected ? selectionAccent.opacity(0.40) :
            (active ? selectionAccent.opacity(0.12) : Color.clear)
        )
    }

    private func play(_ track: Track) {
        guard let index = player.queue.firstIndex(where: { $0.id == track.id }) else { return }
        player.playQueueIndex(index)
    }

    private func select(_ track: Track) {
        let flags = NSEvent.modifierFlags
        if flags.contains(.shift),
           let anchor = selectionAnchor,
           let start = filtered.firstIndex(where: { $0.id == anchor }),
           let end = filtered.firstIndex(where: { $0.id == track.id }) {
            let ids = Set(filtered[min(start, end)...max(start, end)].map(\.id))
            if flags.contains(.command) {
                selectedIDs.formUnion(ids)
            } else {
                selectedIDs = ids
            }
        } else if flags.contains(.command) {
            if !selectedIDs.insert(track.id).inserted {
                selectedIDs.remove(track.id)
            }
            selectionAnchor = track.id
        } else {
            selectedIDs = [track.id]
            selectionAnchor = track.id
        }
    }

    private func addSelected(to playlistID: UUID) {
        let tracks = player.queue.filter { selectedIDs.contains($0.id) }
        guard !tracks.isEmpty else { return }
        addToPlaylist(playlistID, tracks)
        // Adding to a playlist does not remove entries from Current Queue.
    }

    private func remove(_ track: Track) {
        guard let index = player.queue.firstIndex(where: { $0.id == track.id }) else { return }
        player.remove(atOffsets: IndexSet(integer: index))
        selectedIDs.remove(track.id)
    }

    private func removeSelected() {
        let indexes = IndexSet(player.queue.indices.filter {
            selectedIDs.contains(player.queue[$0].id)
        })
        guard !indexes.isEmpty else { return }
        player.remove(atOffsets: indexes)
        selectedIDs.removeAll()
        selectionAnchor = nil
    }

    private func clearQueue() {
        guard !player.queue.isEmpty else { return }
        player.remove(atOffsets: IndexSet(integersIn: 0..<player.queue.count))
        selectedIDs.removeAll()
        selectionAnchor = nil
    }

    private func scrollToCurrent(_ proxy: ScrollViewProxy) {
        guard !player.isFolderPlayback,
              let current = player.currentTrack,
              filtered.contains(where: { $0.id == current.id }) else { return }
        let id = current.id
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            guard !player.isFolderPlayback, player.currentTrack?.id == id else { return }
            withAnimation(.easeInOut(duration: 0.20)) {
                proxy.scrollTo(id, anchor: .center)
            }
        }
    }
}
