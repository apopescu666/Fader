
// MARK: - FADER_PLAYLIST_ARTWORK_CACHE_V1

import SwiftUI
import AppKit

// Native macOS table: insertion indicator and .move drag/drop are provided
// by NSTableView. No filesystem or metadata reads occur during a drag.
// FADER_PLAYLIST_VISUAL_POLISH_V3
// FADER_SELECTION_TOGGLE_ALL_P29137
// FADER_SELECTION_TOGGLE_FIX_P29138
struct PlaylistNativeTable: NSViewRepresentable {
    struct Item {
        let path: String
        let track: Track

        // FADER_P2911_ROW_IDENTITY
        // Defaults to path for existing saved playlists.
        // Current Queue can use Track.id.uuidString.
        var rowID: String { identity ?? path }
        var identity: String? = nil
    }

    // FADER_P299D1_NATIVE_ACTION_PARITY
    struct CopyDestination: Identifiable {
        let id: UUID
        let name: String
    }

    let playlistID: UUID
    let items: [Item]
    // FADER_PLAYLIST_FOLLOW_V1
    let playingPath: String?
    let playbackRunning: Bool
    var scrollRequest: Int = 0
    @Binding var selectedIndices: Set<Int>
    let play: (Int) -> Void
    let playPause: (Int) -> Void
    let remove: (Int) -> Void
    let move: (IndexSet, Int) -> Void
    let addToQueue: (Int) -> Void
    let copyToPlaylist: (Int, UUID) -> Void
    let copyDestinations: [CopyDestination]
    // FADER_P2911_QUEUE_CELL_MODE
    var isQueueMode: Bool = false
    // FADER_P2911_REORDER_GUARD
    var allowsReordering: Bool = true
    // D22: separate external COPY contract, no changes to native MOVE/reorder.
    var receiveExternalPaths: (([String], Int) -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let table = FaderToggleSelectionTable()
        table.headerView = nil
        // STAGE10D25_V52_VISUAL: spacing only; native table behavior unchanged.
        table.rowHeight = 60
        table.intercellSpacing = NSSize(width: 0, height: 1)
        table.allowsMultipleSelection = true
        table.allowsEmptySelection = true
        table.selectionHighlightStyle = .regular
        table.backgroundColor = .clear
        // FADER_PLAYLIST_FOLDER_STYLE_V4
        // Subtle separators, consistent with Music Folders.
        table.gridStyleMask = [.solidHorizontalGridLineMask]
        table.gridColor = NSColor.separatorColor.withAlphaComponent(0.24)
        table.columnAutoresizingStyle = .uniformColumnAutoresizingStyle

        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("song"))
        column.resizingMask = .autoresizingMask
        table.addTableColumn(column)
        table.delegate = context.coordinator
        table.dataSource = context.coordinator
        // FADER_NATIVE_DOUBLE_CLICK_PLAY
        table.target = context.coordinator
        table.doubleAction = #selector(Coordinator.handleDoubleClick(_:))
        table.registerForDraggedTypes([
            Coordinator.dragType, .string,
            NSPasteboard.PasteboardType("public.utf8-plain-text"), .fileURL
        ])
        table.setDraggingSourceOperationMask(.move, forLocal: true)
        table.setDraggingSourceOperationMask([], forLocal: false)


        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        context.coordinator.table = table
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        let c = context.coordinator
        c.parent = self
        guard let table = c.table else { return }
        let paths = items.map(\.rowID)
        let playingChanged = c.lastPlayingPath != playingPath
        let runningChanged = c.lastPlaybackRunning != playbackRunning
        let playlistChanged = c.lastPlaylistID != playlistID
        let signature = renderSignature(items)
        if c.paths != paths || c.renderVersion != signature || playlistChanged {
            c.previousPaths = c.paths
            c.paths = paths
            c.renderVersion = signature
            c.lastPlayingPath = playingPath
            c.lastPlaybackRunning = playbackRunning
            c.lastPlaylistID = playlistID
            // FADER_P299D3_ANIMATED_MOVE
            if let pending = c.pendingNativeMove,
               pending.source != pending.target,
               paths.count == c.previousPaths.count {

                var expected = c.previousPaths
                let moved = expected.remove(at: pending.source)
                expected.insert(moved, at: pending.target)

                if expected == paths {
                    table.beginUpdates()
                    table.moveRow(
                        at: pending.source,
                        to: pending.target
                    )
                    table.endUpdates()
                    print("FADER NATIVE ANIMATION: MOVE ROW")
                } else {
                    table.reloadData()
                    print("FADER NATIVE ANIMATION: RELOAD")
                }
            } else {
                table.reloadData()
            }
            c.pendingNativeMove = nil
        } else if playingChanged || runningChanged {
            let affected = FaderTrackListing.affectedRows(in: paths,
                previous: c.lastPlayingPath, current: playingPath)
            c.lastPlayingPath = playingPath
            c.lastPlaybackRunning = playbackRunning
            if !affected.isEmpty {
                table.reloadData(forRowIndexes: affected, columnIndexes: IndexSet(integer: 0))
            }
        }
        let wanted = IndexSet(selectedIndices.filter { $0 >= 0 && $0 < items.count })
        if table.selectedRowIndexes != wanted {
            c.updatingSelection = true
            table.selectRowIndexes(wanted, byExtendingSelection: false)
            c.updatingSelection = false
        }

        // FADER_PLAYLIST_FOLLOW_V1: auto-scroll on track change.
        if playingChanged || playlistChanged || c.lastScrollRequest != scrollRequest {
            c.lastScrollRequest = scrollRequest
            if let path = playingPath,
               let row = items.firstIndex(where: { $0.rowID == path }) {
                DispatchQueue.main.async { [weak table] in
                    guard let table,
                          row < table.numberOfRows else { return }

                    table.scrollRowToVisible(row)

                    let rowRect = table.rect(ofRow: row)
                    let clip = scroll.contentView
                    let targetY = max(
                        0,
                        rowRect.midY - clip.bounds.height / 2
                    )
                    clip.scroll(to: NSPoint(x: 0, y: targetY))
                    scroll.reflectScrolledClipView(clip)
                }
            }
        }
    }

    private func renderSignature(_ items: [Item]) -> Int {
        var hasher = Hasher()
        for item in items {
            hasher.combine(item.rowID)
            hasher.combine(item.track.title)
            hasher.combine(item.track.artist)
            hasher.combine(item.track.album)
            hasher.combine(item.track.duration)
            hasher.combine(item.track.artworkIdentity)
        }
        for destination in copyDestinations {
            hasher.combine(destination.id)
            hasher.combine(destination.name)
        }
        hasher.combine(isQueueMode)
        return hasher.finalize()
    }

    final class Coordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
        static let dragType = NSPasteboard.PasteboardType("com.fader.playlist-native-move")
        var parent: PlaylistNativeTable
        weak var table: NSTableView?
        var paths: [String] = []
        var previousPaths: [String] = []
        var pendingNativeMove: (source: Int, target: Int)? = nil
        var renderVersion = 0
        var updatingSelection = false
        var lastScrollRequest = -1
        var lastPlayingPath: String? = nil
        var lastPlaybackRunning = false
        var lastPlaylistID: UUID? = nil

        init(_ parent: PlaylistNativeTable) { self.parent = parent }

        // FADER_NATIVE_DOUBLE_CLICK_PLAY
        @objc func handleDoubleClick(_ sender: NSTableView) {
            let row = sender.clickedRow
            guard parent.items.indices.contains(row) else { return }
            parent.play(row)
            // STAGE10D39: playback highlight is independent of native selection.
            // Clear only the double-click selection; single/multi-select and D&D stay native.
            parent.selectedIndices = []
            sender.deselectAll(nil)
        }

        func numberOfRows(in tableView: NSTableView) -> Int { parent.items.count }

        func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
            guard parent.items.indices.contains(row) else { return nil }
            let id = NSUserInterfaceItemIdentifier("PlaylistSongCell")
            let cell = (tableView.makeView(withIdentifier: id, owner: nil) as? NSTableCellView)
                ?? NSTableCellView()
            cell.identifier = id
            let track = parent.items[row].track
            let content = PlaylistSongCellContent(
                track: track,
                isPlaying: parent.playingPath == parent.items[row].rowID,
                playbackRunning: parent.playbackRunning,
                play: { [weak self] in self?.parent.playPause(row) },
                remove: { [weak self] in self?.parent.remove(row) },
                addToQueue: { [weak self] in self?.parent.addToQueue(row) },
                copyToPlaylist: { [weak self] destinationID in
                    self?.parent.copyToPlaylist(row, destinationID)
                },
                copyDestinations: parent.copyDestinations,
                isQueueMode: parent.isQueueMode)
            if let host = cell.subviews.first as? NSHostingView<PlaylistSongCellContent> {
                host.rootView = content
            } else {
                cell.subviews.forEach { $0.removeFromSuperview() }
                let host = NSHostingView(rootView: content)
                host.translatesAutoresizingMaskIntoConstraints = false
                cell.addSubview(host)
                NSLayoutConstraint.activate([
                    host.leadingAnchor.constraint(equalTo: cell.leadingAnchor),
                    host.trailingAnchor.constraint(equalTo: cell.trailingAnchor),
                    host.topAnchor.constraint(equalTo: cell.topAnchor),
                    host.bottomAnchor.constraint(equalTo: cell.bottomAnchor)
                ])
            }
            return cell
        }

        func tableViewSelectionDidChange(_ notification: Notification) {
            guard !updatingSelection, let table else { return }
            let indices = Set(table.selectedRowIndexes)
            if parent.selectedIndices != indices {
                parent.selectedIndices = indices
            }
        }

        func tableView(_ tableView: NSTableView, pasteboardWriterForRow row: Int) -> NSPasteboardWriting? {
            (tableView as? FaderToggleSelectionTable)?.cancelToggleForDrag()
            guard parent.allowsReordering,
                  parent.items.indices.contains(row) else { return nil }
            let item = NSPasteboardItem()
            // Native private payload for internal playlist reorder (unchanged).
            item.setString(parent.playlistID.uuidString + ":" + String(row), forType: Self.dragType)
            if !parent.isQueueMode {
                // Public text payload accepted by Current Queue sidebar.
                // Preserve playlist order for a multi-row drag.
                let indices = tableView.selectedRowIndexes.contains(row)
                    ? Array(tableView.selectedRowIndexes).filter { parent.items.indices.contains($0) }
                    : [row]
                let paths = indices.map { parent.items[$0].path }
                if let data = try? JSONEncoder().encode(paths) {
                    item.setString("fader-tracks:" + data.base64EncodedString(), forType: .string)
                }
            }
            return item
        }

        // D22: Only foreign drags are decoded here. Internal reordering is
        // deliberately left to the ORIGINAL validateDrop/acceptDrop branches.
        private func d22Paths(_ pasteboard: NSPasteboard) -> [String] {
            let text = pasteboard.string(forType: .string)
                ?? pasteboard.string(forType: NSPasteboard.PasteboardType("public.utf8-plain-text"))
            if let text {
                if text.hasPrefix("fader-tracks:") {
                    let encoded = String(text.dropFirst("fader-tracks:".count))
                    if let data = Data(base64Encoded: encoded),
                       let paths = try? JSONDecoder().decode([String].self, from: data) {
                        return paths.filter { $0.hasPrefix("/") }
                    }
                } else if text.hasPrefix("fader-track:") {
                    let path = String(text.dropFirst("fader-track:".count))
                    return path.hasPrefix("/") ? [path] : []
                }
            }
            if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL] {
                return urls.filter { $0.isFileURL }.map { $0.standardizedFileURL.path }
            }
            return []
        }

        func tableView(_ tableView: NSTableView, validateDrop info: NSDraggingInfo,
                       proposedRow row: Int, proposedDropOperation operation: NSTableView.DropOperation) -> NSDragOperation {
            // Only permit external COPY if this is not Fader's native MOVE.
            // Do not alter any logic below for internal moves.
            if info.draggingPasteboard.string(forType: Self.dragType) == nil,
               parent.receiveExternalPaths != nil,
               !d22Paths(info.draggingPasteboard).isEmpty {
                tableView.setDropRow(max(0, min(row, parent.items.count)), dropOperation: .above)
                return .copy
            }



            guard parent.allowsReordering else { return [] }
            guard let raw = info.draggingPasteboard.string(forType: Self.dragType),
                  raw.hasPrefix(parent.playlistID.uuidString + ":"),
                  let source = Int(raw.dropFirst(parent.playlistID.uuidString.count + 1)),
                  parent.items.indices.contains(source),
                  row >= 0, row <= parent.items.count else { return [] }
            tableView.setDropRow(row, dropOperation: .above)
            return .move
        }

        func tableView(_ tableView: NSTableView, acceptDrop info: NSDraggingInfo,
                       row: Int, dropOperation: NSTableView.DropOperation) -> Bool {
            if info.draggingPasteboard.string(forType: Self.dragType) == nil,
               let receive = parent.receiveExternalPaths {
                let paths = d22Paths(info.draggingPasteboard)
                guard !paths.isEmpty else { return false }
                print("FADER D23 CENTRAL DROP: paths=\(paths.count) row=\(row) queue=\(parent.isQueueMode)")
                receive(paths, max(0, min(row, parent.items.count)))
                return true
            }

            guard let raw = info.draggingPasteboard.string(forType: Self.dragType),
                  raw.hasPrefix(parent.playlistID.uuidString + ":"),
                  let source = Int(raw.dropFirst(parent.playlistID.uuidString.count + 1)),
                  parent.items.indices.contains(source),
                  row >= 0, row <= parent.items.count else { return false }
            guard row != source && row != source + 1 else { return true }
            let target = row > source ? row - 1 : row
            pendingNativeMove = (source: source, target: target)
            parent.move(IndexSet(integer: source), row)
            return true
        }
    }
}

// FADER_PLAYLIST_VISUAL_FOLLOW_V2
// Folder-inspired appearance; native playlist drag/drop is preserved.
private struct PlaylistSongCellContent: View {
    @AppStorage("Fader.appearance.useSystemAccent") private var useSystemAccent = true
    @AppStorage("Fader.appearance.accentHex") private var accentHex = "#4B8DF8"
    private var faderAccent: Color {
        useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex)
    }
    let track: Track
    let isPlaying: Bool
    let playbackRunning: Bool
    let play: () -> Void
    let remove: () -> Void
    let addToQueue: () -> Void
    let copyToPlaylist: (UUID) -> Void
    let copyDestinations: [PlaylistNativeTable.CopyDestination]
    let isQueueMode: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName:
                isPlaying ? "speaker.wave.2.fill" : "music.note"
            )
            .foregroundStyle(
                isPlaying ? faderAccent : Color.secondary
            )
            .frame(width: 18)

            if let art = FaderArtworkImageCache.image(for: track) {
                Image(nsImage: art)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            } else {
                Image(systemName: "music.note")
                    .foregroundStyle(.secondary)
                    .frame(width: 40, height: 40)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(track.title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)

                Text(track.artist)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text(durationText(track.duration))
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(.secondary)

            Button(action: play) {
                Image(systemName: isPlaying && playbackRunning ? "pause.circle" : "play.circle")
            }
            .buttonStyle(.borderless)
            .help("Play from this track")

            if !isQueueMode {
                Button(action: addToQueue) {
                    Image(systemName: "plus.circle")
                }
                .buttonStyle(.borderless)
                .help("Add to Queue")
            }

            Menu {
                if copyDestinations.isEmpty {
                    Text("Create a playlist first")
                } else {
                    ForEach(copyDestinations) { destination in
                        Button(destination.name) {
                            copyToPlaylist(destination.id)
                        }
                    }
                }

                Divider()

                Button(isQueueMode ? "Remove from Queue" : "Remove from Playlist", role: .destructive) {
                    remove()
                }
            } label: {
                Image(systemName: "text.badge.plus")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .help(isQueueMode ? "Copy to Playlist / Remove from Queue" : "Copy to Playlist / Remove from Playlist")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .frame(
            maxWidth: .infinity,
            maxHeight: .infinity,
            alignment: .leading
        )
        .background(
            isPlaying
                ? faderAccent.opacity(0.10)
                : Color.clear
        )
        .tint(faderAccent)
    }

    private func durationText(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds > 0 else {
            return "--:--"
        }

        let n = Int(seconds)
        return String(
            format: "%d:%02d",
            n / 60,
            n % 60
        )
    }
}
